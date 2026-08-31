package com.mtm2.jpod.io.pod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers classic POD1 and POD1-64 directory detection, round-tripping, and the
 * rejection rules from the POD1-64 format notes.
 */
class PodArchiveFormatTest {

    private static final String SHORT_NAME = "ART\\WALL01.RAW";
    /** 40 bytes, longer than the classic 31-byte budget but inside the 63-byte one. */
    private static final String LONG_NAME = "MODELS\\TRUCKS\\CUSTOM_BIGFOOT_WHEEL01.RAW";

    @TempDir
    Path temp;

    @Test
    void shortNamesProduceClassicPod1() throws IOException {
        Path file = temp.resolve("classic.pod");
        PodArchive.Format written = new PodArchiveWriter().write(file, "classic archive",
                List.of(blob(SHORT_NAME, 10), blob("DEMO1.RAW", 20)));

        assertEquals(PodArchive.Format.POD1, written);

        PodArchive archive = new PodArchiveReader().read(file);
        assertEquals(PodArchive.Format.POD1, archive.getFormat());
        assertEquals("POD1", archive.getFormat().displayName());
        assertEquals("classic archive", archive.getComment());
        assertEquals(List.of(SHORT_NAME, "DEMO1.RAW"),
                archive.getEntries().stream().map(PodArchive.Entry::name).toList());
        assertEquals(84 + 2 * 40, archive.getEntries().get(0).offset());
        assertArrayEquals(payload(10), archive.getEntryBytes(archive.getEntries().get(0)));
    }

    @Test
    void oneLongNameSwitchesTheWholeDirectoryToPod1_64() throws IOException {
        Path file = temp.resolve("extended.pod");
        PodArchive.Format written = new PodArchiveWriter().write(file, "extended archive",
                List.of(blob(SHORT_NAME, 10), blob(LONG_NAME, 20)));

        assertEquals(PodArchive.Format.POD1_64, written);

        PodArchive archive = new PodArchiveReader().read(file);
        assertEquals(PodArchive.Format.POD1_64, archive.getFormat());
        assertEquals("Extended POD1", archive.getFormat().displayName());
        assertTrue(archive.isPod1Family());
        assertEquals("extended archive", archive.getComment());
        assertEquals(List.of(SHORT_NAME, LONG_NAME),
                archive.getEntries().stream().map(PodArchive.Entry::name).toList());
        // 72-byte records place the first payload after the wider table.
        assertEquals(84 + 2 * 72, archive.getEntries().get(0).offset());
        assertArrayEquals(payload(20), archive.getEntryBytes(archive.getEntries().get(1)));
    }

    @Test
    void nameOfExactly31BytesStaysClassic() throws IOException {
        String name = "A".repeat(31);
        assertEquals(PodArchive.Format.POD1,
                new PodArchiveWriter().write(temp.resolve("edge31.pod"), "", List.of(blob(name, 4))));
    }

    @Test
    void nameOfExactly32BytesNeedsPod1_64() throws IOException {
        String name = "A".repeat(32);
        Path file = temp.resolve("edge32.pod");
        assertEquals(PodArchive.Format.POD1_64,
                new PodArchiveWriter().write(file, "", List.of(blob(name, 4))));
        assertEquals(name, new PodArchiveReader().read(file).getEntries().get(0).name());
    }

    @Test
    void nameTooLongForPod1_64IsRejected() {
        List<PodArchiveWriter.Blob> blobs = List.of(blob("A".repeat(64), 4));
        IOException ex = assertThrows(IOException.class,
                () -> new PodArchiveWriter().buildBytes("", blobs));
        assertTrue(ex.getMessage().contains("63"), ex.getMessage());
    }

    @Test
    void handWrittenPod1_64DirectoryIsDetected() throws IOException {
        Path file = temp.resolve("handmade.pod");
        Files.write(file, handWrittenArchive(64, 72, LONG_NAME, payload(6)));

        PodArchive archive = new PodArchiveReader().read(file);
        assertEquals(PodArchive.Format.POD1_64, archive.getFormat());
        assertEquals(LONG_NAME, archive.getEntries().get(0).name());
        assertArrayEquals(payload(6), archive.getEntryBytes(archive.getEntries().get(0)));
    }

    @Test
    void classicLayoutWinsWhenBothCouldParse() throws IOException {
        Path file = temp.resolve("ambiguous.pod");
        Files.write(file, handWrittenArchive(32, 40, SHORT_NAME, payload(6)));

        assertEquals(PodArchive.Format.POD1, new PodArchiveReader().read(file).getFormat());
    }

    @Test
    void payloadOutsideTheFileIsRejected() throws IOException {
        Path file = temp.resolve("truncated.pod");
        byte[] bytes = handWrittenArchive(32, 40, SHORT_NAME, payload(6));
        // Point the single entry past the end of the archive.
        writeInt32(bytes, 84 + 32 + 4, bytes.length + 1);
        Files.write(file, bytes);

        assertThrows(IOException.class, () -> new PodArchiveReader().read(file));
    }

    @Test
    void controlCharactersInsideANameAreRejected() throws IOException {
        Path file = temp.resolve("garbage.pod");
        byte[] bytes = handWrittenArchive(32, 40, SHORT_NAME, payload(6));
        // A control byte inside the name is what a misparsed directory looks like.
        bytes[84 + 5] = 0x01;
        Files.write(file, bytes);

        assertThrows(IOException.class, () -> new PodArchiveReader().read(file));
    }

    @Test
    void emptyNameFieldIsRejected() throws IOException {
        Path file = temp.resolve("noname.pod");
        byte[] bytes = handWrittenArchive(32, 40, SHORT_NAME, payload(6));
        java.util.Arrays.fill(bytes, 84, 84 + 32, (byte) 0);
        Files.write(file, bytes);

        assertThrows(IOException.class, () -> new PodArchiveReader().read(file));
    }

    /** Builds a single-entry POD1 archive with the given directory record widths. */
    private static byte[] handWrittenArchive(int nameSize, int entrySize, String name, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int dataOffset = 84 + entrySize;
        writeInt32(out, 1);
        writeField(out, "hand written", 80);
        writeField(out, name, nameSize);
        writeInt32(out, data.length);
        writeInt32(out, dataOffset);
        out.writeBytes(data);
        return out.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream out, String value, int size) {
        byte[] raw = value.getBytes(StandardCharsets.ISO_8859_1);
        out.write(raw, 0, raw.length);
        out.writeBytes(new byte[size - raw.length]);
    }

    private static void writeInt32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeInt32(byte[] bytes, int offset, int value) {
        bytes[offset]     = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        bytes[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        bytes[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static PodArchiveWriter.Blob blob(String name, int size) {
        return new PodArchiveWriter.Blob(name, payload(size));
    }

    private static byte[] payload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i + 1);
        }
        return data;
    }
}
