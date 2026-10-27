package com.mtm2.jpod.io.image;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal TIFF reader for the palette-indexed art 4x4 Evolution 2 keeps its model and
 * vegetation textures in.
 *
 * <p>Only the forms the game actually ships are supported, verified across every .TIF
 * entry of the BAJBEACH and PEAK stock tracks:
 * <ul>
 *   <li>little-endian ("II", magic 42), uncompressed (Compression 1)</li>
 *   <li>PhotometricInterpretation 3 (palette colour) with a ColorMap</li>
 *   <li>PlanarConfiguration 1 (chunky), or absent, which means 1</li>
 *   <li>SamplesPerPixel 1, index only, fully opaque</li>
 *   <li>SamplesPerPixel 2, index plus a second 8-bit sample used as opacity</li>
 * </ul>
 *
 * <p>Anything else is refused with a reason rather than decoded into plausible-looking
 * garbage: a viewer that silently shows the wrong texture is worse than one that says
 * it cannot read the file.
 *
 * <p>ColorMap stores three consecutive runs, all reds, then all greens, then all
 * blues, of 16-bit values, not interleaved RGB triples. Both that and the 16-bit
 * scaling are easy to get subtly wrong and produce a washed-out or channel-swapped
 * image.
 */
public final class TiffImageDecoder {

    private static final int LITTLE_ENDIAN_ORDER = 0x4949;
    private static final int BIG_ENDIAN_ORDER = 0x4D4D;
    private static final int TIFF_MAGIC = 42;

    private static final int TAG_IMAGE_WIDTH = 256;
    private static final int TAG_IMAGE_LENGTH = 257;
    private static final int TAG_BITS_PER_SAMPLE = 258;
    private static final int TAG_COMPRESSION = 259;
    private static final int TAG_PHOTOMETRIC = 262;
    private static final int TAG_STRIP_OFFSETS = 273;
    private static final int TAG_SAMPLES_PER_PIXEL = 277;
    private static final int TAG_ROWS_PER_STRIP = 278;
    private static final int TAG_PLANAR_CONFIGURATION = 284;
    private static final int TAG_COLOR_MAP = 320;

    private static final int COMPRESSION_NONE = 1;
    private static final int PHOTOMETRIC_PALETTE = 3;
    private static final int PLANAR_CHUNKY = 1;

    private TiffImageDecoder() {}

    /** True when these bytes begin a TIFF header this decoder should be given. */
    public static boolean isTiff(byte[] data) {
        if (data == null || data.length < 8) return false;
        int order = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        if (order != LITTLE_ENDIAN_ORDER && order != BIG_ENDIAN_ORDER) return false;
        return readUInt16(data, 2, order == LITTLE_ENDIAN_ORDER) == TIFF_MAGIC;
    }

    /**
     * Decodes one palette TIFF, with the second sample as alpha when present.
     *
     * @throws IllegalArgumentException when the data is not a TIFF this supports
     */
    public static BufferedImage decode(byte[] data, String name) {
        if (!isTiff(data)) throw new IllegalArgumentException(name + ": not a TIFF");
        boolean little = (((data[0] & 0xFF) << 8) | (data[1] & 0xFF)) == LITTLE_ENDIAN_ORDER;
        Map<Integer, long[]> tags = readIfd(data, (int) readUInt32(data, 4, little), little, name);

        int width = (int) scalar(tags, TAG_IMAGE_WIDTH, 0);
        int height = (int) scalar(tags, TAG_IMAGE_LENGTH, 0);
        int compression = (int) scalar(tags, TAG_COMPRESSION, COMPRESSION_NONE);
        int photometric = (int) scalar(tags, TAG_PHOTOMETRIC, -1);
        int samplesPerPixel = (int) scalar(tags, TAG_SAMPLES_PER_PIXEL, 1);
        int planar = (int) scalar(tags, TAG_PLANAR_CONFIGURATION, PLANAR_CHUNKY);

        if (width <= 0 || height <= 0) throw new IllegalArgumentException(name + ": TIFF has no dimensions");
        if (compression != COMPRESSION_NONE) {
            throw new IllegalArgumentException(name + ": unsupported TIFF compression " + compression);
        }
        if (photometric != PHOTOMETRIC_PALETTE) {
            throw new IllegalArgumentException(name + ": unsupported TIFF photometric " + photometric
                    + " (only palette colour is handled)");
        }
        if (planar != PLANAR_CHUNKY) {
            throw new IllegalArgumentException(name + ": unsupported TIFF planar configuration " + planar);
        }
        if (samplesPerPixel != 1 && samplesPerPixel != 2) {
            throw new IllegalArgumentException(name + ": unsupported TIFF samples/pixel " + samplesPerPixel);
        }
        long[] bits = tags.get(TAG_BITS_PER_SAMPLE);
        if (bits != null) {
            for (long value : bits) {
                if (value != 8) {
                    throw new IllegalArgumentException(name + ": unsupported TIFF bit depth " + value);
                }
            }
        }
        long[] colorMap = tags.get(TAG_COLOR_MAP);
        if (colorMap == null || colorMap.length < 768) {
            throw new IllegalArgumentException(name + ": TIFF palette image has no usable ColorMap");
        }

        // ColorMap entries are nominally 0..65535, but some writers store 0..255. If
        // nothing in the map exceeds 255 it is read as already 8-bit rather than
        // crushed to near-black.
        int entries = colorMap.length / 3;
        boolean sixteenBit = false;
        for (long value : colorMap) {
            if (value > 255) { sixteenBit = true; break; }
        }
        int[] palette = new int[entries];
        for (int i = 0; i < entries; i++) {
            int r = (int) (sixteenBit ? colorMap[i] >> 8 : colorMap[i]) & 0xFF;
            int g = (int) (sixteenBit ? colorMap[entries + i] >> 8 : colorMap[entries + i]) & 0xFF;
            int b = (int) (sixteenBit ? colorMap[entries * 2 + i] >> 8 : colorMap[entries * 2 + i]) & 0xFF;
            palette[i] = (r << 16) | (g << 8) | b;
        }

        long[] stripOffsets = tags.get(TAG_STRIP_OFFSETS);
        if (stripOffsets == null) throw new IllegalArgumentException(name + ": TIFF has no strip offsets");
        int rowsPerStrip = (int) scalar(tags, TAG_ROWS_PER_STRIP, height);
        if (rowsPerStrip <= 0) rowsPerStrip = height;

        int[] pixels = new int[width * height];
        int rowBytes = width * samplesPerPixel;
        int row = 0;
        for (int strip = 0; strip < stripOffsets.length && row < height; strip++) {
            long offset = stripOffsets[strip];
            int rowsHere = Math.min(rowsPerStrip, height - row);
            for (int r = 0; r < rowsHere; r++, row++) {
                long src = offset + (long) r * rowBytes;
                if (src < 0 || src + rowBytes > data.length) {
                    throw new IllegalArgumentException(name + ": truncated TIFF strip " + strip);
                }
                for (int x = 0; x < width; x++) {
                    int at = (int) (src + (long) x * samplesPerPixel);
                    int index = data[at] & 0xFF;
                    int alpha = samplesPerPixel == 2 ? data[at + 1] & 0xFF : 255;
                    pixels[row * width + x] = (alpha << 24) | palette[Math.min(index, entries - 1)];
                }
            }
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    /** True when this TIFF carries the second sample Evo uses as an opacity plane. */
    public static boolean hasAlphaSample(byte[] data, String name) {
        if (!isTiff(data)) return false;
        boolean little = (((data[0] & 0xFF) << 8) | (data[1] & 0xFF)) == LITTLE_ENDIAN_ORDER;
        try {
            Map<Integer, long[]> tags = readIfd(data, (int) readUInt32(data, 4, little), little, name);
            return scalar(tags, TAG_SAMPLES_PER_PIXEL, 1) == 2;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static Map<Integer, long[]> readIfd(byte[] data, int offset, boolean little, String name) {
        if (offset < 0 || offset + 2 > data.length) {
            throw new IllegalArgumentException(name + ": TIFF IFD lies outside the file");
        }
        int count = readUInt16(data, offset, little);
        Map<Integer, long[]> tags = new HashMap<>();
        for (int i = 0; i < count; i++) {
            int record = offset + 2 + i * 12;
            if (record + 12 > data.length) break;
            int tag = readUInt16(data, record, little);
            int type = readUInt16(data, record + 2, little);
            long length = readUInt32(data, record + 4, little);
            int size = typeSize(type);
            if (size == 0) continue;

            long totalBytes = size * length;
            long valueOffset = totalBytes <= 4 ? record + 8 : readUInt32(data, record + 8, little);
            if (valueOffset < 0 || valueOffset + totalBytes > data.length) continue;

            // A ColorMap is 3 * 2^bits entries; anything past a full 16-bit palette is
            // left unread rather than walking some unrelated huge tag.
            int limit = (int) Math.min(length, 4096);
            List<Long> values = new ArrayList<>(limit);
            for (int v = 0; v < limit; v++) {
                int at = (int) (valueOffset + (long) v * size);
                values.add(switch (type) {
                    case 3 -> (long) readUInt16(data, at, little);
                    case 4 -> readUInt32(data, at, little);
                    case 1, 7 -> (long) (data[at] & 0xFF);
                    case 8 -> (long) (short) readUInt16(data, at, little);
                    case 9 -> (long) (int) readUInt32(data, at, little);
                    default -> 0L;
                });
            }
            tags.put(tag, values.stream().mapToLong(Long::longValue).toArray());
        }
        return tags;
    }

    private static long scalar(Map<Integer, long[]> tags, int tag, long fallback) {
        long[] values = tags.get(tag);
        return values != null && values.length > 0 ? values[0] : fallback;
    }

    private static int typeSize(int type) {
        return switch (type) {
            case 1, 2, 6, 7 -> 1;
            case 3, 8 -> 2;
            case 4, 9, 11 -> 4;
            case 5, 10, 12 -> 8;
            default -> 0;
        };
    }

    private static int readUInt16(byte[] data, int offset, boolean little) {
        int a = data[offset] & 0xFF;
        int b = data[offset + 1] & 0xFF;
        return little ? a | (b << 8) : (a << 8) | b;
    }

    private static long readUInt32(byte[] data, int offset, boolean little) {
        long a = data[offset] & 0xFF;
        long b = data[offset + 1] & 0xFF;
        long c = data[offset + 2] & 0xFF;
        long d = data[offset + 3] & 0xFF;
        return little ? a | (b << 8) | (c << 16) | (d << 24) : (a << 24) | (b << 16) | (c << 8) | d;
    }
}
