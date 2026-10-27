package com.mtm2.jpod.io.image;

import com.mtm2.jpod.io.RawImageDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ImageDecoderTest {

    // -------------------------------------------------------------------------
    // TGA
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"32, 1, 2", "48, 2, 1", "0, 3, 4", "16, 4, 3"})
    void tgaAppliesAllOriginFlags(int descriptor, int first, int second) {
        byte[] data = tgaHeader(2, 2, 2, 24, descriptor, 12);
        int offset = 18;
        for (int red = 1; red <= 4; red++) {
            data[offset++] = 0;
            data[offset++] = 0;
            data[offset++] = (byte) red;
        }
        BufferedImage image = TgaImageDecoder.decode(data, "origin.tga");
        assertEquals(first, (image.getRGB(0, 0) >> 16) & 0xFF);
        assertEquals(second, (image.getRGB(1, 0) >> 16) & 0xFF);
    }

    @Test
    void tgaDecodesRleAndAlpha() {
        byte[] data = tgaHeader(10, 3, 1, 32, 0x20, 6);
        int offset = 18;
        data[offset++] = (byte) 0x82;
        data[offset++] = 30;
        data[offset++] = 20;
        data[offset++] = 10;
        data[offset] = 40;
        BufferedImage image = TgaImageDecoder.decode(data, "alpha.tga");
        for (int x = 0; x < 3; x++) {
            assertEquals(0x280A141E, image.getRGB(x, 0));
        }
    }

    @Test
    void tgaRejectsUnsupportedAndTruncatedPayloads() {
        assertThrows(IllegalArgumentException.class,
                () -> TgaImageDecoder.decode(tgaHeader(1, 1, 1, 24, 0x20, 0), "mapped.tga"));
        assertThrows(IllegalArgumentException.class, () -> TgaImageDecoder.decode(new byte[17], "short.tga"));
        assertThrows(IllegalArgumentException.class,
                () -> TgaImageDecoder.decode(tgaHeader(2, 1, 1, 24, 0x20, 0), "empty.tga"));
    }

    private static byte[] tgaHeader(int type, int width, int height, int bits, int descriptor, int extra) {
        byte[] data = new byte[18 + extra];
        data[2] = (byte) type;
        data[12] = (byte) width;
        data[13] = (byte) (width >> 8);
        data[14] = (byte) height;
        data[15] = (byte) (height >> 8);
        data[16] = (byte) bits;
        data[17] = (byte) descriptor;
        return data;
    }

    // -------------------------------------------------------------------------
    // TIFF
    // -------------------------------------------------------------------------

    @Test
    void tiffDecodesASingleSamplePaletteImageAsFullyOpaque() {
        BufferedImage image = TiffImageDecoder.decode(buildTiff(1, 1, 3, 1), "ROCK.TIF");
        assertEquals(2, image.getWidth());
        assertEquals(2, image.getHeight());
        // Palette entry 1 is pure red, entry 0 is black; the sample grid is 0,1 / 1,0.
        assertEquals(0xFF000000, image.getRGB(0, 0));
        assertEquals(0xFFFF0000, image.getRGB(1, 0));
        assertFalse(TiffImageDecoder.hasAlphaSample(buildTiff(1, 1, 3, 1), "ROCK.TIF"));
    }

    /** The second sample is an opacity gradient, so it reaches alpha intact. */
    @Test
    void tiffUsesTheSecondSampleAsAlpha() {
        BufferedImage image = TiffImageDecoder.decode(buildTiff(2, 1, 3, 1), "PALM.TIF");
        assertEquals(0x00, image.getRGB(0, 0) >>> 24);
        assertEquals(0x40, image.getRGB(1, 0) >>> 24);
        assertEquals(0x80, image.getRGB(0, 1) >>> 24);
        assertEquals(0xFF, image.getRGB(1, 1) >>> 24);
        assertTrue(TiffImageDecoder.hasAlphaSample(buildTiff(2, 1, 3, 1), "PALM.TIF"));
    }

    @Test
    void tiffRefusesWhatItCannotReadFaithfully() {
        assertThrows(IllegalArgumentException.class,
                () -> TiffImageDecoder.decode(buildTiff(1, 5, 3, 1), "LZW.TIF"));
        assertThrows(IllegalArgumentException.class,
                () -> TiffImageDecoder.decode(buildTiff(1, 1, 2, 1), "RGB.TIF"));
        assertThrows(IllegalArgumentException.class,
                () -> TiffImageDecoder.decode(buildTiff(2, 1, 3, 2), "PLANAR.TIF"));
        assertFalse(TiffImageDecoder.isTiff(new byte[] {0, 1, 2, 3, 4, 5, 6, 7}));
        assertTrue(TiffImageDecoder.isTiff(buildTiff(1, 1, 3, 1)));
    }

    /**
     * A 2x2 palette TIFF built by hand: header, pixel data, palette, then the IFD.
     * Samples are palette indices 0,1 / 1,0, and with two samples per pixel the alpha
     * run is 0x00, 0x40, 0x80, 0xFF.
     */
    private static byte[] buildTiff(int samplesPerPixel, int compression, int photometric, int planar) {
        int width = 2;
        int height = 2;
        byte[] indices = {0, 1, 1, 0};
        byte[] alpha = {0x00, 0x40, (byte) 0x80, (byte) 0xFF};
        ByteArrayOutputStream pixels = new ByteArrayOutputStream();
        for (int i = 0; i < indices.length; i++) {
            pixels.write(indices[i]);
            if (samplesPerPixel == 2) pixels.write(alpha[i]);
        }
        int[] colorMap = new int[256 * 3];
        colorMap[1] = 0xFFFF; // entry 1: red

        int pixelOffset = 8;
        int mapOffset = pixelOffset + pixels.size();
        int ifdOffset = mapOffset + colorMap.length * 2;
        int[][] tags = {
                {256, 3, 1, width},
                {257, 3, 1, height},
                {258, 3, samplesPerPixel, 8},
                {259, 3, 1, compression},
                {262, 3, 1, photometric},
                {273, 4, 1, pixelOffset},
                {277, 3, 1, samplesPerPixel},
                {278, 3, 1, height},
                {279, 4, 1, pixels.size()},
                {284, 3, 1, planar},
                {320, 3, colorMap.length, mapOffset},
        };
        byte[] data = new byte[ifdOffset + 2 + tags.length * 12 + 4];
        data[0] = 'I';
        data[1] = 'I';
        writeUInt16(data, 2, 42);
        writeUInt32(data, 4, ifdOffset);
        System.arraycopy(pixels.toByteArray(), 0, data, pixelOffset, pixels.size());
        for (int i = 0; i < colorMap.length; i++) writeUInt16(data, mapOffset + i * 2, colorMap[i]);
        writeUInt16(data, ifdOffset, tags.length);
        for (int i = 0; i < tags.length; i++) {
            int record = ifdOffset + 2 + i * 12;
            writeUInt16(data, record, tags[i][0]);
            writeUInt16(data, record + 2, tags[i][1]);
            writeUInt32(data, record + 4, tags[i][2]);
            // Values small enough to fit are packed into the value field itself, so a
            // two-sample BitsPerSample fills both halves.
            if (tags[i][1] == 3 && tags[i][2] <= 2) {
                for (int v = 0; v < tags[i][2]; v++) writeUInt16(data, record + 8 + v * 2, tags[i][3]);
            } else {
                writeUInt32(data, record + 8, tags[i][3]);
            }
        }
        return data;
    }

    private static void writeUInt16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >> 8);
    }

    private static void writeUInt32(byte[] data, int offset, int value) {
        writeUInt16(data, offset, value);
        writeUInt16(data, offset + 2, value >>> 16);
    }

    // -------------------------------------------------------------------------
    // RAW extras
    // -------------------------------------------------------------------------

    @Test
    void screenSizedRawsAreRecognizedOutright() {
        assertArrayEquals(new int[] {320, 200}, RawImageDecoder.detectDimensions(64000));
        assertArrayEquals(new int[] {640, 400}, RawImageDecoder.detectDimensions(256000));
        assertArrayEquals(new int[] {640, 480}, RawImageDecoder.detectDimensions(307200));
        assertArrayEquals(new int[] {64, 64}, RawImageDecoder.detectDimensions(4096));
        assertNull(RawImageDecoder.detectDimensions(4097));
        assertArrayEquals(new int[] {640, 400}, RawImageDecoder.preferredDimensions(256000));
    }

    @Test
    void anOpacityPlaneOfTheRightSizeBecomesAlphaAndAWrongOneIsIgnored() {
        BufferedImage image = RawImageDecoder.decodeRaw(new byte[4], RawImageDecoder.greyscalePalette(), 2, 2);
        BufferedImage merged = RawImageDecoder.applyOpacityPlane(image, new byte[] {0, 64, (byte) 128, (byte) 255});
        assertEquals(0, merged.getRGB(0, 0) >>> 24);
        assertEquals(128, merged.getRGB(0, 1) >>> 24);
        assertSame(image, RawImageDecoder.applyOpacityPlane(image, new byte[3]));
    }

    @Test
    void disabledTrackAndTruckFilesStayReadableAsText() {
        for (String name : new String[] {"A.SIX", "A.SIY", "A.TRX", "A.SI2", "A.TXV", "A.VEG", "A.WAT"}) {
            assertTrue(RawImageDecoder.isTextFile(name), name);
        }
    }
}
