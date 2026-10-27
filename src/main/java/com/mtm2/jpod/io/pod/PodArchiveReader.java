package com.mtm2.jpod.io.pod;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a Terminal Reality POD archive into a {@link PodArchive}.
 *
 * <p>Only the header and the directory are read. A POD directory is a few
 * kilobytes even when the archive is hundreds of megabytes, so payloads stay on
 * disk behind a {@link PodDataSource} until something actually asks for them. An
 * archive read from a path owns an open file and must be closed.
 *
 * <p>Validation performed:
 * <ul>
 *   <li>Minimum header size check (4-byte count + 80-byte comment)</li>
 *   <li>Sanity-range check on item count (1 - {@value #MAX_REASONABLE_ITEMS})</li>
 *   <li>Directory table bounds check</li>
 *   <li>Per-entry data bounds check</li>
 * </ul>
 *
 * <p>POD version 1 has no magic value, and its directory record is 40 bytes:
 * {@code char name[32]}, {@code int32 size}, {@code int32 offset}. That is the
 * only layout, so a directory table that does not validate as one is refused.
 */
public final class PodArchiveReader {

    private static final Charset LEGACY_CHARSET = StandardCharsets.ISO_8859_1;
    private static final byte[] EPD_MAGIC  = {'d', 't', 'x', 'e'};
    private static final byte[] POD2_MAGIC = {'P', 'O', 'D', '2'};
    private static final int POD_COMMENT_SIZE    = 80;
    private static final int POD_ENTRY_NAME_SIZE = 32;
    private static final int POD1_ENTRY_SIZE     = 40;
    /** Longest name a POD1 directory field can store, the NUL terminator excluded. */
    public static final int POD1_MAX_NAME_LENGTH = POD_ENTRY_NAME_SIZE - 1;
    private static final int POD2_ENTRY_SIZE     = 20;
    private static final int POD2_AUDIT_SIZE     = 312;
    private static final int EPD_ENTRY_SIZE      = 80;
    private static final int POD1_HEADER_SIZE    = Integer.BYTES + POD_COMMENT_SIZE;
    private static final int POD2_HEADER_SIZE    = 8 + POD_COMMENT_SIZE + Integer.BYTES + Integer.BYTES;
    private static final int POD2_COUNT_OFFSET   = 88;
    private static final int POD2_AUDIT_COUNT_OFFSET = 92;
    private static final int EPD_COUNT_OFFSET    = 0x90;
    private static final int EPD_TABLE_OFFSET    = 0x110;
    private static final int EPD_TITLE_OFFSET    = 4;
    private static final int EPD_TITLE_SIZE      = 4;
    /**
     * The widest a POD2 name can be, taken from the 256-byte entry-path field of an
     * audit record. Used only to bound the name-blob read when an archive's payload
     * offsets are too damaged to say where the blob ends.
     */
    private static final int POD2_MAX_NAME_SIZE  = 256;
    /** Upper sanity limit; Hellbender {@code GAME.POD} exceeds 4 k entries. */
    public static final int MAX_REASONABLE_ITEMS = 8192;

    /**
     * Opens the archive at {@code path}. The archive keeps the file open for as long
     * as it lives, so close it when done.
     *
     * @throws IOException if the file cannot be read, is too small, has a suspicious
     *                     item count, or contains out-of-bounds entries
     */
    public PodArchive read(Path path) throws IOException {
        PodDataSource source = PodDataSource.open(path);
        try {
            return read(source, path.toString());
        } catch (IOException | RuntimeException ex) {
            source.close();
            throw ex;
        }
    }

    /** Reads an archive already held in memory, for fixtures and caller-built bytes. */
    public PodArchive read(byte[] bytes) throws IOException {
        return read(PodDataSource.of(bytes), "<memory>");
    }

    private PodArchive read(PodDataSource source, String path) throws IOException {
        try {
            if (source.length() < POD1_HEADER_SIZE) {
                throw new IOException("File too small to be a POD archive: " + path);
            }
            // Enough for any of the three headers; POD1's is the shortest at 84 bytes.
            byte[] head = source.readExact(0, (int) Math.min(source.length(), POD2_HEADER_SIZE));
            if (hasMagic(head, EPD_MAGIC)) {
                return readEpd(source, head, path);
            }
            if (hasMagic(head, POD2_MAGIC)) {
                return readPod2(source, head, path);
            }
            // POD1 carries no magic, so it is the fallback.
            return readPod1(source, head, path);
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    private PodArchive readPod1(PodDataSource source, byte[] head, String path) throws IOException {
        int itemCount = readInt32LE(head, 0);
        if (itemCount < 1 || itemCount > MAX_REASONABLE_ITEMS) {
            throw new IOException("Suspicious POD item count: " + itemCount);
        }

        String comment = decodeNullTerminated(head, Integer.BYTES, POD_COMMENT_SIZE);
        byte[] commentField = java.util.Arrays.copyOfRange(
                head, Integer.BYTES, Integer.BYTES + POD_COMMENT_SIZE);

        long tableEnd = POD1_HEADER_SIZE + (long) itemCount * POD1_ENTRY_SIZE;
        byte[] directory = source.readExact(0, (int) Math.min(tableEnd, source.length()));
        List<PodArchive.Entry> entries = tryReadPod1Directory(directory, source.length(), itemCount);
        if (entries == null) {
            throw new IOException(
                    "POD1 directory does not validate as 40-byte entries: " + path);
        }

        return new PodArchive(PodArchive.Format.POD1, comment, source, entries, commentField);
    }

    /**
     * Attempts to read the whole POD1 directory table with one record layout.
     *
     * <p>The checks mirror what the engine's own {@code validatePodFile} enforces,
     * plus NUL-termination: every record must decode to a NUL-terminated, plausible,
     * non-empty archive path, and its size and offset must be non-negative and name
     * a range inside the file. Reading them signed matters, because the engine does,
     * so a negative size is a rejected volume rather than a 2 GB one.
     *
     * @param directory     header and directory bytes, starting at file offset zero
     * @param archiveLength size of the whole archive, for the payload bounds check
     * @param itemCount     directory entry count from the header
     * @return the parsed entries, or {@code null} if the directory does not validate
     */
    private static List<PodArchive.Entry> tryReadPod1Directory(byte[] directory,
            long archiveLength, int itemCount) {
        int nameSize = POD_ENTRY_NAME_SIZE;
        int entrySize = POD1_ENTRY_SIZE;
        long tableEnd = POD1_HEADER_SIZE + (long) itemCount * entrySize;
        if (tableEnd > archiveLength || tableEnd > directory.length) {
            return null;
        }

        List<PodArchive.Entry> entries = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            int entryOffset = POD1_HEADER_SIZE + i * entrySize;
            String name   = decodeNullTerminated(directory, entryOffset, nameSize);
            long   length = readInt32LE(directory, entryOffset + nameSize);
            long   offset = readInt32LE(directory, entryOffset + nameSize + Integer.BYTES);
            if (!isNulTerminated(directory, entryOffset, nameSize)
                    || !isPlausibleArchivePath(name) || offset < tableEnd
                    || !isInBounds(offset, length, archiveLength)) {
                return null;
            }
            byte[] nameField = java.util.Arrays.copyOfRange(
                    directory, entryOffset, entryOffset + nameSize);
            entries.add(new PodArchive.Entry(name, length, offset, nameField));
        }
        return entries;
    }

    /** Returns {@code true} if the name field holds a terminator inside its width. */
    private static boolean isNulTerminated(byte[] bytes, int fieldOffset, int nameSize) {
        for (int i = fieldOffset; i < fieldOffset + nameSize; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code name} looks like a stored archive path:
     * non-empty, free of control characters and drive separators, and short
     * enough to be null-terminated inside a 32-byte field.
     */
    private static boolean isPlausibleArchivePath(String name) {
        if (name.isEmpty() || name.length() > POD1_MAX_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == ':') {
                return false;
            }
        }
        return true;
    }

    /** Overflow-safe test that an entry's byte range lies inside the archive. */
    private static boolean isInBounds(long offset, long length, long fileSize) {
        return offset >= 0 && length >= 0 && offset <= fileSize && length <= fileSize - offset;
    }

    private PodArchive readEpd(PodDataSource source, byte[] head, String path) throws IOException {
        if (source.length() < EPD_TABLE_OFFSET) {
            throw new IOException("File too small to be an EPD archive: " + path);
        }

        String comment = decodeNullTerminated(head, EPD_TITLE_OFFSET, EPD_TITLE_SIZE);
        // The count sits past the 96 bytes already read, so take the fixed header.
        byte[] header = source.readExact(0, EPD_TABLE_OFFSET);
        int itemCount = readInt32LE(header, EPD_COUNT_OFFSET);
        if (itemCount < 1 || itemCount > MAX_REASONABLE_ITEMS) {
            throw new IOException("Suspicious EPD item count: " + itemCount);
        }

        long tableSize = (long) itemCount * EPD_ENTRY_SIZE;
        if (EPD_TABLE_OFFSET + tableSize > source.length()) {
            throw new IOException("EPD item table exceeds file size");
        }

        byte[] table = source.readExact(EPD_TABLE_OFFSET, (int) tableSize);
        List<PodArchive.Entry> entries = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            int entryOffset = i * EPD_ENTRY_SIZE;
            String name = decodeEpdEntryName(table, entryOffset);
            long length = Integer.toUnsignedLong(readInt32LE(table, entryOffset + 64));
            long offset = Integer.toUnsignedLong(readInt32LE(table, entryOffset + 68));
            validateEntryBounds(name, offset, length, source.length());
            entries.add(new PodArchive.Entry(name, length, offset));
        }

        return new PodArchive(PodArchive.Format.EPD, comment, source, entries);
    }

    private PodArchive readPod2(PodDataSource source, byte[] head, String path) throws IOException {
        if (source.length() < POD2_HEADER_SIZE) {
            throw new IOException("File too small to be a POD2 archive: " + path);
        }

        String comment = decodeNullTerminated(head, 8, POD_COMMENT_SIZE);
        long archiveChecksum = Integer.toUnsignedLong(readInt32LE(head, 4));
        int itemCount = readInt32LE(head, POD2_COUNT_OFFSET);
        if (itemCount < 1 || itemCount > MAX_REASONABLE_ITEMS) {
            throw new IOException("Suspicious POD2 item count: " + itemCount);
        }

        // The audit trail is a different quantity from the directory, one record per
        // edit over the archive's lifetime, so it legitimately outnumbers the entries.
        // 4x4 Evolution 2's shipping TRUCK.POD carries 8,430 audit records for 1,126
        // entries. What the count has to satisfy is that the records fit in the file
        // at all; where they sit is checked against the end of the payloads below.
        int auditCount = readInt32LE(head, POD2_AUDIT_COUNT_OFFSET);
        if (auditCount < 0 || (long) auditCount * POD2_AUDIT_SIZE > source.length()) {
            throw new IOException("Suspicious POD2 audit count: " + auditCount);
        }

        long tableSize = (long) itemCount * POD2_ENTRY_SIZE;
        if (POD2_HEADER_SIZE + tableSize > source.length()) {
            throw new IOException("POD2 item table exceeds file size");
        }

        byte[] table = source.readExact(POD2_HEADER_SIZE, (int) tableSize);
        long nameTableOffset = POD2_HEADER_SIZE + tableSize;

        // Names live in a NUL-terminated blob between the entry table and the first
        // payload, so the table itself says where the blob ends.
        long firstPayload = Long.MAX_VALUE;
        long lastPayloadEnd = 0;
        for (int i = 0; i < itemCount; i++) {
            int entryOffset = i * POD2_ENTRY_SIZE;
            long offset = Integer.toUnsignedLong(readInt32LE(table, entryOffset + 8));
            long end = offset + Integer.toUnsignedLong(readInt32LE(table, entryOffset + 4));
            firstPayload = Math.min(firstPayload, offset);
            lastPayloadEnd = Math.max(lastPayloadEnd, end);
        }
        byte[] names = readPod2NameBlob(source, nameTableOffset, firstPayload, itemCount);

        List<PodArchive.Entry> entries = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            int entryOffset = i * POD2_ENTRY_SIZE;
            long pathOffset = Integer.toUnsignedLong(readInt32LE(table, entryOffset));
            long length     = Integer.toUnsignedLong(readInt32LE(table, entryOffset + 4));
            long offset     = Integer.toUnsignedLong(readInt32LE(table, entryOffset + 8));
            long timestamp  = Integer.toUnsignedLong(readInt32LE(table, entryOffset + 12));
            long checksum   = Integer.toUnsignedLong(readInt32LE(table, entryOffset + 16));
            String name = pathOffset >= names.length ? ""
                    : decodeNullTerminated(names, (int) pathOffset, names.length - (int) pathOffset);
            validateEntryBounds(name, offset, length, source.length());
            entries.add(new PodArchive.Entry(name, length, offset, null, timestamp, checksum));
        }

        long auditOffset = lastPayloadEnd;
        long auditBytes = (long) auditCount * POD2_AUDIT_SIZE;
        if (auditOffset < 0 || auditOffset + auditBytes > source.length()) {
            throw new IOException("POD2 audit trail exceeds file size");
        }
        List<PodArchive.AuditEntry> audits = new ArrayList<>(auditCount);
        byte[] records = auditCount == 0 ? new byte[0]
                : source.readExact(auditOffset, Math.toIntExact(auditBytes));
        for (int i = 0; i < auditCount; i++) {
            int at = i * POD2_AUDIT_SIZE;
            String user = decodeNullTerminated(records, at, 32);
            long timestamp = Integer.toUnsignedLong(readInt32LE(records, at + 32));
            int actionValue = readInt32LE(records, at + 36);
            if (actionValue < 0 || actionValue >= PodArchive.AuditAction.values().length) {
                throw new IOException("Invalid POD2 audit action: " + actionValue);
            }
            String entryPath = decodeNullTerminated(records, at + 40, 256);
            long oldTimestamp = Integer.toUnsignedLong(readInt32LE(records, at + 296));
            long oldSize = Integer.toUnsignedLong(readInt32LE(records, at + 300));
            long newTimestamp = Integer.toUnsignedLong(readInt32LE(records, at + 304));
            long newSize = Integer.toUnsignedLong(readInt32LE(records, at + 308));
            audits.add(new PodArchive.AuditEntry(user, timestamp,
                    PodArchive.AuditAction.values()[actionValue], entryPath,
                    oldTimestamp, oldSize, newTimestamp, newSize));
        }

        return new PodArchive(PodArchive.Format.POD2, comment, source, entries,
                null, archiveChecksum, audits);
    }

    /**
     * The POD2 name blob, which runs from the end of the directory to the first
     * payload. When the payload offsets are too damaged to bound it, falls back to
     * the widest blob the entry count could justify rather than reading the archive.
     */
    private static byte[] readPod2NameBlob(PodDataSource source, long nameTableOffset,
            long firstPayload, int itemCount) {
        long end = firstPayload > nameTableOffset && firstPayload <= source.length()
                ? firstPayload
                : Math.min(source.length(), nameTableOffset + (long) itemCount * POD2_MAX_NAME_SIZE);
        long size = end - nameTableOffset;
        return size <= 0 ? new byte[0] : source.readExact(nameTableOffset, Math.toIntExact(size));
    }

    private static boolean hasMagic(byte[] bytes, byte[] magic) {
        if (bytes.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static void validateEntryBounds(String name, long offset, long length, long fileSize)
            throws IOException {
        if (!isInBounds(offset, length, fileSize)) {
            throw new IOException("POD entry exceeds file size: " + name);
        }
    }

    /**
     * EPD splits a name across a 4-byte prefix and a 60-byte suffix. When the suffix
     * opens with a backslash and the prefix looks like a directory name, the two are
     * joined; otherwise the suffix alone is the name.
     */
    private static String decodeEpdEntryName(byte[] bytes, int entryOffset) {
        String suffix = decodeNullTerminated(bytes, entryOffset + 4, 60);
        String prefix = decodeNullTerminated(bytes, entryOffset, 4);
        if (!suffix.isEmpty() && suffix.charAt(0) == '\\' && isLikelyPathPrefix(prefix)) {
            return prefix + suffix;
        }
        if (!suffix.isEmpty()) {
            return suffix;
        }
        return decodeNullTerminated(bytes, entryOffset, 64);
    }

    private static boolean isLikelyPathPrefix(String prefix) {
        if (prefix.isEmpty()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (!(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && c != '_') {
                return false;
            }
        }
        return true;
    }

    /** Reads a 32-bit little-endian integer from {@code bytes[offset]}. */
    private static int readInt32LE(byte[] bytes, int offset) {
        return (bytes[offset]     & 0xFF)
             | ((bytes[offset + 1] & 0xFF) <<  8)
             | ((bytes[offset + 2] & 0xFF) << 16)
             | ((bytes[offset + 3] & 0xFF) << 24);
    }

    /**
     * Decodes a fixed-length, null-terminated ISO-8859-1 string from a byte array,
     * trimming whitespace. An out-of-range window yields an empty string.
     */
    private static String decodeNullTerminated(byte[] bytes, int offset, int length) {
        if (offset < 0 || offset >= bytes.length || length <= 0) {
            return "";
        }
        int end   = offset;
        int limit = (int) Math.min((long) offset + length, bytes.length);
        while (end < limit && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, LEGACY_CHARSET).trim();
    }
}
