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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void duplicateNamesSurviveWhenTheArchiveAlreadyHadThem() throws IOException {
        // Shipped and community PODs repeat a name so the later copy shadows the
        // earlier one. Authoring still rejects it, but an archive read from disk
        // has to stay savable.
        PodArchiveWriter writer = new PodArchiveWriter();
        List<PodArchiveWriter.Blob> blobs = List.of(blob(SHORT_NAME, 4), blob(SHORT_NAME, 6));

        assertThrows(IOException.class, () -> writer.buildBytes("", blobs));

        byte[] bytes = writer.buildBytes("", blobs, new PodArchiveWriter.WriteOptions(
                PodArchive.Format.POD1, null, List.of(), true));
        Path file = temp.resolve("dupes.pod");
        Files.write(file, bytes);

        PodArchive archive = new PodArchiveReader().read(file);
        assertEquals(2, archive.getEntries().size());
        assertEquals(SHORT_NAME, archive.getEntries().get(0).name());
        assertEquals(SHORT_NAME, archive.getEntries().get(1).name());
        assertEquals(4, archive.getEntries().get(0).length());
        assertEquals(6, archive.getEntries().get(1).length());
    }

    @Test
    void unsafePathsAreRejectedEvenWhenDuplicatesAreAllowed() throws IOException {
        // Relaxing the duplicate rule must not relax path safety with it.
        PodArchiveWriter writer = new PodArchiveWriter();
        PodArchiveWriter.WriteOptions lenient = new PodArchiveWriter.WriteOptions(
                PodArchive.Format.POD1, null, List.of(), true);

        assertThrows(IOException.class, () -> writer.buildBytes("",
                List.of(blob("..\\ESCAPE.RAW", 4)), lenient));
        assertThrows(IOException.class, () -> writer.buildBytes("",
                List.of(blob("\\ROOTED.RAW", 4)), lenient));
    }

    @Test
    void payloadOverlappingTheDirectoryIsRejected() throws IOException {
        Path file = temp.resolve("underflow.pod");
        byte[] bytes = handWrittenArchive(32, 40, SHORT_NAME, payload(6));
        // The first payload byte can never precede the end of the directory. An
        // offset inside the header or the directory means this is not a classic
        // POD1 layout, whatever the name field happens to decode to.
        writeInt32(bytes, 84 + 32 + 4, 84);
        Files.write(file, bytes);

        assertThrows(IOException.class, () -> new PodArchiveReader().read(file));
    }

    @Test
    void aPayloadStartingExactlyAtTheDirectoryEndIsAccepted() throws IOException {
        Path file = temp.resolve("floor.pod");
        // 84-byte header plus one 40-byte record puts the floor at 124, and the
        // hand-written archive already lays the payload down there.
        byte[] bytes = handWrittenArchive(32, 40, SHORT_NAME, payload(6));
        Files.write(file, bytes);

        PodArchive archive = new PodArchiveReader().read(file);
        assertEquals(PodArchive.Format.POD1, archive.getFormat());
        assertEquals(124, archive.getEntries().get(0).offset());
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
    void reSavingKeepsWhateverFollowedTheNameTerminator() throws IOException {
        // Fury3's FURYSE.POD packs a second null-terminated string into the spare
        // bytes of each RAW entry's name field. Nothing here reads past the first
        // terminator, but re-saving must not throw those bytes away.
        Path source = temp.resolve("withextra.pod");
        byte[] bytes = handWrittenArchive(32, 40, SHORT_NAME, payload(6));
        byte[] extra = "VGA.ACT".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(extra, 0, bytes, 84 + SHORT_NAME.length() + 1, extra.length);
        Files.write(source, bytes);

        PodArchive archive = new PodArchiveReader().read(source);
        assertEquals(SHORT_NAME, archive.getEntries().get(0).name());

        List<PodArchiveWriter.Blob> blobs = new java.util.ArrayList<>();
        for (PodArchive.Entry entry : archive.getEntries()) {
            blobs.add(new PodArchiveWriter.Blob(
                    entry.name(), archive.getEntryBytes(entry), entry.nameField()));
        }
        byte[] rebuilt = new PodArchiveWriter().buildBytes(archive.getComment(), blobs);

        assertArrayEquals(bytes, rebuilt);
    }

    @Test
    void anEntryAddedFromDiskGetsAFreshlyBuiltNameField() throws IOException {
        byte[] rebuilt = new PodArchiveWriter().buildBytes("", List.of(blob("NEW.RAW", 4)));

        // No original field to preserve, so the padding is all zeros.
        byte[] field = java.util.Arrays.copyOfRange(rebuilt, 84, 84 + 32);
        for (int i = "NEW.RAW".length(); i < 32; i++) {
            assertEquals(0, field[i], "byte " + i + " should be zero");
        }
    }

    @Test
    void aPreservedClassicFieldStillFitsWhenTheArchiveGoesExtended() throws IOException {
        Path source = temp.resolve("mixed.pod");
        byte[] bytes = handWrittenArchive(32, 40, SHORT_NAME, payload(6));
        byte[] extra = "VGA.ACT".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(extra, 0, bytes, 84 + SHORT_NAME.length() + 1, extra.length);
        Files.write(source, bytes);

        PodArchive archive = new PodArchiveReader().read(source);
        PodArchive.Entry original = archive.getEntries().get(0);

        // Adding a long-named entry forces the 64-byte directory; the preserved
        // 32-byte field must be copied into it rather than dropped.
        List<PodArchiveWriter.Blob> blobs = List.of(
                new PodArchiveWriter.Blob(
                        original.name(), archive.getEntryBytes(original), original.nameField()),
                blob(LONG_NAME, 4));
        Path target = temp.resolve("mixed-out.pod");
        assertEquals(PodArchive.Format.POD1_64,
                new PodArchiveWriter().write(target, archive.getComment(), blobs));

        byte[] written = Files.readAllBytes(target);
        byte[] field = java.util.Arrays.copyOfRange(written, 84, 84 + 64);
        assertEquals("VGA.ACT", new String(
                field, SHORT_NAME.length() + 1, extra.length, StandardCharsets.ISO_8859_1));
    }

    @Test
    void theSecondStringInADirectoryFieldIsReadAsAPaletteName() throws IOException {
        // MTM1, Terminal Velocity, Fury3 and Hellbender all record the palette a
        // RAW was authored against in the spare bytes of its directory field.
        Path source = temp.resolve("hinted.pod");
        byte[] bytes = handWrittenArchive(32, 40, SHORT_NAME, payload(6));
        byte[] palette = "METALCR2.ACT".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(palette, 0, bytes, 84 + SHORT_NAME.length() + 1, palette.length);
        Files.write(source, bytes);

        PodArchive.Entry entry = new PodArchiveReader().read(source).getEntries().get(0);

        assertEquals(SHORT_NAME, entry.name());
        assertEquals("METALCR2.ACT", entry.embeddedPaletteName());
    }

    @Test
    void anEntryWithOnlyPaddingHasNoPaletteName() throws IOException {
        Path source = temp.resolve("plain.pod");
        Files.write(source, handWrittenArchive(32, 40, SHORT_NAME, payload(6)));

        assertNull(new PodArchiveReader().read(source).getEntries().get(0).embeddedPaletteName());
    }

    @Test
    void aSyntheticEntryHasNoDirectoryFieldAndNoPaletteName() {
        PodArchive.Entry entry = new PodArchive.Entry("A.RAW", 4, 0);

        assertNull(entry.nameField());
        assertNull(entry.embeddedPaletteName());
    }

    @Test
    void anExplicitExtendedSaveNeverCollapsesToClassic() throws IOException {
        PodArchiveWriter writer = new PodArchiveWriter();
        List<PodArchiveWriter.Blob> blobs = List.of(blob("SHORT.RAW", 4));
        byte[] bytes = writer.buildBytes("", blobs, new PodArchiveWriter.WriteOptions(
                PodArchive.Format.POD1_64, null, List.of()));
        Path file = temp.resolve("forced.pod");
        Files.write(file, bytes);
        assertEquals(PodArchive.Format.POD1_64, new PodArchiveReader().read(file).getFormat());
        assertEquals(84 + 72 + 4, bytes.length);
    }

    @Test
    void extendedArchiveWithPaletteRoundTripsByteForByte() throws IOException {
        byte[] original = handWrittenArchive(64, 72, LONG_NAME, payload(7));
        byte[] palette = "VGA.ACT".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(palette, 0, original, 84 + LONG_NAME.length() + 1, palette.length);
        Path path = temp.resolve("extended-palette.pod");
        Files.write(path, original);
        PodArchive archive = new PodArchiveReader().read(path);
        PodArchive.Entry entry = archive.getEntries().get(0);
        PodArchiveWriter.Blob blob = new PodArchiveWriter.Blob(entry.name(),
                archive.getEntryBytes(entry), entry.nameField(), entry.embeddedPaletteName(), 0);
        byte[] rebuilt = new PodArchiveWriter().buildBytes(archive.getComment(), List.of(blob),
                new PodArchiveWriter.WriteOptions(archive.getFormat(), archive.getCommentField(), List.of()));
        assertArrayEquals(original, rebuilt);
    }

    @Test
    void renamingARawRebuildsItsFieldAndKeepsThePalette() throws IOException {
        byte[] original = new byte[32];
        byte[] oldName = "OLD.RAW".getBytes(StandardCharsets.ISO_8859_1);
        byte[] palette = "METALCR2.ACT".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(oldName, 0, original, 0, oldName.length);
        System.arraycopy(palette, 0, original, oldName.length + 1, palette.length);
        PodArchiveWriter.Blob renamed = new PodArchiveWriter.Blob(
                "ART\\A_LONGER_RENAMED_TEXTURE.RAW", payload(4), original, "METALCR2.ACT", 0);

        byte[] bytes = new PodArchiveWriter().buildBytes("", List.of(renamed));
        Path file = temp.resolve("renamed.pod");
        Files.write(file, bytes);
        PodArchive.Entry entry = new PodArchiveReader().read(file).getEntries().get(0);
        assertEquals("ART\\A_LONGER_RENAMED_TEXTURE.RAW", entry.name());
        assertEquals("METALCR2.ACT", entry.embeddedPaletteName());
        assertEquals(PodArchive.Format.POD1_64, new PodArchiveReader().read(file).getFormat());
    }

    @Test
    void pod2WriterRoundTripsChecksumsTimestampsAndAudits() throws IOException {
        PodArchiveWriter.Blob file = new PodArchiveWriter.Blob(
                "ART\\TEST.RAW", payload(9), null, null, 1_700_000_000L);
        PodArchive.AuditEntry audit = new PodArchive.AuditEntry("tester", 1_700_000_001L,
                PodArchive.AuditAction.ADD, "ART\\TEST.RAW", 0, 0, 1_700_000_000L, 9);
        byte[] bytes = new PodArchiveWriter().buildBytes("pod2", List.of(file),
                new PodArchiveWriter.WriteOptions(PodArchive.Format.POD2, null, List.of(audit)));
        Path path = temp.resolve("two.pod");
        Files.write(path, bytes);

        PodArchive archive = new PodArchiveReader().read(path);
        assertEquals(PodArchive.Format.POD2, archive.getFormat());
        assertEquals(1_700_000_000L, archive.getEntries().get(0).timestamp());
        assertEquals(PodArchiveWriter.crc32Mpeg2(payload(9)), archive.getEntries().get(0).checksum());
        assertEquals(PodArchiveWriter.crc32Mpeg2(bytes, 8, bytes.length - 8), archive.getChecksum());
        assertEquals(List.of(audit), archive.getAuditEntries());
    }

    @Test
    void crossPortAuthoringVectorsHaveStableBinaryOutput() throws Exception {
        PodArchiveWriter writer = new PodArchiveWriter();
        byte[] classic = writer.buildBytes("vector", List.of(
                new PodArchiveWriter.Blob("A.TXT", new byte[] {1, 2, 3}, null, null, 0)));
        byte[] extended = writer.buildBytes("vector", List.of(
                new PodArchiveWriter.Blob("ART\\T.RAW", new byte[] {4, 5}, null,
                        "METALCR2.ACT", 0)),
                new PodArchiveWriter.WriteOptions(PodArchive.Format.POD1_64, null, List.of()));
        byte[] pod2 = writer.buildBytes("vector", List.of(
                new PodArchiveWriter.Blob("A.TXT", new byte[] {1, 2, 3}, null, null,
                        1_700_000_000L)),
                new PodArchiveWriter.WriteOptions(PodArchive.Format.POD2, null, List.of()));

        assertEquals("a909fc89a0f2b8d1bb4c4e28b4288b8440c7bc67bd2a8258d0438dce2f008cb8", sha256(classic));
        assertEquals("693e615f827790ba6aa6126b2ca27f0fde6ec34d58fdf1edb63b0f4328c609ef", sha256(extended));
        assertEquals("a1679199ed5bb19ba310ae18ea7c0bc6a8c7a55a9244320fb8dbf4cebf5d1c80", sha256(pod2));
        assertEquals(0x0376E6E7L, PodArchiveWriter.crc32Mpeg2("123456789".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void writerRejectsDuplicatesUnsafePathsAndLongComments() {
        PodArchiveWriter writer = new PodArchiveWriter();
        assertThrows(IOException.class, () -> writer.buildBytes("", List.of(
                blob("A.RAW", 1), blob("a.raw", 1))));
        assertThrows(IOException.class, () -> writer.buildBytes("", List.of(blob("..\\A.RAW", 1))));
        assertThrows(IOException.class, () -> writer.buildBytes("X".repeat(80), List.of(blob("A.RAW", 1))));
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

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
