package com.mtm2.jpod.ui.audio;

/** Common transport used by the WAV and tracker-module players. */
public interface AudioPlayer extends AutoCloseable {

    enum State { STOPPED, PLAYING, PAUSED }

    /** False when the data cannot be played; {@link #errorMessage()} says why. */
    boolean isAvailable();

    String errorMessage();

    boolean canSeek();

    /** Length in microseconds, or 0 when unknown. */
    long durationMicros();

    /** Current position in microseconds. */
    long positionMicros();

    State state();

    void play();

    void pause();

    void resume();

    /** Stops and rewinds to the start. */
    void stop();

    void seek(long micros);

    /** Releases the audio device. Never throws. */
    @Override
    void close();
}
