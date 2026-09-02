package com.mtm2.jpod.io.pod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds and writes Terminal Reality POD archives from an in-memory blob list.
 *
 * <p>The binary layout produced is:
 * <pre>
 *   4 bytes   item count (int32 LE)
 *  80 bytes   comment, null-padded (ISO-8859-1, truncated to 79 usable chars)
 *  N × 40     directory table — for each entry:
 *               32 bytes  name, null-padded (ISO-8859-1)
 *                4 bytes  data length (uint32 LE)
 *                4 bytes  data offset from file start (uint32 LE)
 *   …         raw file data concatenated in directory order
 * </pre>
 *
 * <p>When at least one entry name does not fit in the classic 31-byte budget,
 * the POD1-64 directory is emitted instead: the header is unchanged, but the
 * name field is 64 bytes wide and each record is therefore 72 bytes. Classic
 * POD1 is preferred whenever every name fits, because extended archives can
 * only be opened by updated engines and tools.
 *
 * <p>Entry names are written exactly as supplied; callers are responsible for
 * uppercasing if the target game requires it.
 */
public final class PodArchiveWriter {

    private static final int COMMENT_SIZE    = 80;
    private static final int ENTRY_NAME_SIZE = 32;
    private static final int ENTRY_SIZE      = 40;
    /** POD1-64 name field; the extra 32 bytes make each record 72 bytes wide. */
    private static final int LONG_NAME_SIZE  = 64;
    private static final int POD2_HEADER_SIZE = 96;
    private static final int POD2_ENTRY_SIZE = 20;
    /**
     * Matches the reader's limit, so anything JPod can open it can also save.
     * Hellbender's {@code GAME.POD} holds 4 341 entries and used to read fine but
     * fail on save against the old 4 096 cap.
     */
    private static final int MAX_ITEMS       = 8192;

    /** Longest entry name the POD1-64 directory can store, excluding the terminator. */
    public static final int MAX_NAME_LENGTH  = LONG_NAME_SIZE - 1;

    /**
     * Builds the archive bytes and writes them atomically to {@code path}.
     *
     * <p>Parent directories are created if absent.
     *
     * @param path    destination file (created or overwritten)
     * @param comment up to 79-byte archive comment (rejected if longer)
     * @param blobs   ordered list of entries to pack; must not be empty
     * @return the directory layout written, {@link PodArchive.Format#POD1} or
     *         {@link PodArchive.Format#POD1_64}
     * @throws IllegalArgumentException if {@code blobs} is null or empty
     * @throws IOException              if any entry count exceeds {@value MAX_ITEMS},
     *                                  if a name is longer than
     *                                  {@value #MAX_NAME_LENGTH} bytes, on offset
     *                                  overflow, or on write failure
     */
    public PodArchive.Format write(Path path, String comment, List<Blob> blobs) throws IOException {
        return write(path, comment, blobs,
                new WriteOptions(PodArchive.Format.POD1, null, List.of()));
    }

    /**
     * As {@link #write(Path, String, List)}, but writing {@code commentField} back
     * verbatim when it is supplied and the comment text still matches it.
     */
    public PodArchive.Format write(Path path, String comment, List<Blob> blobs, byte[] commentField)
            throws IOException {
        return write(path, comment, blobs,
                new WriteOptions(PodArchive.Format.POD1, commentField, List.of()));
    }

    /** Writes an explicitly selected POD1-family or POD2 archive. */
    public PodArchive.Format write(Path path, String comment, List<Blob> blobs,
            WriteOptions options) throws IOException {
        PodArchive.Format actual = actualFormat(blobs, options.format());
        byte[] bytes = buildBytes(comment, blobs, options);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, bytes);
        return actual;
    }

    /**
     * Returns the directory layout that {@link #buildBytes} would use for
     * {@code blobs}: {@link PodArchive.Format#POD1_64} if any name needs more
     * than the classic 31-byte budget, otherwise {@link PodArchive.Format#POD1}.
     *
     * <p>The whole stored path counts, including any {@code ART\} or
     * {@code MODELS\} prefix, the extension, and the null terminator.
     */
    public static PodArchive.Format formatFor(List<Blob> blobs) {
        return actualFormat(blobs, PodArchive.Format.POD1);
    }

    /** Resolves automatic POD1-64 promotion while respecting an explicit format. */
    public static PodArchive.Format actualFormat(List<Blob> blobs, PodArchive.Format requested) {
        if (requested == PodArchive.Format.POD2) return requested;
        if (requested != PodArchive.Format.POD1 && requested != PodArchive.Format.POD1_64) {
            throw new IllegalArgumentException("Unsupported output format: " + requested);
        }
        if (requested == PodArchive.Format.POD1_64) return requested;
        for (Blob blob : blobs) {
            if (requiredNameFieldLength(blob) > ENTRY_NAME_SIZE) {
                return PodArchive.Format.POD1_64;
            }
        }
        return PodArchive.Format.POD1;
    }

    /**
     * Builds and returns the complete POD binary without writing to disk.
     *
     * <p>Offsets in the directory table are computed automatically; the caller
     * does not need to pre-calculate them.
     *
     * <p>The classic 40-byte directory is used when every name fits in 31
     * bytes; otherwise the 72-byte POD1-64 directory is used.
     *
     * @param comment up to 79-character archive comment
     * @param blobs   entries to include; order determines directory and data layout
     * @return complete POD file contents as a byte array
     * @throws IllegalArgumentException if {@code blobs} is null or empty
     * @throws IOException              if entry count exceeds {@value MAX_ITEMS},
     *                                  if a name is longer than
     *                                  {@value #MAX_NAME_LENGTH} bytes, or if the
     *                                  total size overflows {@code int}
     */
    public byte[] buildBytes(String comment, List<Blob> blobs) throws IOException {
        return buildBytes(comment, blobs,
                new WriteOptions(PodArchive.Format.POD1, null, List.of()));
    }

    /**
     * As {@link #buildBytes(String, List)}, but writing {@code commentField} back
     * verbatim when it is supplied and the comment text still matches it.
     */
    public byte[] buildBytes(String comment, List<Blob> blobs, byte[] commentField) throws IOException {
        return buildBytes(comment, blobs,
                new WriteOptions(PodArchive.Format.POD1, commentField, List.of()));
    }

    /** Builds POD1/POD1-64 or POD2 according to explicit write options. */
    public byte[] buildBytes(String comment, List<Blob> blobs, WriteOptions options) throws IOException {
        validateInputs(comment, blobs, options.allowDuplicateNames());
        PodArchive.Format actual = actualFormat(blobs, options.format());
        if (actual == PodArchive.Format.POD2) {
            return buildPod2(comment, blobs, options.auditEntries());
        }
        return buildPod1(comment, blobs, options.commentField(), actual);
    }

    private byte[] buildPod1(String comment, List<Blob> blobs, byte[] commentField,
            PodArchive.Format actual) throws IOException {
        int nameSize = actual == PodArchive.Format.POD1_64 ? LONG_NAME_SIZE : ENTRY_NAME_SIZE;
        for (Blob blob : blobs) {
            if (requiredNameFieldLength(blob) > nameSize) {
                throw new IOException("POD entry name/palette exceeds " + (nameSize - 1)
                        + " usable bytes: " + blob.name());
            }
        }
        int entrySize  = nameSize + Integer.BYTES + Integer.BYTES;
        int headerSize = Math.addExact(Integer.BYTES + COMMENT_SIZE,
                Math.multiplyExact(blobs.size(), entrySize));
        int[] offsets = offsetsFor(blobs, headerSize);

        int total = headerSize;
        for (Blob blob : blobs) total = Math.addExact(total, blob.data().length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        writeInt32(out, blobs.size());
        writeCommentField(out, comment, commentField);
        for (int i = 0; i < blobs.size(); i++) {
            writeNameField(out, blobs.get(i), nameSize);
            writeInt32(out, blobs.get(i).data().length);
            writeInt32(out, offsets[i]);
        }
        for (Blob blob : blobs) out.writeBytes(blob.data());
        return out.toByteArray();
    }

    private byte[] buildPod2(String comment, List<Blob> blobs,
            List<PodArchive.AuditEntry> audits) throws IOException {
        if (audits.size() > MAX_ITEMS) throw new IOException("Too many POD2 audit records: " + audits.size());
        for (PodArchive.AuditEntry audit : audits) validateAudit(audit);
        ByteArrayOutputStream names = new ByteArrayOutputStream();
        int[] nameOffsets = new int[blobs.size()];
        for (int i = 0; i < blobs.size(); i++) {
            nameOffsets[i] = names.size();
            byte[] name = blobs.get(i).name().getBytes(StandardCharsets.ISO_8859_1);
            names.writeBytes(name);
            names.write(0);
        }
        int dataStart = Math.addExact(POD2_HEADER_SIZE,
                Math.addExact(Math.multiplyExact(blobs.size(), POD2_ENTRY_SIZE), names.size()));
        int[] offsets = offsetsFor(blobs, dataStart);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'P', 'O', 'D', '2'});
        writeInt32(out, 0);
        writeFixedStrict(out, comment, COMMENT_SIZE, "POD2 comment");
        writeInt32(out, blobs.size());
        writeInt32(out, audits.size());
        for (int i = 0; i < blobs.size(); i++) {
            Blob blob = blobs.get(i);
            writeInt32(out, nameOffsets[i]);
            writeInt32(out, blob.data().length);
            writeInt32(out, offsets[i]);
            writeInt32(out, (int) blob.timestamp());
            writeInt32(out, (int) crc32Mpeg2(blob.data()));
        }
        out.writeBytes(names.toByteArray());
        for (Blob blob : blobs) out.writeBytes(blob.data());
        for (PodArchive.AuditEntry audit : audits) writeAudit(out, audit);
        byte[] result = out.toByteArray();
        writeInt32At(result, 4, (int) crc32Mpeg2(result, 8, result.length - 8));
        return result;
    }

    /**
     * Rejects what the directory cannot encode, plus unsafe paths and, unless
     * {@code allowDuplicateNames}, names that collide case-insensitively.
     *
     * <p>Duplicates are a genuine authoring mistake but not a malformed archive:
     * shipped and community PODs repeat a name so the later copy shadows the
     * earlier one. Refusing to write them would make an opened archive unsavable,
     * so the caller grandfathers the ones it read in and {@code PodArchiveValidator}
     * reports them instead.
     */
    private static void validateInputs(String comment, List<Blob> blobs,
            boolean allowDuplicateNames) throws IOException {
        if (blobs == null || blobs.isEmpty()) {
            throw new IllegalArgumentException("At least one POD entry is required.");
        }
        if (blobs.size() > MAX_ITEMS) {
            throw new IOException("Too many POD entries: " + blobs.size());
        }
        if (nameByteLength(comment) > COMMENT_SIZE - 1) {
            throw new IOException("POD comment exceeds 79 bytes");
        }
        java.util.HashSet<String> names = new java.util.HashSet<>();
        for (Blob blob : blobs) {
            String normalized = normalizeName(blob.name());
            validatePath(normalized);
            if (!names.add(normalized.toUpperCase(java.util.Locale.ROOT))
                    && !allowDuplicateNames) {
                throw new IOException("Duplicate POD entry name: " + normalized);
            }
            if (blob.timestamp() < 0 || blob.timestamp() > 0xFFFF_FFFFL) {
                throw new IOException("Entry timestamp is outside uint32 range: " + blob.name());
            }
        }
    }

    private static int[] offsetsFor(List<Blob> blobs, int headerSize) {
        int[] offsets = new int[blobs.size()];
        int cursor = headerSize;
        for (int i = 0; i < blobs.size(); i++) {
            offsets[i] = cursor;
            cursor = Math.addExact(cursor, blobs.get(i).data().length);
        }
        return offsets;
    }

    /**
     * Writes the 80-byte comment field. The original bytes go back out verbatim
     * when the caller has them and the comment text still matches what they decode
     * to, which is what preserves anything after the terminator through an open and
     * save. An edited comment is written fresh.
     */
    private static void writeCommentField(ByteArrayOutputStream out, String comment, byte[] original) {
        String text = comment != null ? comment : "";
        if (original != null && original.length == COMMENT_SIZE && decodeField(original).equals(text)) {
            out.write(original, 0, COMMENT_SIZE);
            return;
        }
        writeFixedAscii(out, comment, COMMENT_SIZE);
    }

    /** Decodes a field the way the reader does, so the two can be compared. */
    private static String decodeField(byte[] field) {
        int end = 0;
        while (end < field.length && field[end] != 0) {
            end++;
        }
        return new String(field, 0, end, StandardCharsets.ISO_8859_1).trim();
    }

    /**
     * Writes one directory name field.
     *
     * <p>When the entry came from an archive and its original field fits the
     * layout being written, those bytes go back out verbatim, so anything after
     * the terminator survives a re-save. Most archives leave only zeros there;
     * Fury3's {@code FURYSE.POD} packs a second null-terminated {@code .ACT}
     * name into the spare bytes of every {@code ART\*.RAW} entry. Nothing
     * documents that and nothing here reads past the first terminator, but
     * re-saving should not silently drop bytes that were in the file.
     *
     * <p>A field wider than the target layout cannot be copied, so it is rebuilt
     * from the name instead.
     */
    private static void writeNameField(ByteArrayOutputStream out, Blob blob, int nameSize) {
        byte[] original = blob.nameField();
        if (original != null && original.length == nameSize
                && decodeField(original).equals(blob.name())) {
            out.write(original, 0, original.length);
            return;
        }
        byte[] field = new byte[nameSize];
        byte[] name = blob.name().getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(name, 0, field, 0, name.length);
        String palette = blob.embeddedPaletteName();
        if (palette != null && !palette.isBlank()) {
            byte[] pal = palette.getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(pal, 0, field, name.length + 1, pal.length);
        }
        out.writeBytes(field);
    }

    /** Writes a fixed-length, null-padded ISO-8859-1 string field. */
    private static void writeFixedAscii(ByteArrayOutputStream out, String value, int length) {
        byte[] bytes = value != null ? value.getBytes(StandardCharsets.ISO_8859_1) : new byte[0];
        int copyLength = Math.min(bytes.length, length - 1);
        out.write(bytes, 0, copyLength);
        if (copyLength < length) {
            out.writeBytes(new byte[length - copyLength]);
        }
    }

    /** Returns the ISO-8859-1 byte length of an entry name, excluding the terminator. */
    private static int nameByteLength(String name) {
        return name != null ? name.getBytes(StandardCharsets.ISO_8859_1).length : 0;
    }

    private static int requiredNameFieldLength(Blob blob) {
        int length = nameByteLength(blob.name()) + 1;
        String palette = blob.embeddedPaletteName();
        return palette == null || palette.isBlank() ? length : length + nameByteLength(palette) + 1;
    }

    /** Canonical archive paths use backslashes and never rooted/traversal components. */
    public static String normalizeName(String name) {
        return name.replace('/', '\\');
    }

    public static void validatePath(String name) throws IOException {
        if (name == null || name.isBlank() || name.startsWith("\\") || name.endsWith("\\")
                || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("Unsafe POD entry path: " + name);
        }
        for (String part : name.split("\\\\", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IOException("Unsafe POD entry path: " + name);
            }
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < 0x20) throw new IOException("Unsafe POD entry path: " + name);
            }
        }
    }

    private static void writeFixedStrict(ByteArrayOutputStream out, String value, int size,
            String label) throws IOException {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length > size - 1) throw new IOException(label + " exceeds " + (size - 1) + " bytes");
        out.writeBytes(bytes);
        out.writeBytes(new byte[size - bytes.length]);
    }

    private static void writeAudit(ByteArrayOutputStream out, PodArchive.AuditEntry audit)
            throws IOException {
        writeFixedStrict(out, audit.user(), 32, "Audit user");
        writeInt32(out, (int) audit.timestamp());
        writeInt32(out, audit.action().ordinal());
        writeFixedStrict(out, audit.entryPath(), 256, "Audit entry path");
        writeInt32(out, (int) audit.oldTimestamp());
        writeInt32(out, (int) audit.oldSize());
        writeInt32(out, (int) audit.newTimestamp());
        writeInt32(out, (int) audit.newSize());
    }

    private static void validateAudit(PodArchive.AuditEntry audit) throws IOException {
        if (audit == null || audit.action() == null) throw new IOException("Invalid POD2 audit record");
        long[] values = {audit.timestamp(), audit.oldTimestamp(), audit.oldSize(),
                audit.newTimestamp(), audit.newSize()};
        for (long value : values) {
            if (value < 0 || value > 0xFFFF_FFFFL) {
                throw new IOException("POD2 audit value is outside uint32 range: " + value);
            }
        }
    }

    /**
     * The POD2 checksum: CRC-32/MPEG-2, i.e. polynomial 0x04C11DB7, initial
     * 0xffffffff, MSB-first, and no final XOR. Check value for "123456789" is
     * 0x0376E6E7. Not CRC-32/CCITT, despite what the shape of it suggests.
     */
    public static long crc32Mpeg2(byte[] bytes) { return crc32Mpeg2(bytes, 0, bytes.length); }

    public static long crc32Mpeg2(byte[] bytes, int offset, int length) {
        int crc = 0xFFFF_FFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (bytes[i] & 0xFF) << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc << 1) ^ ((crc & 0x8000_0000) != 0 ? 0x04C11DB7 : 0);
            }
        }
        return Integer.toUnsignedLong(crc);
    }

    private static void writeInt32At(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value; bytes[at + 1] = (byte) (value >>> 8);
        bytes[at + 2] = (byte) (value >>> 16); bytes[at + 3] = (byte) (value >>> 24);
    }

    /** Writes a 32-bit unsigned integer in little-endian byte order. */
    private static void writeInt32(ByteArrayOutputStream out, int value) {
        out.write( value         & 0xFF);
        out.write((value >>>  8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    /**
     * An in-memory entry to be packed into a POD archive.
     *
     * @param name archive entry name (e.g. {@code "ART\\WALL01.RAW"});
     *             at most {@value PodArchiveWriter#MAX_NAME_LENGTH} bytes, and
     *             names over 31 bytes force the POD1-64 directory
     * @param data raw file content
     * @param nameField the directory field this entry was read from, written back
     *                  verbatim when it fits, or {@code null} to build the field
     *                  from {@code name}
     */
    public record Blob(String name, byte[] data, byte[] nameField,
                       String embeddedPaletteName, long timestamp) {
        public Blob {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("POD entry name is required.");
            }
            if (data == null) {
                throw new IllegalArgumentException("POD entry data is required.");
            }
            name = normalizeName(name);
            if (embeddedPaletteName == null && nameField != null) {
                embeddedPaletteName = PodArchive.Entry.secondString(nameField);
            }
        }

        /** A blob with no original directory field, for entries added from disk. */
        public Blob(String name, byte[] data) {
            this(name, data, null, null, Instant.now().getEpochSecond());
        }

        public Blob(String name, byte[] data, byte[] nameField) {
            this(name, data, nameField, null, Instant.now().getEpochSecond());
        }
    }

    /**
     * @param allowDuplicateNames permits entry names that collide case-insensitively.
     *        Authoring always rejects them, but real archives use a repeated name as
     *        an override and must stay re-savable, so opening one sets this. See
     *        {@link #validateInputs}.
     */
    public record WriteOptions(PodArchive.Format format, byte[] commentField,
                               List<PodArchive.AuditEntry> auditEntries,
                               boolean allowDuplicateNames) {
        public WriteOptions {
            if (format == null) format = PodArchive.Format.POD1;
            auditEntries = auditEntries == null ? List.of() : List.copyOf(auditEntries);
        }

        public WriteOptions(PodArchive.Format format, byte[] commentField,
                            List<PodArchive.AuditEntry> auditEntries) {
            this(format, commentField, auditEntries, false);
        }
    }
}
