package com.mtm2.jpod.io.pod;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a Terminal Reality POD archive from disk into a {@link PodArchive}.
 *
 * <p>The entire file is loaded into memory with {@link Files#readAllBytes} so
 * that individual entry bytes can later be sliced cheaply without additional
 * I/O.  This is appropriate for the typical POD file sizes encountered in
 * Terminal Reality titles (&lt; 100 MB).
 *
 * <p>Validation performed:
 * <ul>
 *   <li>Minimum header size check (4-byte count + 80-byte comment)</li>
 *   <li>Sanity-range check on item count (1 – {@value #MAX_REASONABLE_ITEMS})</li>
 *   <li>Directory table bounds check</li>
 *   <li>Per-entry data bounds check</li>
 * </ul>
 *
 * <p>POD version 1 has no magic value, so the directory layout is detected by
 * validating it. The classic 40-byte record is tried first and the 72-byte
 * POD1-64 record only if the classic table fails to validate, which keeps
 * ordinary archives from being reported as extended.
 */
public final class PodArchiveReader {

    private static final Charset LEGACY_CHARSET = StandardCharsets.ISO_8859_1;
    private static final byte[] EPD_MAGIC  = {'d', 't', 'x', 'e'};
    private static final byte[] POD2_MAGIC = {'P', 'O', 'D', '2'};
    private static final int POD_COMMENT_SIZE    = 80;
    private static final int POD_ENTRY_NAME_SIZE = 32;
    private static final int POD1_ENTRY_SIZE     = 40;
    /** POD1-64 widens the directory name field to 64 bytes, giving 72-byte records. */
    private static final int POD1_64_NAME_SIZE   = 64;
    private static final int POD1_64_ENTRY_SIZE  = 72;
    private static final int POD2_ENTRY_SIZE     = 20;
    private static final int EPD_ENTRY_SIZE      = 80;
    private static final int POD1_HEADER_SIZE    = Integer.BYTES + POD_COMMENT_SIZE;
    private static final int POD2_HEADER_SIZE    = 8 + POD_COMMENT_SIZE + Integer.BYTES + Integer.BYTES;
    private static final int EPD_COUNT_OFFSET    = 0x90;
    private static final int EPD_TABLE_OFFSET    = 0x110;
    private static final int EPD_TITLE_OFFSET    = 4;
    private static final int EPD_TITLE_SIZE      = 4;
    /** Upper sanity limit; Hellbender {@code GAME.POD} exceeds 4 k entries. */
    private static final int MAX_REASONABLE_ITEMS = 8192;

    /**
     * Reads the POD archive at {@code path} and returns it as an in-memory
     * {@link PodArchive}.
     *
     * @param path path to a {@code .pod} file
     * @return parsed archive with all entry metadata and raw bytes loaded
     * @throws IOException if the file cannot be read, is too small, has a
     *                     suspicious item count, or contains out-of-bounds entries
     */
    public PodArchive read(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < POD1_HEADER_SIZE) {
            throw new IOException("File too small to be a POD archive: " + path);
        }

        if (hasMagic(bytes, EPD_MAGIC)) {
            return readEpd(bytes, path);
        }
        if (hasMagic(bytes, POD2_MAGIC)) {
            return readPod2(bytes, path);
        }
        return readPod1(bytes, path);
    }

    private PodArchive readPod1(byte[] bytes, Path path) throws IOException {
        int itemCount = readInt32LE(bytes, 0);
        if (itemCount < 1 || itemCount > MAX_REASONABLE_ITEMS) {
            throw new IOException("Suspicious POD item count: " + itemCount);
        }

        String comment = decodeNullTerminated(bytes, Integer.BYTES, POD_COMMENT_SIZE);

        List<PodArchive.Entry> classic =
                tryReadPod1Directory(bytes, itemCount, POD_ENTRY_NAME_SIZE, POD1_ENTRY_SIZE);
        if (classic != null) {
            return new PodArchive(PodArchive.Format.POD1, comment, bytes, classic);
        }

        List<PodArchive.Entry> extended =
                tryReadPod1Directory(bytes, itemCount, POD1_64_NAME_SIZE, POD1_64_ENTRY_SIZE);
        if (extended != null) {
            return new PodArchive(PodArchive.Format.POD1_64, comment, bytes, extended);
        }

        throw new IOException(
                "POD1 directory is neither a valid 40-byte nor 72-byte layout: " + path);
    }

    /**
     * Attempts to read the whole POD1 directory table with one record layout.
     *
     * <p>The table is accepted only if every record decodes to a plausible
     * non-empty archive path whose data range lies inside the file, which is
     * what lets the classic and POD1-64 layouts be told apart without a magic
     * value.
     *
     * @param bytes     complete archive contents
     * @param itemCount directory entry count from the header
     * @param nameSize  width of the name field, 32 (classic) or 64 (POD1-64)
     * @param entrySize width of a whole record, 40 (classic) or 72 (POD1-64)
     * @return the parsed entries, or {@code null} if this layout does not validate
     */
    private static List<PodArchive.Entry> tryReadPod1Directory(
            byte[] bytes, int itemCount, int nameSize, int entrySize) {
        long tableSize = (long) itemCount * entrySize;
        if (POD1_HEADER_SIZE + tableSize > bytes.length) {
            return null;
        }

        List<PodArchive.Entry> entries = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            int entryOffset = POD1_HEADER_SIZE + i * entrySize;
            String name   = decodeNullTerminated(bytes, entryOffset, nameSize);
            long   length = Integer.toUnsignedLong(readInt32LE(bytes, entryOffset + nameSize));
            long   offset = Integer.toUnsignedLong(
                    readInt32LE(bytes, entryOffset + nameSize + Integer.BYTES));
            if (!isPlausibleArchivePath(name) || !isInBounds(offset, length, bytes.length)) {
                return null;
            }
            entries.add(new PodArchive.Entry(name, length, offset));
        }
        return entries;
    }

    /**
     * Returns {@code true} if {@code name} looks like a stored archive path:
     * non-empty, free of control characters and drive separators, and short
     * enough to be null-terminated inside a 64-byte field.
     */
    private static boolean isPlausibleArchivePath(String name) {
        if (name.isEmpty() || name.length() > POD1_64_NAME_SIZE - 1) {
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
    private static boolean isInBounds(long offset, long length, int fileSize) {
        return offset >= 0 && length >= 0 && offset <= fileSize && length <= fileSize - offset;
    }

    private PodArchive readEpd(byte[] bytes, Path path) throws IOException {
        if (bytes.length < EPD_TABLE_OFFSET) {
            throw new IOException("File too small to be an EPD archive: " + path);
        }

        String comment = decodeNullTerminated(bytes, EPD_TITLE_OFFSET, EPD_TITLE_SIZE);
        int itemCount = readInt32LE(bytes, EPD_COUNT_OFFSET);
        if (itemCount < 1 || itemCount > MAX_REASONABLE_ITEMS) {
            throw new IOException("Suspicious EPD item count: " + itemCount);
        }

        int tableSize = Math.multiplyExact(itemCount, EPD_ENTRY_SIZE);
        if (EPD_TABLE_OFFSET + tableSize > bytes.length) {
            throw new IOException("EPD item table exceeds file size");
        }

        List<PodArchive.Entry> entries = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            int entryOffset = EPD_TABLE_OFFSET + i * EPD_ENTRY_SIZE;
            String name = decodeEpdEntryName(bytes, entryOffset);
            long length = Integer.toUnsignedLong(readInt32LE(bytes, entryOffset + 64));
            long offset = Integer.toUnsignedLong(readInt32LE(bytes, entryOffset + 68));
            validateEntryBounds(name, offset, length, bytes.length);
            entries.add(new PodArchive.Entry(name, length, offset));
        }

        return new PodArchive(PodArchive.Format.EPD, comment, bytes, entries);
    }

    private PodArchive readPod2(byte[] bytes, Path path) throws IOException {
        if (bytes.length < POD2_HEADER_SIZE) {
            throw new IOException("File too small to be a POD2 archive: " + path);
        }

        String comment = decodeNullTerminated(bytes, 8, POD_COMMENT_SIZE);
        int itemCount = readInt32LE(bytes, 88);
        if (itemCount < 1 || itemCount > MAX_REASONABLE_ITEMS) {
            throw new IOException("Suspicious POD2 item count: " + itemCount);
        }

        int tableOffset = POD2_HEADER_SIZE;
        int tableSize   = Math.multiplyExact(itemCount, POD2_ENTRY_SIZE);
        if (tableOffset + tableSize > bytes.length) {
            throw new IOException("POD2 item table exceeds file size");
        }

        int nameTableOffset = tableOffset + tableSize;
        List<PodArchive.Entry> entries = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            int entryOffset = tableOffset + i * POD2_ENTRY_SIZE;
            int pathOffset  = readInt32LE(bytes, entryOffset);
            long length     = Integer.toUnsignedLong(readInt32LE(bytes, entryOffset + 4));
            long offset     = Integer.toUnsignedLong(readInt32LE(bytes, entryOffset + 8));
            String name     = decodeNullTerminated(bytes, nameTableOffset + pathOffset,
                    bytes.length - (nameTableOffset + pathOffset));
            validateEntryBounds(name, offset, length, bytes.length);
            entries.add(new PodArchive.Entry(name, length, offset));
        }

        return new PodArchive(PodArchive.Format.POD2, comment, bytes, entries);
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

    private static void validateEntryBounds(String name, long offset, long length, int fileSize)
            throws IOException {
        if (!isInBounds(offset, length, fileSize)) {
            throw new IOException("POD entry exceeds file size: " + name);
        }
    }

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

    /** Reads a 32-bit unsigned little-endian integer from {@code bytes[offset]}. */
    private static int readInt32LE(byte[] bytes, int offset) {
        return (bytes[offset]     & 0xFF)
             | ((bytes[offset + 1] & 0xFF) <<  8)
             | ((bytes[offset + 2] & 0xFF) << 16)
             | ((bytes[offset + 3] & 0xFF) << 24);
    }

    /**
     * Decodes a fixed-length, null-terminated ISO-8859-1 string from a byte
     * array, trimming trailing whitespace.
     *
     * @param bytes  source buffer
     * @param offset start of the field
     * @param length maximum field length in bytes
     * @return decoded string up to (but not including) the first null byte
     */
    private static String decodeNullTerminated(byte[] bytes, int offset, int length) {
        if (offset < 0 || offset >= bytes.length || length <= 0) {
            return "";
        }
        int end   = offset;
        int limit = Math.min(offset + length, bytes.length);
        while (end < limit && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, LEGACY_CHARSET).trim();
    }
}
