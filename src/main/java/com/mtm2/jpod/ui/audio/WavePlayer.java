package com.mtm2.jpod.ui.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.io.ByteArrayInputStream;

/**
 * Plays a WAV entry with play, pause, resume, stop and seeking.
 *
 * <p>A {@link Clip} holds the decoded audio, so position and seeking are exact. Data
 * that is not already PCM is converted when the platform has a codec for it.
 */
public final class WavePlayer implements AudioPlayer {

    private final Clip clip;
    private final String error;
    private volatile State state = State.STOPPED;
    /** Set while a pause or stop is ours, so its STOP event is not read as the end. */
    private volatile boolean stopRequested;

    public WavePlayer(byte[] data) {
        Clip opened = null;
        String failure = null;
        try {
            AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(data));
            AudioFormat format = stream.getFormat();
            if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                    && format.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED) {
                AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                        format.getSampleRate(), 16, format.getChannels(),
                        format.getChannels() * 2, format.getSampleRate(), false);
                stream = AudioSystem.getAudioInputStream(pcm, stream);
            }
            opened = AudioSystem.getClip();
            opened.open(stream);
            opened.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP && !stopRequested) {
                    // Ran off the end on its own.
                    state = State.STOPPED;
                }
            });
        } catch (Exception ex) {
            failure = "Cannot play this audio file: " + ex.getMessage();
            if (opened != null) opened.close();
            opened = null;
        }
        clip = opened;
        error = failure;
    }

    @Override public boolean isAvailable() { return clip != null; }

    @Override public String errorMessage() { return error; }

    @Override public boolean canSeek() { return clip != null; }

    @Override public long durationMicros() { return clip == null ? 0 : clip.getMicrosecondLength(); }

    @Override public long positionMicros() { return clip == null ? 0 : clip.getMicrosecondPosition(); }

    @Override public State state() { return state; }

    @Override
    public void play() {
        if (clip == null) return;
        if (clip.getFramePosition() >= clip.getFrameLength()) {
            clip.setFramePosition(0);
        }
        stopRequested = false;
        state = State.PLAYING;
        clip.start();
    }

    @Override
    public void pause() {
        if (clip == null || state != State.PLAYING) return;
        stopRequested = true;
        clip.stop();
        state = State.PAUSED;
    }

    @Override
    public void resume() {
        if (clip == null) return;
        stopRequested = false;
        state = State.PLAYING;
        clip.start();
    }

    @Override
    public void stop() {
        if (clip == null) return;
        stopRequested = true;
        clip.stop();
        clip.setFramePosition(0);
        state = State.STOPPED;
    }

    @Override
    public void seek(long micros) {
        if (clip == null) return;
        long clamped = Math.max(0, Math.min(clip.getMicrosecondLength(), micros));
        clip.setMicrosecondPosition(clamped);
    }

    @Override
    public void close() {
        if (clip != null) {
            stopRequested = true;
            clip.stop();
            clip.close();
        }
    }
}
