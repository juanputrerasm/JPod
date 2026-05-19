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
 */
public final class PodArchiveReader {

    private static final Charset LEGACY_CHARSET = StandardCharsets.ISO_8859_1;
    private static final int POD_COMMENT_SIZE    = 80;
    private static final int POD_ENTRY_NAME_SIZE = 32;
    private static final int POD_ENTRY_SIZE      = 40;
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
        if (bytes.length < Integer.BYTES + POD_COMMENT_SIZE) {
            throw new IOException("File too small to be a POD archive: " + path);
        }

        int itemCount = readInt32LE(bytes, 0);
        if (itemCount < 1 || itemCount > MAX_REASONABLE_ITEMS) {
            throw new IOException("Suspicious POD item count: " + itemCount);
        }

        int tableOffset = Integer.BYTES + POD_COMMENT_SIZE;
        int tableSize   = Math.multiplyExact(itemCount, POD_ENTRY_SIZE);
        if (tableOffset + tableSize > bytes.length) {
            throw new IOException("POD item table exceeds file size");
        }

        String comment = decodeNullTerminated(bytes, Integer.BYTES, POD_COMMENT_SIZE);
        List<PodArchive.Entry> entries = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            int entryOffset = tableOffset + i * POD_ENTRY_SIZE;
            String name   = decodeNullTerminated(bytes, entryOffset, POD_ENTRY_NAME_SIZE);
            long   length = Integer.toUnsignedLong(
                    readInt32LE(bytes, entryOffset + POD_ENTRY_NAME_SIZE));
            long   offset = Integer.toUnsignedLong(
                    readInt32LE(bytes, entryOffset + POD_ENTRY_NAME_SIZE + Integer.BYTES));
            if (offset + length > bytes.length) {
                throw new IOException("POD entry exceeds file size: " + name);
            }
            entries.add(new PodArchive.Entry(name, length, offset));
        }

        return new PodArchive(comment, bytes, entries);
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
        int end   = offset;
        int limit = offset + length;
        while (end < limit && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, LEGACY_CHARSET).trim();
    }
}
