package com.mtm2.jpod.io.pod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final int MAX_ITEMS       = 4096;

    /** Longest entry name the POD1-64 directory can store, excluding the terminator. */
    public static final int MAX_NAME_LENGTH  = LONG_NAME_SIZE - 1;

    /**
     * Builds the archive bytes and writes them atomically to {@code path}.
     *
     * <p>Parent directories are created if absent.
     *
     * @param path    destination file (created or overwritten)
     * @param comment up to 79-character archive comment (truncated if longer)
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
        byte[] bytes = buildBytes(comment, blobs);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, bytes);
        return formatFor(blobs);
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
        for (Blob blob : blobs) {
            if (nameByteLength(blob.name()) > ENTRY_NAME_SIZE - 1) {
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
        if (blobs == null || blobs.isEmpty()) {
            throw new IllegalArgumentException("At least one POD entry is required.");
        }
        if (blobs.size() > MAX_ITEMS) {
            throw new IOException("Too many POD entries: " + blobs.size());
        }
        for (Blob blob : blobs) {
            if (nameByteLength(blob.name()) > MAX_NAME_LENGTH) {
                throw new IOException("POD entry name exceeds " + MAX_NAME_LENGTH
                        + " bytes: " + blob.name());
            }
        }

        int nameSize   = formatFor(blobs) == PodArchive.Format.POD1_64
                ? LONG_NAME_SIZE : ENTRY_NAME_SIZE;
        int entrySize  = nameSize + Integer.BYTES + Integer.BYTES;
        int headerSize = Integer.BYTES + COMMENT_SIZE + blobs.size() * entrySize;
        int[] offsets = new int[blobs.size()];
        int cursor = headerSize;
        for (int i = 0; i < blobs.size(); i++) {
            offsets[i] = cursor;
            cursor = Math.addExact(cursor, blobs.get(i).data().length);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(cursor);
        writeInt32(out, blobs.size());
        writeFixedAscii(out, comment, COMMENT_SIZE);
        for (int i = 0; i < blobs.size(); i++) {
            Blob blob = blobs.get(i);
            writeFixedAscii(out, blob.name(), nameSize);
            writeInt32(out, blob.data().length);
            writeInt32(out, offsets[i]);
        }
        for (Blob blob : blobs) {
            out.writeBytes(blob.data());
        }
        return out.toByteArray();
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
     */
    public record Blob(String name, byte[] data) {
        public Blob {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("POD entry name is required.");
            }
            if (data == null) {
                throw new IllegalArgumentException("POD entry data is required.");
            }
        }
    }
}
