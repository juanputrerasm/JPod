package com.mtm2.jpod.io.pod;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Builds and writes Terminal Reality POD archives.
 *
 * <p>The POD1 layout produced is:
 * <pre>
 *   4 bytes   item count (int32 LE)
 *  80 bytes   comment, null-padded (ISO-8859-1, at most 79 usable bytes)
 *  N x 40     directory table, for each entry:
 *               32 bytes  name, null-padded (ISO-8859-1)
 *                4 bytes  data length (uint32 LE)
 *                4 bytes  data offset from file start (uint32 LE)
 *   ...       raw file data concatenated in directory order
 * </pre>
 *
 * <p>A name that does not fit the 31-byte budget is a refusal naming the entry and
 * its length, never a truncation: a truncated name packs without error and then
 * simply never resolves in game.
 *
 * <p>Archives are written straight to the output, one payload at a time through a
 * shared transfer buffer, so building a 200 MB archive costs the directory plus that
 * buffer rather than the archive twice over. {@link #write(Path, String, List,
 * WriteOptions, Runnable)} builds beside the target and moves it into place, which
 * keeps a failed write from destroying the original and makes it safe to save over
 * an archive that is currently open for reading.
 *
 * <p>Entry names are written exactly as supplied; callers are responsible for
 * uppercasing if the target game requires it. The engine uppercases every name at
 * mount, so lowercase resolves, but every shipped pod stores uppercase and writing
 * it keeps round trips byte-identical.
 */
public final class PodArchiveWriter {

    private static final int COMMENT_SIZE    = 80;
    private static final int ENTRY_NAME_SIZE = 32;
    private static final int ENTRY_SIZE      = 40;
    private static final int POD2_HEADER_SIZE = 96;
    private static final int POD2_ENTRY_SIZE = 20;
    /**
     * Matches the reader's limit, so anything JPod can open it can also save.
     * Hellbender's {@code GAME.POD} holds 4 341 entries.
     */
    private static final int MAX_ITEMS       = PodArchiveReader.MAX_REASONABLE_ITEMS;

    /** Longest entry name a POD1 directory can store, excluding the terminator. */
    public static final int MAX_NAME_LENGTH  = ENTRY_NAME_SIZE - 1;

    /** Width of a POD1 directory name field. */
    public static final int CLASSIC_NAME_FIELD_SIZE = ENTRY_NAME_SIZE;

    /** Width of a POD1 directory record. */
    public static final int CLASSIC_ENTRY_SIZE = ENTRY_SIZE;

    /** Suffix of the temporary file a write builds before replacing the target. */
    private static final String TEMP_SUFFIX = ".jpodtmp";

    /** Initial value of a POD2 checksum, and the seed to start one with. */
    public static final int CRC32_SEED = 0xFFFF_FFFF;

    /** One entry per leading byte value: a byte costs a lookup instead of eight steps. */
    private static final int[] CRC32_TABLE = buildCrc32Table();

    /**
     * Builds the archive and writes it to {@code path}.
     *
     * @return the directory layout written: {@link PodArchive.Format#POD1}, or
     *         {@link PodArchive.Format#POD2} when that was requested
     * @throws IllegalArgumentException if {@code blobs} is null or empty
     * @throws IOException              if the entry count exceeds the limit, a name is
     *                                  longer than {@value #MAX_NAME_LENGTH} bytes, on
     *                                  offset overflow, or on write failure
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

    /** Writes an explicitly selected POD1 or POD2 archive. */
    public PodArchive.Format write(Path path, String comment, List<Blob> blobs,
            WriteOptions options) throws IOException {
        return write(path, comment, blobs, options, null);
    }

    /**
     * Writes an archive beside {@code path} and moves it into place.
     *
     * @param beforeReplace runs once the new archive is complete on disk and before
     *        it takes the target's place. A caller that is reading from the target,
     *        which is what saving over the archive you have open means, releases its
     *        handle here: every payload has been streamed out by then.
     */
    public PodArchive.Format write(Path path, String comment, List<Blob> blobs,
            WriteOptions options, Runnable beforeReplace) throws IOException {
        validateLayout(comment, blobs, options);
        PodArchive.Format actual = actualFormat(blobs, options.format());
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Build beside the target and swap. Until the swap the target is untouched,
        // so a source archive being read from can also be the destination.
        Path temp = absolute.resolveSibling(absolute.getFileName() + TEMP_SUFFIX);
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                OutputStream output = new BufferedOutputStream(
                        Channels.newOutputStream(channel), PodDataSource.SCRATCH_SIZE);
                try {
                    if (actual == PodArchive.Format.POD2) {
                        int crc = writePod2(output, comment, blobs, options.auditEntries());
                        output.flush();
                        ByteBuffer field = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
                        field.putInt(crc).flip();
                        channel.write(field, 4);
                    } else {
                        writePod1(output, comment, blobs, options.commentField());
                        output.flush();
                    }
                } catch (UncheckedIOException ex) {
                    throw ex.getCause();
                }
            }
            if (beforeReplace != null) {
                beforeReplace.run();
            }
            replace(temp, absolute);
        } finally {
            Files.deleteIfExists(temp);
        }
        return actual;
    }

    /** Moves the finished temporary file onto the target path. */
    private static void replace(Path temp, Path path) throws IOException {
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Returns the directory layout {@link #buildBytes} would use for {@code blobs},
     * which for the POD1 family is always {@link PodArchive.Format#POD1}.
     */
    public static PodArchive.Format formatFor(List<Blob> blobs) {
        return actualFormat(blobs, PodArchive.Format.POD1);
    }

    /**
     * Validates the requested output format.
     *
     * @throws IllegalArgumentException if {@code requested} is not a writable format
     */
    public static PodArchive.Format actualFormat(List<Blob> blobs, PodArchive.Format requested) {
        if (requested == PodArchive.Format.POD2) return requested;
        if (requested == PodArchive.Format.POD1) return requested;
        throw new IllegalArgumentException("Unsupported output format: " + requested);
    }

    /**
     * Every check a write performs, without producing the archive. The directory
     * constraints come from the names and the overflow checks from the lengths, so
     * no payload is read: renaming one entry in a large archive stays cheap.
     */
    public static void validateLayout(String comment, List<Blob> blobs, WriteOptions options)
            throws IOException {
        validateInputs(comment, blobs, options.allowDuplicateNames());
        PodArchive.Format actual = actualFormat(blobs, options.format());
        if (actual == PodArchive.Format.POD2) {
            pod2Layout(comment, blobs, options.auditEntries());
        } else {
            pod1Layout(blobs);
        }
    }

    /**
     * Builds and returns the complete POD binary without writing to disk. Kept for
     * fixtures and in-memory callers; {@link #write} streams instead.
     *
     * @throws IllegalArgumentException if {@code blobs} is null or empty
     * @throws IOException              if the entry count exceeds the limit, a name is
     *                                  longer than {@value #MAX_NAME_LENGTH} bytes, or
     *                                  the total size overflows {@code int}
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

    /** Builds POD1 or POD2 according to explicit write options. */
    public byte[] buildBytes(String comment, List<Blob> blobs, WriteOptions options) throws IOException {
        validateLayout(comment, blobs, options);
        PodArchive.Format actual = actualFormat(blobs, options.format());
        int total = 0;
        for (Blob blob : blobs) total = Math.addExact(total, blob.length());
        ByteArrayOutputStream out = new ByteArrayOutputStream(total + 1024);
        try {
            if (actual == PodArchive.Format.POD2) {
                int crc = writePod2(out, comment, blobs, options.auditEntries());
                byte[] result = out.toByteArray();
                writeInt32At(result, 4, crc);
                return result;
            }
            writePod1(out, comment, blobs, options.commentField());
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
        return out.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Layout: everything that can be decided from names and lengths alone
    // -------------------------------------------------------------------------

    private record Pod1Plan(int headerSize, int[] offsets) {}

    private record Pod2Plan(byte[] names, int[] nameOffsets, int dataStart, int[] offsets) {}

    private static Pod1Plan pod1Layout(List<Blob> blobs) throws IOException {
        for (Blob blob : blobs) {
            checkNameFits(blob);
        }
        int headerSize;
        try {
            headerSize = Math.addExact(Integer.BYTES + COMMENT_SIZE,
                    Math.multiplyExact(blobs.size(), ENTRY_SIZE));
        } catch (ArithmeticException ex) {
            throw new IOException("POD directory is too large", ex);
        }
        return new Pod1Plan(headerSize, offsetsFor(blobs, headerSize));
    }

    private static Pod2Plan pod2Layout(String comment, List<Blob> blobs,
            List<PodArchive.AuditEntry> audits) throws IOException {
        if (audits.size() > MAX_ITEMS) {
            throw new IOException("Too many POD2 audit records: " + audits.size());
        }
        checkFixedField(comment, COMMENT_SIZE, "POD2 comment");
        for (PodArchive.AuditEntry audit : audits) {
            validateAudit(audit);
            checkFixedField(audit.user(), 32, "Audit user");
            checkFixedField(audit.entryPath(), 256, "Audit entry path");
        }
        ByteArrayOutputStream names = new ByteArrayOutputStream();
        int[] nameOffsets = new int[blobs.size()];
        for (int i = 0; i < blobs.size(); i++) {
            nameOffsets[i] = names.size();
            names.writeBytes(blobs.get(i).name().getBytes(StandardCharsets.ISO_8859_1));
            names.write(0);
        }
        byte[] nameBlob = names.toByteArray();
        int dataStart;
        try {
            dataStart = Math.addExact(POD2_HEADER_SIZE,
                    Math.addExact(Math.multiplyExact(blobs.size(), POD2_ENTRY_SIZE), nameBlob.length));
        } catch (ArithmeticException ex) {
            throw new IOException("POD2 directory is too large", ex);
        }
        return new Pod2Plan(nameBlob, nameOffsets, dataStart, offsetsFor(blobs, dataStart));
    }

    // -------------------------------------------------------------------------
    // Writing
    // -------------------------------------------------------------------------

    private static void writePod1(OutputStream out, String comment, List<Blob> blobs,
            byte[] commentField) throws IOException {
        Pod1Plan plan = pod1Layout(blobs);
        writeInt32(out, blobs.size());
        writeCommentField(out, comment, commentField);
        for (int i = 0; i < blobs.size(); i++) {
            writeNameField(out, blobs.get(i));
            writeInt32(out, blobs.get(i).length());
            writeInt32(out, plan.offsets()[i]);
        }
        byte[] scratch = PodDataSource.newScratch();
        for (Blob blob : blobs) blob.copyTo(out, scratch);
    }

    /**
     * Writes a POD2 archive with a zero checksum field and returns the checksum that
     * belongs there. It covers everything after the field, so it is accumulated as
     * the bytes go past rather than by reading the finished archive back.
     */
    private static int writePod2(OutputStream out, String comment, List<Blob> blobs,
            List<PodArchive.AuditEntry> audits) throws IOException {
        Pod2Plan plan = pod2Layout(comment, blobs, audits);
        byte[] scratch = PodDataSource.newScratch();
        out.write(new byte[] {'P', 'O', 'D', '2'});
        writeInt32(out, 0);
        CrcOutputStream hashed = new CrcOutputStream(out);
        writeFixedStrict(hashed, comment, COMMENT_SIZE, "POD2 comment");
        writeInt32(hashed, blobs.size());
        writeInt32(hashed, audits.size());
        for (int i = 0; i < blobs.size(); i++) {
            Blob blob = blobs.get(i);
            writeInt32(hashed, plan.nameOffsets()[i]);
            writeInt32(hashed, blob.length());
            writeInt32(hashed, plan.offsets()[i]);
            writeInt32(hashed, (int) blob.timestamp());
            writeInt32(hashed, (int) blob.crc32Mpeg2(scratch));
        }
        hashed.write(plan.names());
        for (Blob blob : blobs) blob.copyTo(hashed, scratch);
        for (PodArchive.AuditEntry audit : audits) writeAudit(hashed, audit);
        hashed.flush();
        return hashed.crc;
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
        HashSet<String> names = new HashSet<>();
        for (Blob blob : blobs) {
            String normalized = normalizeName(blob.name());
            validatePath(normalized);
            if (!names.add(normalized.toUpperCase(Locale.ROOT)) && !allowDuplicateNames) {
                throw new IOException("Duplicate POD entry name: " + normalized);
            }
            if (blob.timestamp() < 0 || blob.timestamp() > 0xFFFF_FFFFL) {
                throw new IOException("Entry timestamp is outside uint32 range: " + blob.name());
            }
        }
    }

    private static int[] offsetsFor(List<Blob> blobs, int headerSize) throws IOException {
        int[] offsets = new int[blobs.size()];
        int cursor = headerSize;
        try {
            for (int i = 0; i < blobs.size(); i++) {
                offsets[i] = cursor;
                cursor = Math.addExact(cursor, blobs.get(i).length());
            }
        } catch (ArithmeticException ex) {
            throw new IOException("POD archive would exceed 2 GB", ex);
        }
        return offsets;
    }

    /**
     * Writes the 80-byte comment field. The original bytes go back out verbatim
     * when the caller has them and the comment text still matches what they decode
     * to, which is what preserves anything after the terminator through an open and
     * save. An edited comment is written fresh.
     */
    private static void writeCommentField(OutputStream out, String comment, byte[] original)
            throws IOException {
        String text = comment != null ? comment : "";
        if (original != null && original.length == COMMENT_SIZE && decodeField(original).equals(text)) {
            out.write(original, 0, COMMENT_SIZE);
            return;
        }
        writeFixedStrict(out, comment, COMMENT_SIZE, "POD comment");
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
     * <p>When the entry came from an archive and its original field still names it,
     * those bytes go back out verbatim, so anything after the terminator survives a
     * re-save. Most archives leave only zeros there; Fury3's {@code FURYSE.POD}
     * packs a second null-terminated {@code .ACT} name into the spare bytes of every
     * {@code ART\*.RAW} entry. A renamed entry is rebuilt from its name, carrying
     * the palette record forward.
     */
    private static void writeNameField(OutputStream out, Blob blob) throws IOException {
        byte[] original = blob.nameField();
        if (original != null && original.length == ENTRY_NAME_SIZE
                && decodeField(original).equals(blob.name())) {
            out.write(original, 0, original.length);
            return;
        }
        byte[] field = new byte[ENTRY_NAME_SIZE];
        byte[] name = blob.name().getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(name, 0, field, 0, name.length);
        String palette = blob.embeddedPaletteName();
        if (palette != null && !palette.isBlank()) {
            byte[] pal = palette.getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(pal, 0, field, name.length + 1, pal.length);
        }
        out.write(field);
    }

    /** Returns the ISO-8859-1 byte length of a name, excluding the terminator. */
    private static int nameByteLength(String name) {
        return name != null ? name.getBytes(StandardCharsets.ISO_8859_1).length : 0;
    }

    /**
     * Bytes a directory name field must hold for this name and palette name,
     * terminators included. Exposed so callers can find the entries that will not fit
     * without building a blob per entry.
     */
    public static int requiredNameFieldBytes(String name, String embeddedPaletteName) {
        int length = nameByteLength(name) + 1;
        return embeddedPaletteName == null || embeddedPaletteName.isBlank()
                ? length : length + nameByteLength(embeddedPaletteName) + 1;
    }

    /**
     * Refuses a name the 32-byte directory field cannot hold, naming the entry and the
     * length so the author can shorten it. Never truncates: a truncated name packs
     * without error and then fails to match at load time, which is far harder to
     * diagnose than a refusal.
     *
     * <p>The whole stored path counts, including any {@code ART\} or {@code MODELS\}
     * prefix, the extension and the terminator, and, on the {@code .RAW} entries that
     * carry one, the embedded {@code .ACT} palette record after it. A palette record
     * that no longer fits is reported separately, because dropping it loses producer
     * data that was in the file and shortening the path is not the only remedy.
     */
    private static void checkNameFits(Blob blob) throws IOException {
        int name = nameByteLength(blob.name());
        if (name > MAX_NAME_LENGTH) {
            throw new IOException("POD entry name is " + name + " bytes; POD1 holds "
                    + MAX_NAME_LENGTH + ": " + blob.name());
        }
        int required = requiredNameFieldBytes(blob.name(), blob.embeddedPaletteName());
        if (required > ENTRY_NAME_SIZE) {
            throw new IOException("POD entry name plus its embedded palette record is "
                    + required + " bytes; the directory field holds "
                    + ENTRY_NAME_SIZE + ": " + blob.name() + " + " + blob.embeddedPaletteName());
        }
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

    private static void checkFixedField(String value, int size, String label) throws IOException {
        if (nameByteLength(value) > size - 1) {
            throw new IOException(label + " exceeds " + (size - 1) + " bytes");
        }
    }

    private static void writeFixedStrict(OutputStream out, String value, int size,
            String label) throws IOException {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length > size - 1) throw new IOException(label + " exceeds " + (size - 1) + " bytes");
        out.write(bytes);
        out.write(new byte[size - bytes.length]);
    }

    private static void writeAudit(OutputStream out, PodArchive.AuditEntry audit)
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

    // -------------------------------------------------------------------------
    // CRC-32/MPEG-2
    // -------------------------------------------------------------------------

    private static int[] buildCrc32Table() {
        int[] table = new int[256];
        for (int value = 0; value < 256; value++) {
            int crc = value << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc << 1) ^ ((crc & 0x8000_0000) != 0 ? 0x04C11DB7 : 0);
            }
            table[value] = crc;
        }
        return table;
    }

    /**
     * The POD2 checksum: CRC-32/MPEG-2, i.e. polynomial 0x04C11DB7, initial
     * 0xffffffff, MSB-first, and no final XOR. Check value for "123456789" is
     * 0x0376E6E7. Not CRC-32/CCITT, despite what the shape of it suggests, and not
     * {@link java.util.zip.CRC32}, which is the reflected IEEE checksum.
     */
    public static long crc32Mpeg2(byte[] bytes) { return crc32Mpeg2(bytes, 0, bytes.length); }

    public static long crc32Mpeg2(byte[] bytes, int offset, int length) {
        return Integer.toUnsignedLong(crc32Mpeg2(bytes, offset, length, CRC32_SEED));
    }

    /**
     * Continues a running checksum, so a whole-archive CRC can be accumulated while
     * the archive is written rather than in a second pass over it.
     */
    public static int crc32Mpeg2(byte[] bytes, int offset, int length, int seed) {
        int crc = seed;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            crc = (crc << 8) ^ CRC32_TABLE[((crc >>> 24) ^ bytes[i]) & 0xFF];
        }
        return crc;
    }

    /** Checksums a range of a data source without holding it in memory. */
    public static long crc32Mpeg2(PodDataSource source, long offset, long count) {
        byte[] scratch = PodDataSource.newScratch();
        int crc = CRC32_SEED;
        long remaining = count;
        long at = offset;
        while (remaining > 0) {
            int want = (int) Math.min(scratch.length, remaining);
            int read = source.read(at, scratch, 0, want);
            if (read <= 0) break;
            crc = crc32Mpeg2(scratch, 0, read, crc);
            at += read;
            remaining -= read;
        }
        return Integer.toUnsignedLong(crc);
    }

    /** Passes bytes through while accumulating their CRC-32/MPEG-2. */
    private static final class CrcOutputStream extends FilterOutputStream {
        private int crc = CRC32_SEED;

        CrcOutputStream(OutputStream inner) {
            super(inner);
        }

        @Override
        public void write(int b) throws IOException {
            crc = (crc << 8) ^ CRC32_TABLE[((crc >>> 24) ^ b) & 0xFF];
            out.write(b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            crc = crc32Mpeg2(bytes, offset, length, crc);
            out.write(bytes, offset, length);
        }
    }

    private static void writeInt32At(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value; bytes[at + 1] = (byte) (value >>> 8);
        bytes[at + 2] = (byte) (value >>> 16); bytes[at + 3] = (byte) (value >>> 24);
    }

    /** Writes a 32-bit integer in little-endian byte order. */
    private static void writeInt32(OutputStream out, int value) throws IOException {
        out.write(new byte[] {(byte) value, (byte) (value >>> 8),
                (byte) (value >>> 16), (byte) (value >>> 24)});
    }

    /**
     * One entry handed to the writer: a name plus the bytes to store under it.
     *
     * <p>The bytes are either held directly, for an entry added from disk or built in
     * memory, or referenced in a {@link PodDataSource}, for an entry that came from an
     * archive and has never needed materializing. {@link #length()} is always known
     * either way, which is what lets the writer plan the whole layout before touching
     * a payload.
     */
    public static final class Blob {
        private final String name;
        private final byte[] data;
        private final PodDataSource source;
        private final long offset;
        private final int length;
        private final byte[] nameField;
        private final String embeddedPaletteName;
        private final long timestamp;

        /**
         * @param name      archive entry name (e.g. {@code "ART\\WALL01.RAW"})
         * @param data      raw file content
         * @param nameField the directory field this entry was read from, written back
         *                  verbatim when it still names the entry, or {@code null}
         */
        public Blob(String name, byte[] data, byte[] nameField, String embeddedPaletteName,
                long timestamp) {
            this(name, data, null, 0, data == null ? 0 : data.length, nameField,
                    embeddedPaletteName, timestamp);
            if (data == null) {
                throw new IllegalArgumentException("POD entry data is required.");
            }
        }

        /** A blob with no original directory field, for entries added from disk. */
        public Blob(String name, byte[] data) {
            this(name, data, null, null, Instant.now().getEpochSecond());
        }

        public Blob(String name, byte[] data, byte[] nameField) {
            this(name, data, nameField, null, Instant.now().getEpochSecond());
        }

        /** A blob that reads its payload from an archive only when asked. */
        public Blob(String name, PodDataSource source, long offset, int length,
                byte[] nameField, String embeddedPaletteName, long timestamp) {
            this(name, null, source, offset, length, nameField, embeddedPaletteName, timestamp);
            if (source == null) {
                throw new IllegalArgumentException("POD entry source is required.");
            }
        }

        private Blob(String name, byte[] data, PodDataSource source, long offset, int length,
                byte[] nameField, String embeddedPaletteName, long timestamp) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("POD entry name is required.");
            }
            this.name = normalizeName(name);
            this.data = data;
            this.source = source;
            this.offset = offset;
            this.length = length;
            this.nameField = nameField;
            this.embeddedPaletteName = embeddedPaletteName == null && nameField != null
                    ? PodArchive.Entry.secondString(nameField) : embeddedPaletteName;
            this.timestamp = timestamp;
        }

        public String name() { return name; }

        /** Payload size, known without reading the payload. */
        public int length() { return length; }

        /**
         * The payload as one array, read from the source if it is not already held.
         * Prefer {@link #copyTo} where the bytes are only being written onward.
         */
        public byte[] data() { return data != null ? data : source.readExact(offset, length); }

        public byte[] nameField() { return nameField; }

        public String embeddedPaletteName() { return embeddedPaletteName; }

        public long timestamp() { return timestamp; }

        /** Writes the payload without holding it in memory. */
        public void copyTo(OutputStream target, byte[] scratch) throws IOException {
            if (data != null) {
                target.write(data);
                return;
            }
            source.copyTo(offset, length, target, scratch);
        }

        /** The payload's CRC-32/MPEG-2, streamed when the payload is not held. */
        public long crc32Mpeg2(byte[] scratch) {
            if (data != null) return PodArchiveWriter.crc32Mpeg2(data);
            return PodArchiveWriter.crc32Mpeg2(source, offset, length);
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
