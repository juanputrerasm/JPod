package com.mtm2.jpod.io.pod;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Immutable in-memory representation of a Terminal Reality POD archive.
 *
 * <p>POD binary layout (all integers little-endian):
 * <pre>
 *   Offset   Size   Description
 *   ------   ----   -----------
 *        0      4   Item count (int32)
 *        4     80   Archive comment (null-terminated ISO-8859-1 string)
 *       84  N x 40  Directory table, one {@link Entry} per item:
 *                     +  0  32 bytes  Entry name (null-padded)
 *                     + 32   4 bytes  Data length (uint32)
 *                     + 36   4 bytes  Data offset from file start (uint32)
 *   84+Nx40  ...    Raw file data, concatenated in directory order
 * </pre>
 *
 * <p>That 40-byte record is the only POD1 directory layout. A directory table
 * that does not validate as one is a malformed archive and is refused.
 *
 * <p>Instances are created exclusively by {@link PodArchiveReader}. Only the
 * header and directory are held in memory; payloads stay in the
 * {@link PodDataSource} until {@link #getEntryBytes(Entry)} or
 * {@link #copyEntryTo} asks for them. An archive read from disk keeps its file
 * open, so close it when done.
 */
public final class PodArchive implements AutoCloseable {

    public enum Format {
        /** Classic POD version 1: 84-byte header, 40-byte directory records, 31-byte names. */
        POD1("POD1"),
        POD2("POD2"),
        EPD("EPD");

        private final String displayName;

        Format(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        @Override public String toString() { return displayName; }
    }

    private final Format format;
    private final String comment;
    private final PodDataSource data;
    private final List<Entry> entries;
    private final byte[] commentField;
    private final long checksum;
    private final List<AuditEntry> auditEntries;

    /**
     * Name lookups, built on first use. An archive with thousands of entries is
     * searched repeatedly while previewing art and models, and a linear scan there
     * costs a title computation per entry per lookup.
     */
    private volatile Map<String, Entry> byName;
    private volatile Map<String, Entry> byTitle;

    PodArchive(Format format, String comment, PodDataSource data, List<Entry> entries) {
        this(format, comment, data, entries, null);
    }

    PodArchive(Format format, String comment, PodDataSource data, List<Entry> entries,
            byte[] commentField) {
        this(format, comment, data, entries, commentField, 0, List.of());
    }

    PodArchive(Format format, String comment, PodDataSource data, List<Entry> entries,
            byte[] commentField, long checksum, List<AuditEntry> auditEntries) {
        this.format = Objects.requireNonNull(format);
        this.comment = Objects.requireNonNull(comment);
        this.data = Objects.requireNonNull(data);
        this.entries = List.copyOf(entries);
        this.commentField = commentField;
        this.checksum = checksum;
        this.auditEntries = List.copyOf(auditEntries);
    }

    /**
     * Returns the 80-byte comment field exactly as read, or {@code null} when the
     * archive did not come from a POD1 header. Like a directory name field it can
     * hold bytes after the terminator, so it is kept for writing back verbatim.
     */
    public byte[] getCommentField() {
        return commentField;
    }

    public Format getFormat() {
        return format;
    }

    /** Returns {@code true} for POD version 1 archives. */
    public boolean isPod1Family() {
        return format == Format.POD1;
    }

    /** Returns the 80-character archive comment from the POD header (trimmed). */
    public String getComment() {
        return comment;
    }

    /** Returns an unmodifiable list of all directory entries in declaration order. */
    public List<Entry> getEntries() {
        return entries;
    }

    /** POD2 archive checksum, or zero for formats that do not carry one. */
    public long getChecksum() { return checksum; }

    /** POD2 audit trail in declaration order; empty for POD1 and EPD. */
    public List<AuditEntry> getAuditEntries() { return auditEntries; }

    /**
     * The bytes behind the archive. Held open for as long as the archive is, so
     * callers must close the archive when they are done with it.
     */
    public PodDataSource getData() { return data; }

    /**
     * True when the POD2 checksum over bytes 8..EOF matches the header. Streams the
     * file rather than holding it; non-POD2 archives carry no checksum and pass.
     */
    public boolean isChecksumValid() {
        return format != Format.POD2
                || checksum == PodArchiveWriter.crc32Mpeg2(data, 8, data.length() - 8);
    }

    /** True when a POD2 entry's stored CRC matches its payload, streamed. */
    public boolean isEntryChecksumValid(Entry entry) {
        return format != Format.POD2
                || entry.checksum() == PodArchiveWriter.crc32Mpeg2(data, entry.offset(), entry.length());
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
        Map<String, Entry> index = byName;
        if (index == null) {
            index = buildIndex(Entry::name);
            byName = index;
        }
        return Optional.ofNullable(index.get(name.toUpperCase(Locale.ROOT)));
    }

    /**
     * Finds an entry whose bare filename (without leading path) matches {@code name}
     * (case-insensitive).
     *
     * @param name bare filename, e.g. {@code "DEMO1.RAW"}
     */
    public Optional<Entry> findEntryByTitle(String name) {
        Map<String, Entry> index = byTitle;
        if (index == null) {
            index = buildIndex(Entry::title);
            byTitle = index;
        }
        return Optional.ofNullable(index.get(name.toUpperCase(Locale.ROOT)));
    }

    /**
     * Indexes the entries by one of their names, upper-cased. Shipped and community
     * archives do repeat a name, so the first occurrence is kept, matching what a
     * linear scan returns.
     */
    private Map<String, Entry> buildIndex(Function<Entry, String> key) {
        Map<String, Entry> index = new HashMap<>(entries.size() * 2);
        for (Entry entry : entries) {
            index.putIfAbsent(key.apply(entry).toUpperCase(Locale.ROOT), entry);
        }
        return index;
    }

    /**
     * Returns a single entry's payload, read from the archive on demand. Bounds were
     * validated at parse time, so the range is always inside the archive.
     *
     * <p>Prefer {@link #copyEntryTo} when the bytes are only going to be written
     * somewhere: this materializes the whole payload as one array.
     *
     * @param entry a directory entry belonging to this archive
     * @return copy of the entry's data bytes
     * @throws ArithmeticException if the length overflows {@code int}
     */
    public byte[] getEntryBytes(Entry entry) {
        return data.readExact(entry.offset(), Math.toIntExact(entry.length()));
    }

    /** Streams an entry's payload to {@code target} without holding it in memory. */
    public void copyEntryTo(Entry entry, OutputStream target, byte[] scratch) throws IOException {
        data.copyTo(entry.offset(), entry.length(), target, scratch);
    }

    /** Releases the archive's file handle. */
    @Override
    public void close() {
        data.close();
    }

    /**
     * One entry in the POD directory table.
     *
     * @param name      archive path of the entry (null-bytes stripped, ISO-8859-1)
     * @param length    data length in bytes
     * @param offset    byte offset of the data from the start of the POD file
     * @param nameField the directory name field exactly as read, terminator and
     *                  trailing bytes included, or {@code null} for entries that
     *                  did not come from a POD1 directory
     */
    public record Entry(String name, long length, long offset, byte[] nameField,
                        long timestamp, long checksum) {

        /** An entry with no original directory field, for synthetic use. */
        public Entry(String name, long length, long offset) {
            this(name, length, offset, null, 0, 0);
        }

        /** A POD1 entry retaining its original fixed-width directory field. */
        public Entry(String name, long length, long offset, byte[] nameField) {
            this(name, length, offset, nameField, 0, 0);
        }

        /**
         * Returns the palette this entry's art was authored against, read from the
         * second string in the directory field, or {@code null} when there is none.
         *
         * <p>The early Terminal Reality packer wrote it for every {@code .RAW}
         * entry in MTM1, Terminal Velocity, Fury3, and Hellbender; MTM2 and CPR
         * dropped the practice. Where it is present it is authoritative, and it
         * names an {@code .ACT} that exists in the same archive.
         */
        public String embeddedPaletteName() {
            return secondString(nameField);
        }

        /**
         * Returns the second null-terminated string in a directory name field, or
         * {@code null} when the field holds only a path.
         *
         * <p>Nothing upstream documents this string, and no reader here looks past
         * the first terminator when locating entries. It is a hint for previewing
         * art, nothing more.
         */
        public static String secondString(byte[] field) {
            if (field == null) {
                return null;
            }
            int first = -1;
            for (int i = 0; i < field.length; i++) {
                if (field[i] == 0) { first = i; break; }
            }
            if (first < 0 || first + 1 >= field.length) {
                return null;
            }
            int start = first + 1;
            int end = start;
            while (end < field.length && field[end] != 0) {
                end++;
            }
            if (end == start) {
                return null;
            }
            String value = new String(field, start, end - start,
                    java.nio.charset.StandardCharsets.ISO_8859_1).trim();
            return value.isEmpty() ? null : value;
        }

        /**
         * Returns the bare filename (no leading path) in upper-case.
         * For example {@code "ART\DEMO1.RAW"} returns {@code "DEMO1.RAW"}.
         */
        public String title() {
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            return (slash >= 0 ? name.substring(slash + 1) : name).toUpperCase(Locale.ROOT);
        }
    }

    public enum AuditAction { ADD, REMOVE, CHANGE }

    /** One 312-byte POD2 audit record. Times are unsigned Unix seconds. */
    public record AuditEntry(String user, long timestamp, AuditAction action,
                             String entryPath, long oldTimestamp, long oldSize,
                             long newTimestamp, long newSize) {}
}
