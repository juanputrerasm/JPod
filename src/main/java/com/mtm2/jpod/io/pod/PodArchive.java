package com.mtm2.jpod.io.pod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable in-memory representation of a Terminal Reality POD archive.
 *
 * <p>POD binary layout (all integers little-endian):
 * <pre>
 *   Offset   Size   Description
 *   ------   ----   -----------
 *        0      4   Item count (uint32)
 *        4     80   Archive comment (null-terminated ISO-8859-1 string)
 *       84  N × 40  Directory table — one {@link Entry} per item:
 *                     +  0  32 bytes  Entry name (null-padded)
 *                     + 32   4 bytes  Data length (uint32)
 *                     + 36   4 bytes  Data offset from file start (uint32)
 *   84+N×40   …    Raw file data, concatenated in directory order
 * </pre>
 *
 * <p>Instances are created exclusively by {@link PodArchiveReader}. The raw
 * file bytes are kept in memory so that individual entry data can be sliced
 * out cheaply via {@link #getEntryBytes(Entry)}.
 */
public final class PodArchive {

    public enum Format {
        POD1("POD"),
        POD2("POD2"),
        EPD("EPD");

        private final String displayName;

        Format(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private final Format format;
    private final String comment;
    private final byte[] bytes;
    private final List<Entry> entries;

    PodArchive(Format format, String comment, byte[] bytes, List<Entry> entries) {
        this.format = Objects.requireNonNull(format);
        this.comment = Objects.requireNonNull(comment);
        this.bytes = Objects.requireNonNull(bytes);
        this.entries = List.copyOf(entries);
    }

    public Format getFormat() {
        return format;
    }

    /** Returns the 80-character archive comment from the POD header (trimmed). */
    public String getComment() {
        return comment;
    }

    /** Returns an unmodifiable list of all directory entries in declaration order. */
    public List<Entry> getEntries() {
        return entries;
    }

    /**
     * Returns all entries whose name ends with {@code extension} (case-insensitive).
     *
     * @param extension file extension including the dot, e.g. {@code ".raw"}
     */
    public List<Entry> getEntriesByExtension(String extension) {
        String normalized = extension.toUpperCase(Locale.ROOT);
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.name().toUpperCase(Locale.ROOT).endsWith(normalized)) {
                matches.add(entry);
            }
        }
        return matches;
    }

    /**
     * Finds a single entry by its full archive name (case-insensitive).
     *
     * @param name archive entry name, e.g. {@code "ART\\DEMO1.RAW"} or {@code "demo1.raw"}
     * @return the matching entry, or empty if not found
     */
    public Optional<Entry> findEntry(String name) {
        String normalized = name.toUpperCase(Locale.ROOT);
        for (Entry entry : entries) {
            if (entry.name().toUpperCase(Locale.ROOT).equals(normalized)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /**
     * Finds an entry whose bare filename (without leading path) matches {@code name}
     * (case-insensitive).
     *
     * @param name bare filename, e.g. {@code "DEMO1.RAW"}
     */
    public Optional<Entry> findEntryByTitle(String name) {
        String normalized = name.toUpperCase(Locale.ROOT);
        for (Entry entry : entries) {
            if (entry.title().equals(normalized)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns a copy of the raw bytes for a single entry.
     *
     * <p>The slice is taken directly from the in-memory archive bytes using
     * {@link Entry#offset()} and {@link Entry#length()}.
     *
     * @param entry a directory entry belonging to this archive
     * @return copy of the entry's data bytes
     * @throws ArithmeticException if offset or length overflows {@code int}
     */
    public byte[] getEntryBytes(Entry entry) {
        int start = Math.toIntExact(entry.offset());
        int end   = Math.toIntExact(entry.offset() + entry.length());
        return java.util.Arrays.copyOfRange(bytes, start, end);
    }

    /**
     * One entry in the POD directory table.
     *
     * @param name    archive path of the entry (null-bytes stripped, ISO-8859-1)
     * @param length  data length in bytes
     * @param offset  byte offset of the data from the start of the POD file
     */
    public record Entry(String name, long length, long offset) {

        /**
         * Returns the bare filename (no leading path) in upper-case.
         * For example {@code "ART\DEMO1.RAW"} returns {@code "DEMO1.RAW"}.
         */
        public String title() {
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            return (slash >= 0 ? name.substring(slash + 1) : name).toUpperCase(Locale.ROOT);
        }
    }
}
