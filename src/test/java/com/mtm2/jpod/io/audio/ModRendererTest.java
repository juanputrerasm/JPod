package com.mtm2.jpod.io.audio;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ModRendererTest {

    private static final int RATE = 44100;
    /** Frames in one row at the default speed 6 and tempo 125. */
    private static final int ROW_FRAMES = 6 * (int) Math.round(RATE * 2.5 / 125);

    @Test
    void parsesASixChannelModuleAndItsSamples() {
        ModModule module = ModModule.parse(module("6CHN", 6, 1, new int[] {0}, cells -> { }));
        assertEquals(6, module.channels());
        assertEquals("JPOD TEST", module.title());
        assertEquals(1, module.songLength());
        assertEquals(64, module.sample(1).volume());
        assertEquals(1000, module.sample(1).length());
        assertTrue(module.sample(1).looped());
        assertTrue(ModModule.isModule(module("M.K.", 4, 1, new int[] {0}, cells -> { })));
    }

    @Test
    void aSongRunsToTheEndOfItsOrderList() {
        ModRenderer renderer = new ModRenderer(ModModule.parse(module("M.K.", 4, 2, new int[] {0, 1}, cells -> { })), RATE);
        assertEquals(2L * 64 * ROW_FRAMES, renderer.durationFrames());
    }

    @Test
    void aBackwardsJumpEndsTheSongInsteadOfLoopingForever() {
        // Row 3 of the only pattern jumps back to order 0: the song is four rows long.
        byte[] data = module("M.K.", 4, 1, new int[] {0}, cells -> cells.set(0, 3, 0, 0, 0, 0xB, 0x00));
        ModRenderer renderer = new ModRenderer(ModModule.parse(data), RATE);
        assertEquals(4L * ROW_FRAMES, renderer.durationFrames());
    }

    @Test
    void aPatternLoopRepeatsRowsWithoutBeingMistakenForTheEnd() {
        // Row 0 sets the loop point; row 1 loops back twice, so rows 0-1 play three times,
        // then a pattern break on row 2 ends the one-order song.
        byte[] data = module("M.K.", 4, 1, new int[] {0}, cells -> {
            cells.set(0, 0, 0, 0, 0, 0xE, 0x60);
            cells.set(0, 1, 0, 0, 0, 0xE, 0x62);
            cells.set(0, 2, 1, 0, 0, 0xD, 0x00);
        });
        ModRenderer renderer = new ModRenderer(ModModule.parse(data), RATE);
        assertEquals((3L * 2 + 1) * ROW_FRAMES, renderer.durationFrames());
    }

    @Test
    void speedAndTempoChangeTheLengthOfARow() {
        byte[] data = module("M.K.", 4, 1, new int[] {0}, cells -> {
            cells.set(0, 0, 0, 0, 0, 0xF, 3);
            cells.set(0, 1, 0, 0, 0, 0xB, 0);
        });
        ModRenderer renderer = new ModRenderer(ModModule.parse(data), RATE);
        assertEquals(2L * 3 * Math.round(RATE * 2.5 / 125), renderer.durationFrames());
    }

    @Test
    void aTriggeredNoteProducesSoundAndSeekingLandsWhereAsked() {
        byte[] data = module("M.K.", 4, 1, new int[] {0}, cells -> cells.set(0, 0, 0, 1, 428, 0, 0));
        ModRenderer renderer = new ModRenderer(ModModule.parse(data), RATE);
        long length = renderer.durationFrames();
        short[] buffer = new short[4096 * 2];
        int frames = renderer.render(buffer, 4096);
        assertEquals(4096, frames);
        int peak = 0;
        for (short value : buffer) peak = Math.max(peak, Math.abs(value));
        assertTrue(peak > 1000, "a full-volume square wave should be audible, peak " + peak);

        renderer.seek(length / 2);
        assertEquals(length / 2, renderer.position());
        long rest = 0;
        int read;
        while ((read = renderer.render(buffer, 4096)) > 0) rest += read;
        assertEquals(length - length / 2, rest);
        assertTrue(renderer.ended());
    }

    @Test
    void rejectsWhatIsNotAModule() {
        assertThrows(IllegalArgumentException.class, () -> ModModule.parse(new byte[100]));
        byte[] junk = new byte[2000];
        junk[470] = (byte) 200;
        assertThrows(IllegalArgumentException.class, () -> ModModule.parse(junk));
        assertFalse(ModModule.isModule(junk));
    }

    // -------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------

    private interface CellWriter {
        void set(int pattern, int row, int channel, int sample, int period, int effect, int param);
    }

    private interface Patterns {
        void fill(CellWriter cells);
    }

    /** A module with one looped 1000-byte square-wave sample and empty patterns. */
    private static byte[] module(String tag, int channels, int patterns, int[] orders, Patterns fill) {
        int patternBytes = patterns * 64 * channels * 4;
        int sampleLength = 1000;
        byte[] data = new byte[1084 + patternBytes + sampleLength];
        byte[] title = "JPOD TEST".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0, title.length);
        // Sample 1: length, finetune 0, volume 64, loop over all of it.
        int at = 20;
        data[at + 22] = (byte) ((sampleLength / 2) >> 8);
        data[at + 23] = (byte) (sampleLength / 2);
        data[at + 25] = 64;
        data[at + 28] = (byte) ((sampleLength / 2) >> 8);
        data[at + 29] = (byte) (sampleLength / 2);
        data[950] = (byte) orders.length;
        data[951] = 127;
        for (int i = 0; i < orders.length; i++) data[952 + i] = (byte) orders[i];
        byte[] signature = tag.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(signature, 0, data, 1080, 4);
        fill.fill((pattern, row, channel, sample, period, effect, param) -> {
            int cell = 1084 + ((pattern * 64 + row) * channels + channel) * 4;
            data[cell] = (byte) ((sample & 0xF0) | ((period >> 8) & 0x0F));
            data[cell + 1] = (byte) period;
            data[cell + 2] = (byte) (((sample & 0x0F) << 4) | effect);
            data[cell + 3] = (byte) param;
        });
        int sampleAt = 1084 + patternBytes;
        for (int i = 0; i < sampleLength; i++) data[sampleAt + i] = (byte) ((i / 20) % 2 == 0 ? 100 : -100);
        return data;
    }
}
