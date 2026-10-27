package com.mtm2.jpod.io.audio;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A parsed ProTracker-family tracker module.
 *
 * <p>Terminal Reality's {@code .mod} music is 6-channel ProTracker ({@code 6CHN}). The
 * parser also takes the other common signatures: {@code M.K.}, {@code M!K!},
 * {@code FLT4}, {@code 4CHN}, {@code 8CHN}, {@code CD81}, {@code OKTA}, {@code FLT8},
 * {@code xCHN}, {@code xxCH} and {@code xxCN}, plus the original 15-sample
 * Soundtracker layout, which has no signature at all.
 *
 * <p>Layout, all integers big-endian:
 * <pre>
 *     0   20   song title
 *    20   30 x 31 sample headers: name[22], length/2, finetune, volume,
 *                 loop start/2, loop length/2
 *   950    1   song length (orders used)
 *   951    1   restart position (ignored, as ProTracker does)
 *   952  128   order table
 *  1080    4   signature, which also gives the channel count
 *  1084  ...   patterns: 64 rows x channels x 4 bytes each
 *   ...  ...   sample data, signed 8-bit, in header order
 * </pre>
 */
public final class ModModule {

    /** Rows in every pattern. */
    public static final int ROWS = 64;

    private final String title;
    private final int channels;
    private final Sample[] samples;
    private final int songLength;
    private final int[] orders;
    private final byte[] patterns;
    private final int patternCount;

    /** One instrument sample. Lengths and loop points are in bytes. */
    public record Sample(String name, byte[] data, int fineTune, int volume,
                         int loopStart, int loopLength) {
        public int length() {
            return data.length;
        }

        /** ProTracker treats a loop of one word as no loop at all. */
        public boolean looped() {
            return loopLength > 2;
        }
    }

    private ModModule(String title, int channels, Sample[] samples, int songLength,
            int[] orders, byte[] patterns, int patternCount) {
        this.title = title;
        this.channels = channels;
        this.samples = samples;
        this.songLength = songLength;
        this.orders = orders;
        this.patterns = patterns;
        this.patternCount = patternCount;
    }

    public String title() { return title; }

    public int channels() { return channels; }

    /** Samples in header order; the pattern data numbers them from 1. */
    public Sample sample(int number) {
        return number >= 1 && number <= samples.length ? samples[number - 1] : null;
    }

    public int sampleCount() { return samples.length; }

    public int songLength() { return songLength; }

    /** Pattern played at an order position. */
    public int order(int position) { return orders[position]; }

    public int patternCount() { return patternCount; }

    /** The four raw bytes of one pattern cell, decoded. */
    public Cell cell(int pattern, int row, int channel) {
        int at = ((pattern * ROWS + row) * channels + channel) * 4;
        int b0 = patterns[at] & 0xFF;
        int b1 = patterns[at + 1] & 0xFF;
        int b2 = patterns[at + 2] & 0xFF;
        int b3 = patterns[at + 3] & 0xFF;
        return new Cell((b0 & 0xF0) | (b2 >> 4), ((b0 & 0x0F) << 8) | b1, b2 & 0x0F, b3);
    }

    /** One pattern cell: sample number (0 = none), Amiga period (0 = none), effect, parameter. */
    public record Cell(int sample, int period, int effect, int param) {}

    /** True when these bytes carry a signature this parser recognises. */
    public static boolean isModule(byte[] data) {
        return data != null && data.length >= 1084 && channelsFor(data, 1080) > 0;
    }

    /**
     * Parses a module.
     *
     * @throws IllegalArgumentException when the data is not a module this parser reads
     */
    public static ModModule parse(byte[] data) {
        if (data == null || data.length < 600) {
            throw new IllegalArgumentException("Too short to be a MOD file");
        }
        int sampleSlots;
        int channels;
        int headerEnd;
        if (data.length >= 1084 && channelsFor(data, 1080) > 0) {
            sampleSlots = 31;
            channels = channelsFor(data, 1080);
            headerEnd = 1084;
        } else {
            // The 15-sample Soundtracker layout has no signature, so it is accepted only
            // when its song length and order table are plausible.
            sampleSlots = 15;
            channels = 4;
            headerEnd = 600;
            int length = data[470] & 0xFF;
            if (length < 1 || length > 128) {
                throw new IllegalArgumentException("Not a MOD file: unrecognised signature");
            }
            for (int i = 0; i < 128; i++) {
                if ((data[472 + i] & 0xFF) > 63) {
                    throw new IllegalArgumentException("Not a MOD file: unrecognised signature");
                }
            }
        }

        String title = ascii(data, 0, 20);
        int songAt = 20 + sampleSlots * 30;
        int songLength = Math.max(1, Math.min(128, data[songAt] & 0xFF));
        int[] orders = new int[128];
        int highest = 0;
        for (int i = 0; i < 128; i++) {
            orders[i] = data[songAt + 2 + i] & 0x7F;
            highest = Math.max(highest, orders[i]);
        }
        int patternCount = highest + 1;
        int patternBytes = patternCount * ROWS * channels * 4;
        byte[] patterns = new byte[patternBytes];
        int available = Math.max(0, Math.min(patternBytes, data.length - headerEnd));
        System.arraycopy(data, headerEnd, patterns, 0, available);

        Sample[] samples = new Sample[sampleSlots];
        int cursor = headerEnd + patternBytes;
        for (int i = 0; i < sampleSlots; i++) {
            int at = 20 + i * 30;
            String name = ascii(data, at, 22);
            int length = readWord(data, at + 22) * 2;
            int fineTune = data[at + 24] & 0x0F;
            if (fineTune > 7) fineTune -= 16;
            int volume = Math.min(64, data[at + 25] & 0xFF);
            int loopStart = readWord(data, at + 26) * 2;
            int loopLength = readWord(data, at + 28) * 2;

            // A truncated file keeps whatever sample data it still has.
            int present = Math.max(0, Math.min(length, data.length - cursor));
            byte[] sampleData = present == 0 ? new byte[0] : Arrays.copyOfRange(data, cursor, cursor + present);
            cursor += length;
            if (loopStart >= sampleData.length) {
                loopStart = 0;
                loopLength = 0;
            } else if (loopStart + loopLength > sampleData.length) {
                loopLength = sampleData.length - loopStart;
            }
            samples[i] = new Sample(name, sampleData, fineTune, volume, loopStart, loopLength);
        }
        return new ModModule(title, channels, samples, songLength, orders, patterns, patternCount);
    }

    /** The channel count a signature declares, or 0 when it is not one. */
    private static int channelsFor(byte[] data, int at) {
        String tag = new String(data, at, 4, StandardCharsets.ISO_8859_1);
        switch (tag) {
            case "M.K.", "M!K!", "M&K!", "N.T.", "FLT4", "4CHN": return 4;
            case "6CHN": return 6;
            case "8CHN", "CD81", "OKTA", "OCTA", "FLT8": return 8;
            default: break;
        }
        if (tag.endsWith("CHN") && Character.isDigit(tag.charAt(0))) {
            return tag.charAt(0) - '0';
        }
        if ((tag.endsWith("CH") || tag.endsWith("CN"))
                && Character.isDigit(tag.charAt(0)) && Character.isDigit(tag.charAt(1))) {
            int count = Integer.parseInt(tag.substring(0, 2));
            return count >= 1 && count <= 32 ? count : 0;
        }
        return 0;
    }

    private static int readWord(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    private static String ascii(byte[] data, int at, int length) {
        int end = at;
        while (end < at + length && data[end] != 0) end++;
        StringBuilder text = new StringBuilder(end - at);
        for (int i = at; i < end; i++) {
            int c = data[i] & 0xFF;
            text.append(c >= 0x20 && c < 0x7F ? (char) c : ' ');
        }
        return text.toString().trim();
    }
}
