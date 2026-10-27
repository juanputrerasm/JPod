package com.mtm2.jpod.io.audio;

/**
 * Renders a {@link ModModule} to 16-bit stereo PCM.
 *
 * <p>Follows ProTracker's timing (speed in ticks per row, tempo in BPM, a tick lasting
 * {@code 2.5 / tempo} seconds) and its effect set: arpeggio, portamento, tone
 * portamento, vibrato, tremolo, panning, sample offset, volume slide, position jump,
 * set volume, pattern break, the {@code Exy} extended commands (fine slides,
 * glissando-free finetune, pattern loop, retrigger, note cut, note delay, pattern
 * delay) and speed/tempo.
 *
 * <p>A song ends when it runs off its order list or when it reaches a row it has
 * already played. The second rule is what gives a song that loops forever, through a
 * backwards position jump, a finite length that can be shown and seeked; rows replayed
 * by a pattern loop ({@code E6x}) do not count as revisits.
 *
 * <p>Not thread-safe: one renderer belongs to one playback thread.
 */
public final class ModRenderer {

    /** PAL Amiga clock, the one ProTracker periods are relative to. */
    private static final double AMIGA_CLOCK = 7093789.2;

    /** ProTracker's periods for C-1 to B-3 at finetune 0. */
    private static final int[] BASE_PERIODS = {
            856, 808, 762, 720, 678, 640, 604, 570, 538, 508, 480, 453,
            428, 404, 381, 360, 339, 320, 302, 285, 269, 254, 240, 226,
            214, 202, 190, 180, 170, 160, 151, 143, 135, 127, 120, 113,
    };

    /** Five octaves, C-0 to B-4, so extended-range modules still find their notes. */
    private static final int[] NOTE_PERIODS = new int[60];

    private static final int[] SINE = {
            0, 24, 49, 74, 97, 120, 141, 161, 180, 197, 212, 224, 235, 244, 250, 253,
            255, 253, 250, 244, 235, 224, 212, 197, 180, 161, 141, 120, 97, 74, 49, 24,
    };

    static {
        for (int i = 0; i < 12; i++) NOTE_PERIODS[i] = BASE_PERIODS[i] * 2;
        System.arraycopy(BASE_PERIODS, 0, NOTE_PERIODS, 12, 36);
        for (int i = 0; i < 12; i++) NOTE_PERIODS[48 + i] = BASE_PERIODS[24 + i] / 2;
    }

    private final ModModule module;
    private final int sampleRate;
    private final Channel[] channels;
    private final float gain;
    private final int minPeriod;
    private final int maxPeriod;

    // Sequencer state.
    private int speed;
    private int tempo;
    private int order;
    private int row;
    private int tick;
    private int ticksThisRow;
    private int samplesLeftInTick;
    private boolean ended;
    private boolean[][] visited;
    private boolean patternLoopActive;
    private int jumpOrder;
    private int breakRow;
    private int loopJumpRow;
    private int patternDelay;
    private long framesRendered;

    public ModRenderer(ModModule module, int sampleRate) {
        this.module = module;
        this.sampleRate = sampleRate;
        this.channels = new Channel[module.channels()];
        // Amiga style LRRL stereo, softened so a channel is never only in one ear.
        for (int c = 0; c < channels.length; c++) {
            int phase = c % 4;
            channels[c] = new Channel(phase == 0 || phase == 3 ? -0.6f : 0.6f);
        }
        this.gain = Math.min(0.5f, 1.9f / module.channels());
        // ProTracker stops slides at B-3 and C-1; extended modules get the wider range.
        boolean classic = module.channels() == 4;
        this.minPeriod = classic ? 113 : 28;
        this.maxPeriod = classic ? 856 : 3424;
        reset();
    }

    /** Output frames per second. */
    public int sampleRate() {
        return sampleRate;
    }

    /** Stereo frames rendered or skipped since the start of the song. */
    public long position() {
        return framesRendered;
    }

    /** True once the song has finished. */
    public boolean ended() {
        return ended;
    }

    /** Rewinds to the start of the song. */
    public void reset() {
        speed = 6;
        tempo = 125;
        order = 0;
        row = 0;
        tick = 0;
        ticksThisRow = 0;
        samplesLeftInTick = 0;
        ended = module.songLength() == 0;
        visited = new boolean[module.songLength()][ModModule.ROWS];
        patternLoopActive = false;
        framesRendered = 0;
        for (Channel channel : channels) channel.reset();
    }

    /**
     * The song's length in frames, found by playing it through without mixing.
     * Leaves this renderer rewound to the start.
     */
    public long durationFrames() {
        reset();
        while (!ended) {
            if (!advanceTick()) break;
            framesRendered += samplesLeftInTick;
            samplesLeftInTick = 0;
        }
        long total = framesRendered;
        reset();
        return total;
    }

    /** Moves playback to {@code frame}, without mixing what lies before it. */
    public void seek(long frame) {
        reset();
        while (!ended && framesRendered < frame) {
            if (samplesLeftInTick == 0 && !advanceTick()) break;
            int step = (int) Math.min(samplesLeftInTick, frame - framesRendered);
            for (Channel channel : channels) channel.advance(step);
            samplesLeftInTick -= step;
            framesRendered += step;
        }
    }

    /**
     * Renders up to {@code frames} stereo frames into {@code out} as interleaved
     * left/right samples.
     *
     * @return frames written; fewer than asked only at the end of the song
     */
    public int render(short[] out, int frames) {
        int written = 0;
        while (written < frames && !ended) {
            if (samplesLeftInTick == 0 && !advanceTick()) break;
            int count = Math.min(frames - written, samplesLeftInTick);
            mix(out, written, count);
            written += count;
            samplesLeftInTick -= count;
            framesRendered += count;
        }
        return written;
    }

    // -------------------------------------------------------------------------
    // Mixing
    // -------------------------------------------------------------------------

    private void mix(short[] out, int offset, int count) {
        for (int i = 0; i < count; i++) {
            float left = 0;
            float right = 0;
            for (Channel channel : channels) {
                if (!channel.active) continue;
                float value = channel.sampleValue() * channel.outputVolume / 64f;
                left += value * channel.leftGain;
                right += value * channel.rightGain;
                channel.advance(1);
            }
            out[(offset + i) * 2] = clip(left * gain * 256f);
            out[(offset + i) * 2 + 1] = clip(right * gain * 256f);
        }
    }

    private static short clip(float value) {
        if (value > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (value < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) value;
    }

    // -------------------------------------------------------------------------
    // Sequencing
    // -------------------------------------------------------------------------

    /** Processes one tick. Returns false, and marks the song ended, when it is over. */
    private boolean advanceTick() {
        if (ended) return false;
        if (tick >= ticksThisRow) {
            if (ticksThisRow > 0 && !nextRow()) {
                ended = true;
                return false;
            }
            if (!enterRow()) {
                ended = true;
                return false;
            }
        } else {
            int local = tick % speed;
            for (Channel channel : channels) {
                if (local == 0) {
                    // The start of a pattern-delay repeat: only per-tick timing effects run.
                    channel.delayRepeat(this);
                } else {
                    channel.updateTick(this, local);
                }
            }
        }
        tick++;
        samplesLeftInTick = Math.max(1, (int) Math.round(sampleRate * 2.5 / tempo));
        for (Channel channel : channels) channel.updateFrequency(this);
        return true;
    }

    /** Reads the current row and runs its tick-zero effects. */
    private boolean enterRow() {
        if (order >= module.songLength()) return false;
        if (!patternLoopActive) {
            if (visited[order][row]) return false;
            visited[order][row] = true;
        }
        jumpOrder = -1;
        breakRow = -1;
        loopJumpRow = -1;
        patternDelay = 0;
        int pattern = module.order(order);
        for (int c = 0; c < channels.length; c++) {
            channels[c].enterRow(this, module.cell(pattern, row, c));
        }
        ticksThisRow = speed * (1 + patternDelay);
        tick = 0;
        return true;
    }

    /** Moves to the row after this one, honouring jumps. Returns false past the end. */
    private boolean nextRow() {
        if (loopJumpRow >= 0) {
            row = loopJumpRow;
            return true;
        }
        if (jumpOrder >= 0 || breakRow >= 0) {
            order = jumpOrder >= 0 ? jumpOrder : order + 1;
            row = breakRow >= 0 && breakRow < ModModule.ROWS ? breakRow : 0;
        } else if (++row >= ModModule.ROWS) {
            row = 0;
            order++;
        }
        return order < module.songLength();
    }

    private static int noteIndex(int period) {
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < NOTE_PERIODS.length; i++) {
            int distance = Math.abs(NOTE_PERIODS[i] - period);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static int periodFor(int noteIndex, int fineTune) {
        int index = Math.max(0, Math.min(NOTE_PERIODS.length - 1, noteIndex));
        return (int) Math.round(NOTE_PERIODS[index] * Math.pow(2, -fineTune / 96.0));
    }

    private static int waveform(int type, int position) {
        int pos = position & 63;
        return switch (type & 3) {
            case 1 -> pos < 32 ? 255 - pos * 8 : -(255 - (pos - 32) * 8); // ramp down
            case 2 -> pos < 32 ? 255 : -255;                                // square
            default -> pos < 32 ? SINE[pos] : -SINE[pos - 32];
        };
    }

    /** One tracker channel's playback state. */
    private static final class Channel {
        private final float leftGain;
        private final float rightGain;

        private ModModule.Sample sample;
        private boolean active;
        private double position;
        private double step;
        private int period;
        private int noteIndex;
        private int fineTune;
        private int volume;
        private int outputVolume;

        private int effect;
        private int param;
        private int targetPeriod;
        private int portaSpeed;
        private int vibratoSpeed;
        private int vibratoDepth;
        private int vibratoPos;
        private int vibratoWave;
        private int tremoloSpeed;
        private int tremoloDepth;
        private int tremoloPos;
        private int tremoloWave;
        private int sampleOffset;
        private int loopRow;
        private int loopCount;
        private int periodDelta;
        private int volumeDelta;
        private int delayedPeriod;
        private ModModule.Sample delayedSample;

        private Channel(float pan) {
            leftGain = (1 - pan) / 2f * 1.6f;
            rightGain = (1 + pan) / 2f * 1.6f;
        }

        private void reset() {
            sample = null;
            active = false;
            position = 0;
            step = 0;
            period = 0;
            noteIndex = 0;
            fineTune = 0;
            volume = 0;
            outputVolume = 0;
            effect = 0;
            param = 0;
            targetPeriod = 0;
            portaSpeed = 0;
            vibratoSpeed = 0;
            vibratoDepth = 0;
            vibratoPos = 0;
            vibratoWave = 0;
            tremoloSpeed = 0;
            tremoloDepth = 0;
            tremoloPos = 0;
            tremoloWave = 0;
            sampleOffset = 0;
            loopRow = 0;
            loopCount = 0;
            periodDelta = 0;
            volumeDelta = 0;
            delayedPeriod = 0;
            delayedSample = null;
        }

        /** Linear interpolation between neighbouring sample points. */
        private float sampleValue() {
            byte[] data = sample.data();
            int index = (int) position;
            if (index < 0 || index >= data.length) {
                // A sample swapped in mid-note can be shorter than where the old one was.
                active = false;
                return 0;
            }
            float fraction = (float) (position - index);
            float a = data[index];
            int nextIndex = index + 1;
            if (nextIndex >= data.length || (sample.looped()
                    && nextIndex >= sample.loopStart() + sample.loopLength())) {
                nextIndex = sample.looped() ? sample.loopStart() : index;
            }
            float b = data[nextIndex];
            return a + (b - a) * fraction;
        }

        /** Moves the play position on by {@code frames}, honouring the sample loop. */
        private void advance(int frames) {
            if (!active) return;
            position += step * frames;
            if (sample.looped()) {
                int loopEnd = sample.loopStart() + sample.loopLength();
                if (position >= loopEnd) {
                    position = sample.loopStart() + (position - sample.loopStart()) % sample.loopLength();
                }
            } else if (position >= sample.length()) {
                active = false;
            }
        }

        private void trigger(ModRenderer song, ModModule.Sample with, int rawPeriod) {
            if (with != null) {
                sample = with;
            }
            noteIndex = noteIndex(rawPeriod);
            period = periodFor(noteIndex, fineTune);
            position = 0;
            active = sample != null && sample.length() > 0;
            if (vibratoWave < 4) vibratoPos = 0;
            if (tremoloWave < 4) tremoloPos = 0;
        }

        private void enterRow(ModRenderer song, ModModule.Cell cell) {
            effect = cell.effect();
            param = cell.param();
            periodDelta = 0;
            volumeDelta = 0;
            ModModule.Sample newSample = song.module.sample(cell.sample());
            if (newSample != null) {
                volume = newSample.volume();
                fineTune = newSample.fineTune();
            }
            int x = param >> 4;
            int y = param & 0x0F;
            boolean tonePorta = effect == 3 || effect == 5;
            boolean noteDelay = effect == 0xE && x == 0xD && y > 0;

            if (cell.period() > 0) {
                if (tonePorta) {
                    targetPeriod = periodFor(noteIndex(cell.period()), fineTune);
                    if (newSample != null) sample = newSample;
                } else if (noteDelay) {
                    delayedPeriod = cell.period();
                    delayedSample = newSample;
                } else {
                    trigger(song, newSample, cell.period());
                }
            } else if (newSample != null && !noteDelay) {
                // A sample number without a note changes the sample for the next loop.
                sample = newSample;
            }

            switch (effect) {
                case 0x3 -> { if (param != 0) portaSpeed = param; }
                case 0x4 -> {
                    if (x != 0) vibratoSpeed = x;
                    if (y != 0) vibratoDepth = y;
                }
                case 0x7 -> {
                    if (x != 0) tremoloSpeed = x;
                    if (y != 0) tremoloDepth = y;
                }
                case 0x8 -> { /* Panning is left to the fixed Amiga layout. */ }
                case 0x9 -> {
                    if (param != 0) sampleOffset = param * 256;
                    if (cell.period() > 0 && !tonePorta && active) {
                        position = sampleOffset;
                        if (position >= sample.length()) {
                            if (sample.looped()) position = sample.loopStart();
                            else active = false;
                        }
                    }
                }
                case 0xB -> {
                    song.jumpOrder = param;
                    song.breakRow = song.breakRow >= 0 ? song.breakRow : 0;
                }
                case 0xC -> volume = Math.min(64, param);
                case 0xD -> {
                    song.breakRow = x * 10 + y;
                    if (song.jumpOrder < 0) song.jumpOrder = song.order + 1;
                }
                case 0xE -> extendedTickZero(song, x, y);
                case 0xF -> {
                    if (param > 0 && param < 32) song.speed = param;
                    else if (param >= 32) song.tempo = param;
                }
                default -> { }
            }
            outputVolume = volume;
        }

        private void extendedTickZero(ModRenderer song, int x, int y) {
            switch (x) {
                case 0x1 -> period = Math.max(song.minPeriod, period - y);
                case 0x2 -> period = Math.min(song.maxPeriod, period + y);
                case 0x4 -> vibratoWave = y;
                case 0x5 -> fineTune = y > 7 ? y - 16 : y;
                case 0x6 -> {
                    if (y == 0) {
                        loopRow = song.row;
                    } else if (loopCount == 0) {
                        loopCount = y;
                        song.loopJumpRow = loopRow;
                        song.patternLoopActive = true;
                    } else if (--loopCount > 0) {
                        song.loopJumpRow = loopRow;
                    } else {
                        song.patternLoopActive = false;
                    }
                }
                case 0x7 -> tremoloWave = y;
                case 0xA -> volume = Math.min(64, volume + y);
                case 0xB -> volume = Math.max(0, volume - y);
                case 0xC -> { if (y == 0) volume = 0; }
                case 0xE -> song.patternDelay = Math.max(song.patternDelay, y);
                default -> { }
            }
        }

        /** Runs the per-tick part of the row's effect, on ticks other than zero. */
        private void updateTick(ModRenderer song, int localTick) {
            int x = param >> 4;
            int y = param & 0x0F;
            periodDelta = 0;
            volumeDelta = 0;
            switch (effect) {
                case 0x0 -> {
                    if (param != 0) {
                        int semitones = switch (localTick % 3) {
                            case 1 -> x;
                            case 2 -> y;
                            default -> 0;
                        };
                        periodDelta = periodFor(noteIndex + semitones, fineTune) - period;
                    }
                }
                case 0x1 -> period = Math.max(song.minPeriod, period - param);
                case 0x2 -> period = Math.min(song.maxPeriod, period + param);
                case 0x3 -> tonePortamento();
                case 0x4 -> vibrato();
                case 0x5 -> { tonePortamento(); volumeSlide(x, y); }
                case 0x6 -> { vibrato(); volumeSlide(x, y); }
                case 0x7 -> tremolo();
                case 0xA -> volumeSlide(x, y);
                case 0xE -> {
                    if (x == 0x9 && y > 0 && localTick % y == 0 && sample != null) {
                        position = 0;
                        active = sample.length() > 0;
                    } else if (x == 0xC && localTick == y) {
                        volume = 0;
                    } else if (x == 0xD && localTick == y && delayedPeriod > 0) {
                        if (delayedSample != null) {
                            volume = delayedSample.volume();
                            fineTune = delayedSample.fineTune();
                        }
                        trigger(song, delayedSample, delayedPeriod);
                        delayedPeriod = 0;
                    }
                }
                default -> { }
            }
            outputVolume = Math.max(0, Math.min(64, volume + volumeDelta));
        }

        /** A pattern-delay repeat's first tick: slides do not run, timing effects do. */
        private void delayRepeat(ModRenderer song) {
            periodDelta = 0;
            volumeDelta = 0;
            outputVolume = volume;
        }

        private void tonePortamento() {
            if (targetPeriod == 0 || portaSpeed == 0) return;
            if (period < targetPeriod) {
                period = Math.min(targetPeriod, period + portaSpeed);
            } else if (period > targetPeriod) {
                period = Math.max(targetPeriod, period - portaSpeed);
            }
        }

        private void vibrato() {
            periodDelta = waveform(vibratoWave, vibratoPos) * vibratoDepth / 128;
            vibratoPos = (vibratoPos + vibratoSpeed) & 63;
        }

        private void tremolo() {
            volumeDelta = waveform(tremoloWave, tremoloPos) * tremoloDepth / 64;
            tremoloPos = (tremoloPos + tremoloSpeed) & 63;
        }

        private void volumeSlide(int up, int down) {
            if (up > 0) volume = Math.min(64, volume + up);
            else volume = Math.max(0, volume - down);
        }

        private void updateFrequency(ModRenderer song) {
            int playing = period + periodDelta;
            if (playing <= 0) {
                step = 0;
                return;
            }
            step = AMIGA_CLOCK / (playing * 2.0) / song.sampleRate;
        }
    }
}
