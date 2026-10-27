package com.mtm2.jpod.ui.audio;

import com.mtm2.jpod.io.audio.ModModule;
import com.mtm2.jpod.io.audio.ModRenderer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays a tracker module through the built-in {@link ModRenderer}.
 *
 * <p>A background thread renders a few thousand frames at a time and hands them to a
 * {@link SourceDataLine}, writing only what the line can take without blocking, so
 * pause, stop and seek never wait on a full buffer. The song's length is found up
 * front by playing it through without mixing, which is what makes the seek bar work.
 */
public final class ModulePlayer implements AudioPlayer {

    private static final int SAMPLE_RATE = 44100;
    private static final int FRAME_BYTES = 4;
    private static final int CHUNK_FRAMES = 2048;

    private final Object lock = new Object();
    private final ModRenderer renderer;
    private final SourceDataLine line;
    private final long durationFrames;
    private final String error;
    private Thread worker;
    private volatile State state = State.STOPPED;
    private volatile boolean closed;
    /** Frames handed to the line, counted from the start of the song. */
    private volatile long writtenFrames;

    public ModulePlayer(byte[] data) {
        ModRenderer parsed = null;
        SourceDataLine opened = null;
        long length = 0;
        String failure = null;
        try {
            parsed = new ModRenderer(ModModule.parse(data), SAMPLE_RATE);
            length = parsed.durationFrames();
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
            opened = AudioSystem.getSourceDataLine(format);
            opened.open(format, SAMPLE_RATE * FRAME_BYTES / 4);
        } catch (IllegalArgumentException ex) {
            failure = "Cannot play this module: " + ex.getMessage();
        } catch (Exception ex) {
            failure = "No audio output is available: " + ex.getMessage();
        }
        if (failure != null && opened != null) {
            opened.close();
            opened = null;
        }
        renderer = failure == null ? parsed : null;
        line = opened;
        durationFrames = length;
        error = failure;
    }

    @Override public boolean isAvailable() { return line != null; }

    @Override public String errorMessage() { return error; }

    @Override public boolean canSeek() { return line != null && durationFrames > 0; }

    @Override public long durationMicros() { return framesToMicros(durationFrames); }

    @Override
    public long positionMicros() {
        if (line == null) return 0;
        long queued = (line.getBufferSize() - line.available()) / FRAME_BYTES;
        return framesToMicros(Math.max(0, Math.min(durationFrames, writtenFrames - queued)));
    }

    @Override public State state() { return state; }

    @Override
    public void play() {
        if (line == null) return;
        synchronized (lock) {
            if (renderer.ended() || renderer.position() >= durationFrames) {
                renderer.reset();
                line.flush();
                writtenFrames = 0;
            }
            state = State.PLAYING;
            line.start();
            if (worker == null || !worker.isAlive()) {
                worker = new Thread(this::pump, "JPod MOD playback");
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    @Override
    public void pause() {
        if (line == null || state != State.PLAYING) return;
        state = State.PAUSED;
        line.stop();
    }

    @Override
    public void resume() {
        if (line == null || state != State.PAUSED) return;
        state = State.PLAYING;
        line.start();
    }

    @Override
    public void stop() {
        if (line == null) return;
        synchronized (lock) {
            state = State.STOPPED;
            line.stop();
            line.flush();
            renderer.reset();
            writtenFrames = 0;
        }
    }

    @Override
    public void seek(long micros) {
        if (line == null) return;
        long frame = Math.max(0, Math.min(durationFrames, micros * SAMPLE_RATE / 1_000_000L));
        synchronized (lock) {
            renderer.seek(frame);
            line.flush();
            writtenFrames = renderer.position();
        }
    }

    /** Feeds the line while playing; exits when the song ends or the player closes. */
    private void pump() {
        short[] samples = new short[CHUNK_FRAMES * 2];
        byte[] bytes = new byte[CHUNK_FRAMES * FRAME_BYTES];
        while (!closed && state != State.STOPPED) {
            int wrote = 0;
            synchronized (lock) {
                if (state == State.PLAYING) {
                    int frames = Math.min(CHUNK_FRAMES, line.available() / FRAME_BYTES);
                    if (frames > 0) {
                        int rendered = renderer.render(samples, frames);
                        for (int i = 0; i < rendered * 2; i++) {
                            bytes[i * 2] = (byte) samples[i];
                            bytes[i * 2 + 1] = (byte) (samples[i] >> 8);
                        }
                        line.write(bytes, 0, rendered * FRAME_BYTES);
                        writtenFrames += rendered;
                        wrote = rendered;
                        if (rendered < frames && renderer.ended()) {
                            break;
                        }
                    }
                }
            }
            if (wrote == 0) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (!closed && renderer.ended()) {
            line.drain();
            state = State.STOPPED;
        }
    }

    private static long framesToMicros(long frames) {
        return frames * 1_000_000L / SAMPLE_RATE;
    }

    @Override
    public void close() {
        closed = true;
        state = State.STOPPED;
        if (line != null) {
            line.stop();
            line.flush();
        }
        Thread running = worker;
        if (running != null) {
            try {
                running.join(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        if (line != null) {
            line.close();
        }
    }
}
