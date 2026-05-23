package com.mtm2.jpod.io;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decodes Terminal Reality raw image and palette formats.
 *
 * RAW format (art\*.raw, data\*.raw, data\*.clr):
 *   A flat array of 8-bit palette indices with no header.
 *   Dimensions are inferred from the file size:
 *     4 096 bytes → 64 × 64  (art texture)
 *    65 536 bytes → 256 × 256  (heightmap / colour-lookup-table / CLR)
 *   Exact-square payloads fall back to {@code side × side}. Non-square payloads
 *   require the caller to choose dimensions explicitly.
 *
 * ACT format (art\*.act):
 *   768 bytes = 256 colours × 3 bytes (R, G, B).
 *   Terminal Reality stores 6-bit VGA values (0–63).
 *   The decoder auto-detects 8-bit Adobe ACT files (any channel > 63 → 8-bit).
 */
public final class RawImageDecoder {

    /** Standard art-texture size: 64 × 64. */
    public static final int ART_TEXTURE_SIDE = 64;

    /** Standard heightmap / CLR size: 256 × 256. */
    public static final int LARGE_IMAGE_SIDE = 256;

    /** Number of bytes in an ACT palette file (256 colours × 3 channels). */
    public static final int ACT_PALETTE_BYTES = 768;

    private RawImageDecoder() {}

    // -------------------------------------------------------------------------
    // ACT palette decoding
    // -------------------------------------------------------------------------

    /**
     * Decodes a 768-byte ACT palette into a 256-element ARGB int array.
     *
     * Auto-detects the encoding:
     *   • If every channel byte is ≤ 63 → 6-bit VGA (Terminal Reality format).
     *     Scaled with the exact VGA formula: {@code round(v * 255 / 63)}, so
     *     index 0 → 0 and index 63 → 255 with no clamping artefacts.
     *   • If any channel byte is > 63 → 8-bit Adobe ACT; values used directly.
     *
     * @param actBytes raw ACT file bytes (must be ≥ 768 bytes)
     * @return 256 ARGB values with full alpha (0xFF_??????)
     * @throws IllegalArgumentException if the byte array is too short
     */
    public static int[] decodeAct(byte[] actBytes) {
        if (actBytes.length < ACT_PALETTE_BYTES) {
            throw new IllegalArgumentException(
                    "ACT file must be at least 768 bytes, got " + actBytes.length);
        }

        // Auto-detect: any value > 63 means 8-bit Adobe ACT
        boolean is8bit = false;
        for (int i = 0; i < ACT_PALETTE_BYTES; i++) {
            if ((actBytes[i] & 0xFF) > 63) { is8bit = true; break; }
        }

        int[] palette = new int[256];
        for (int i = 0; i < 256; i++) {
            int rv = actBytes[i * 3]     & 0xFF;
            int gv = actBytes[i * 3 + 1] & 0xFF;
            int bv = actBytes[i * 3 + 2] & 0xFF;
            int r, g, b;
            if (is8bit) {
                r = rv; g = gv; b = bv;
            } else {
                // 6-bit VGA → 8-bit: exact mapping 0→0, 63→255
                r = (rv * 255 + 31) / 63;
                g = (gv * 255 + 31) / 63;
                b = (bv * 255 + 31) / 63;
            }
            palette[i] = 0xFF_000000 | (r << 16) | (g << 8) | b;
        }
        return palette;
    }

    /**
     * Returns a greyscale palette where index {@code i} maps to RGB(i, i, i).
     * Used as a fallback when no ACT file is available.
     */
    public static int[] greyscalePalette() {
        int[] palette = new int[256];
        for (int i = 0; i < 256; i++) {
            palette[i] = 0xFF_000000 | (i << 16) | (i << 8) | i;
        }
        return palette;
    }

    // -------------------------------------------------------------------------
    // RAW / CLR image decoding
    // -------------------------------------------------------------------------

    /**
     * Decodes a raw 8-bit paletted image.
     *
     * @param rawBytes  the flat pixel-index data
     * @param palette   256-element ARGB palette (from {@link #decodeAct} or {@link #greyscalePalette})
     * @param width     image width in pixels
     * @param height    image height in pixels
     * @return a {@link BufferedImage} with type {@code TYPE_INT_ARGB}
     * @throws IllegalArgumentException if the byte array is smaller than {@code width × height}
     */
    public static BufferedImage decodeRaw(byte[] rawBytes, int[] palette, int width, int height) {
        int required = width * height;
        if (rawBytes.length < required) {
            throw new IllegalArgumentException(
                    "RAW data too short: expected " + required + " bytes, got " + rawBytes.length);
        }
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = rawBytes[y * width + x] & 0xFF;
                img.setRGB(x, y, palette[index]);
            }
        }
        return img;
    }

    /**
     * Infers image dimensions from the file size.
     *
     * @param byteCount number of bytes in the RAW/CLR file
     * @return {@code int[2]} = {width, height}, or {@code null} if the size is unrecognised
     */
    public static int[] detectDimensions(int byteCount) {
        if (byteCount == ART_TEXTURE_SIDE * ART_TEXTURE_SIDE) {
            return new int[]{ART_TEXTURE_SIDE, ART_TEXTURE_SIDE};
        }
        if (byteCount == LARGE_IMAGE_SIDE * LARGE_IMAGE_SIDE) {
            return new int[]{LARGE_IMAGE_SIDE, LARGE_IMAGE_SIDE};
        }
        // Best-effort: largest square that fits
        int side = (int) Math.sqrt(byteCount);
        if (side * side == byteCount && side > 0) {
            return new int[]{side, side};
        }
        return null;
    }

    /**
     * Returns exact width/height pairs whose product matches {@code byteCount},
     * ordered from most square-like to most elongated.
     */
    public static List<int[]> suggestDimensions(int byteCount) {
        List<int[]> suggestions = new ArrayList<>();
        if (byteCount <= 0) {
            return suggestions;
        }
        for (int width = 1; width * width <= byteCount; width++) {
            if (byteCount % width != 0) {
                continue;
            }
            int height = byteCount / width;
            suggestions.add(new int[]{width, height});
        }
        suggestions.sort(Comparator
                .comparingInt((int[] dims) -> Math.abs(dims[0] - dims[1]))
                .thenComparingInt(dims -> Math.max(dims[0], dims[1])));
        return suggestions;
    }

    /**
     * Returns {@code true} if the given filename (case-insensitive) has a raw image
     * extension supported by this decoder.
     */
    public static boolean isRawImage(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.endsWith(".RAW") || upper.endsWith(".CLR");
    }

    /**
     * Returns {@code true} if the given filename has an ACT palette extension.
     */
    public static boolean isActPalette(String name) {
        return name.toUpperCase(java.util.Locale.ROOT).endsWith(".ACT");
    }

    /**
     * Returns {@code true} if the given filename extension suggests plain ASCII/text content.
     */
    public static boolean isTextFile(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.endsWith(".TXT") || upper.endsWith(".DEF") || upper.endsWith(".NAV")
                || upper.endsWith(".TDF") || upper.endsWith(".TEX") || upper.endsWith(".LVL")
                || upper.endsWith(".INI") || upper.endsWith(".LST") || upper.endsWith(".INF")
                || upper.endsWith(".CFG") || upper.endsWith(".VOX") || upper.endsWith(".SIT")
                || upper.endsWith(".TRN") || upper.endsWith(".NDX") || upper.endsWith(".TNL")
                || upper.endsWith(".TTX") || upper.endsWith(".TRK");
    }

    /**
     * Loads the default Metal Crusher palette bundled as a package-relative
     * classpath resource.
     *
     * <p>The file must be placed at
     * {@code src/main/resources/com/mtm2/jpod/palettes/metalcr2.act},
     * which is packaged in the JAR at {@code /com/mtm2/jpod/palettes/metalcr2.act}
     * and loaded via an absolute classpath resource lookup.
     * Falls back to greyscale if the resource is absent.
     */
    public static int[] loadResourcePalette() {
        try (java.io.InputStream in =
                     RawImageDecoder.class.getResourceAsStream("/com/mtm2/jpod/palettes/metalcr2.act")) {
            if (in != null) {
                return decodeAct(in.readAllBytes());
            }
        } catch (Exception ignored) {}
        return greyscalePalette();
    }
}
