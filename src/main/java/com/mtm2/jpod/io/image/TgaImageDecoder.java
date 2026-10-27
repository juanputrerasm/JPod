package com.mtm2.jpod.io.image;

import java.awt.image.BufferedImage;

/** Decodes uncompressed and RLE-compressed 24/32-bit true-colour TGA images. */
public final class TgaImageDecoder {

    private static final int HEADER_BYTES = 18;

    private TgaImageDecoder() {}

    /**
     * Decodes a TGA, honouring its image-origin flags and keeping a 32-bit image's
     * alpha channel.
     *
     * @throws IllegalArgumentException when the data is not a TGA this supports
     */
    public static BufferedImage decode(byte[] data, String name) {
        String displayName = name == null || name.isEmpty() ? "image" : name;
        if (data.length < HEADER_BYTES) {
            throw invalid(displayName, "header is truncated");
        }

        int idLength = data[0] & 0xFF;
        int colorMapType = data[1] & 0xFF;
        int imageType = data[2] & 0xFF;
        int width = readUInt16(data, 12);
        int height = readUInt16(data, 14);
        int bitsPerPixel = data[16] & 0xFF;
        int descriptor = data[17] & 0xFF;

        if (colorMapType != 0 || (imageType != 2 && imageType != 10)) {
            throw invalid(displayName, "expected uncompressed or RLE true-colour data");
        }
        if (width == 0 || height == 0 || (bitsPerPixel != 24 && bitsPerPixel != 32)) {
            throw invalid(displayName, "expected non-zero dimensions and 24- or 32-bit pixels");
        }

        int pixelCount = width * height;
        int[] source = new int[pixelCount];
        int[] offset = {HEADER_BYTES + idLength};
        if (offset[0] > data.length) {
            throw invalid(displayName, "image ID is truncated");
        }

        int bytesPerPixel = bitsPerPixel / 8;
        int pixel = 0;
        if (imageType == 2) {
            while (pixel < pixelCount) {
                source[pixel++] = readPixel(data, offset, bytesPerPixel, displayName);
            }
        } else {
            while (pixel < pixelCount) {
                if (offset[0] >= data.length) {
                    throw invalid(displayName, "RLE packet is truncated");
                }
                int packet = data[offset[0]++] & 0xFF;
                int count = (packet & 0x7F) + 1;
                if (count > pixelCount - pixel) {
                    throw invalid(displayName, "RLE packet contains too many pixels");
                }
                if ((packet & 0x80) != 0) {
                    int argb = readPixel(data, offset, bytesPerPixel, displayName);
                    for (int i = 0; i < count; i++) source[pixel++] = argb;
                } else {
                    for (int i = 0; i < count; i++) {
                        source[pixel++] = readPixel(data, offset, bytesPerPixel, displayName);
                    }
                }
            }
        }

        boolean topOrigin = (descriptor & 0x20) != 0;
        boolean rightOrigin = (descriptor & 0x10) != 0;
        int[] oriented = source;
        if (!topOrigin || rightOrigin) {
            oriented = new int[pixelCount];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int targetX = rightOrigin ? width - 1 - x : x;
                    int targetY = topOrigin ? y : height - 1 - y;
                    oriented[targetY * width + targetX] = source[y * width + x];
                }
            }
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, oriented, 0, width);
        return image;
    }

    private static int readPixel(byte[] data, int[] offset, int bytesPerPixel, String name) {
        if (offset[0] > data.length - bytesPerPixel) {
            throw invalid(name, "pixel data is truncated");
        }
        int at = offset[0];
        int b = data[at] & 0xFF;
        int g = data[at + 1] & 0xFF;
        int r = data[at + 2] & 0xFF;
        int a = bytesPerPixel == 4 ? data[at + 3] & 0xFF : 255;
        offset[0] += bytesPerPixel;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int readUInt16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static IllegalArgumentException invalid(String name, String reason) {
        return new IllegalArgumentException("Invalid or unsupported TGA " + name + ": " + reason);
    }
}
