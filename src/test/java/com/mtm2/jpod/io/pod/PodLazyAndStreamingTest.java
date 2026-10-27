package com.mtm2.jpod.io.pod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Directory-only opening, on-demand payloads, and the streamed, swap-in-place writer. */
class PodLazyAndStreamingTest {

    @TempDir
    Path temp;

    @Test
    void openingReadsTheDirectoryAndPayloadsComeFromTheFileOnDemand() throws Exception {
        Path file = temp.resolve("lazy.pod");
        new PodArchiveWriter().write(file, "lazy", List.of(blob("A.TXT", 10), blob("ART\\B.RAW", 4096)));
        try (PodArchive archive = new PodArchiveReader().read(file)) {
            assertEquals(Files.size(file), archive.getData().length());
            PodArchive.Entry b = archive.findEntry("art\\b.raw").orElseThrow();
            assertArrayEquals(payload(4096), archive.getEntryBytes(b));

            ByteArrayOutputStream streamed = new ByteArrayOutputStream();
            archive.copyEntryTo(b, streamed, new byte[100]);
            assertArrayEquals(payload(4096), streamed.toByteArray());
            assertEquals("B.RAW", archive.findEntryByTitle("b.raw").orElseThrow().title());
        }
    }

    @Test
    void aClosedArchiveRefusesToReadRatherThanReturningGarbage() throws Exception {
        Path file = temp.resolve("closed.pod");
        new PodArchiveWriter().write(file, "", List.of(blob("A.TXT", 10)));
        PodArchive archive = new PodArchiveReader().read(file);
        PodArchive.Entry entry = archive.getEntries().get(0);
        archive.close();
        assertThrows(IllegalStateException.class, () -> archive.getEntryBytes(entry));
    }

    @Test
    void savingOverTheOpenArchiveStreamsFromItAndSwapsInPlace() throws Exception {
        Path file = temp.resolve("self.pod");
        new PodArchiveWriter().write(file, "before", List.of(blob("A.TXT", 10), blob("B.TXT", 20)));
        PodArchive archive = new PodArchiveReader().read(file);
        List<PodArchiveWriter.Blob> blobs = new ArrayList<>();
        for (PodArchive.Entry entry : archive.getEntries()) {
            blobs.add(new PodArchiveWriter.Blob("NEW\\" + entry.name(), archive.getData(), entry.offset(),
                    (int) entry.length(), null, null, 0));
        }
        boolean[] released = {false};
        new PodArchiveWriter().write(file, "after", blobs,
                new PodArchiveWriter.WriteOptions(PodArchive.Format.POD1, null, List.of()),
                () -> {
                    released[0] = true;
                    archive.close();
                });
        assertTrue(released[0]);
        assertFalse(Files.exists(temp.resolve("self.pod.jpodtmp")));
        try (PodArchive reread = new PodArchiveReader().read(file)) {
            assertEquals("after", reread.getComment());
            assertEquals("NEW\\B.TXT", reread.getEntries().get(1).name());
            assertArrayEquals(payload(20), reread.getEntryBytes(reread.getEntries().get(1)));
        }
    }

    @Test
    void aFailedWriteLeavesTheTargetUntouched() throws Exception {
        Path file = temp.resolve("keep.pod");
        new PodArchiveWriter().write(file, "", List.of(blob("A.TXT", 10)));
        byte[] before = Files.readAllBytes(file);
        String tooLong = "ART\\" + "X".repeat(40) + ".RAW";
        assertThrows(Exception.class, () -> new PodArchiveWriter().write(file, "", List.of(blob(tooLong, 4))));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void streamedAndInMemoryPod2AreIdenticalAndVerify() throws Exception {
        List<PodArchive.AuditEntry> audits = List.of(new PodArchive.AuditEntry("me", 1, PodArchive.AuditAction.ADD,
                "A.TXT", 0, 0, 1, 10));
        List<PodArchiveWriter.Blob> blobs = List.of(blob("A.TXT", 10), blob("DIR\\B.BIN", 70000));
        PodArchiveWriter.WriteOptions options =
                new PodArchiveWriter.WriteOptions(PodArchive.Format.POD2, null, audits);
        Path file = temp.resolve("two.pod");
        new PodArchiveWriter().write(file, "pod two", blobs, options);
        byte[] inMemory = new PodArchiveWriter().buildBytes("pod two", blobs, options);
        assertArrayEquals(inMemory, Files.readAllBytes(file));
        try (PodArchive archive = new PodArchiveReader().read(file)) {
            assertTrue(archive.isChecksumValid());
            for (PodArchive.Entry entry : archive.getEntries()) {
                assertTrue(archive.isEntryChecksumValid(entry));
            }
            assertEquals(1, archive.getAuditEntries().size());
        }
    }

    @Test
    void pod2AuditTrailsMayOutnumberTheEntryLimit() throws Exception {
        // 4x4 Evolution 2's TRUCK.POD carries 8,430 audit records for 1,126 entries.
        List<PodArchive.AuditEntry> audits = new ArrayList<>();
        for (int i = 0; i < PodArchiveReader.MAX_REASONABLE_ITEMS + 10; i++) {
            audits.add(new PodArchive.AuditEntry("me", i, PodArchive.AuditAction.CHANGE, "A.TXT", 0, 0, 0, 0));
        }
        byte[] bytes = new PodArchiveWriter().buildBytes("", List.of(blob("A.TXT", 10)),
                new PodArchiveWriter.WriteOptions(PodArchive.Format.POD2, null, audits.subList(0, 10)));
        // Patch the count and append the remaining records, which the writer itself caps.
        int total = audits.size();
        byte[] extended = new byte[bytes.length + (total - 10) * 312];
        System.arraycopy(bytes, 0, extended, 0, bytes.length);
        for (int i = 10; i < total; i++) {
            int at = bytes.length + (i - 10) * 312;
            extended[at + 36] = 2;
        }
        extended[92] = (byte) total;
        extended[93] = (byte) (total >>> 8);
        PodArchive archive = new PodArchiveReader().read(extended);
        assertEquals(total, archive.getAuditEntries().size());
    }

    @Test
    void theTableCrcMatchesTheKnownCheckValue() {
        assertEquals(0x0376E6E7L, PodArchiveWriter.crc32Mpeg2("123456789".getBytes()));
    }

    private static PodArchiveWriter.Blob blob(String name, int size) {
        return new PodArchiveWriter.Blob(name, payload(size), null, null, 0);
    }

    private static byte[] payload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte) (i * 31 + 7);
        return data;
    }
}
