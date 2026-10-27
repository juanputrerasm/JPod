package com.mtm2.jpod.io.pod;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Random-access byte source behind an open archive.
 *
 * <p>A POD directory is a few kilobytes even when the archive is hundreds of
 * megabytes, so the reader parses the directory and leaves the payloads where they
 * are. Everything that later wants an entry's bytes, a preview, an extract or a
 * re-save, asks the source for exactly the range it needs.
 *
 * <p>Implementations must be safe to call from more than one thread: previews and
 * extraction run on background workers while the UI thread is still reading sizes
 * and names.
 */
public interface PodDataSource extends Closeable {

    /** The transfer size used wherever a payload is streamed rather than held. */
    int SCRATCH_SIZE = 65536;

    /** Total bytes available, which is the archive's size on disk. */
    long length();

    /**
     * Fills {@code buffer} from {@code offset}, returning how many bytes were
     * actually available. A short return means end of source, not an error.
     */
    int read(long offset, byte[] buffer, int bufferOffset, int count);

    /** Releases whatever the source holds. Never throws. */
    @Override
    void close();

    /**
     * The requested range as a new array.
     *
     * @throws UncheckedIOException when the range runs past the end of the source
     */
    default byte[] readExact(long offset, int count) {
        if (count <= 0) {
            return new byte[0];
        }
        byte[] buffer = new byte[count];
        int read = read(offset, buffer, 0, count);
        if (read != count) {
            throw new UncheckedIOException(new IOException("POD data ends early: wanted "
                    + count + " bytes at " + offset + ", got " + read));
        }
        return buffer;
    }

    /**
     * Streams a range to {@code target} through {@code scratch}, so a large payload
     * never has to exist as one array.
     */
    default void copyTo(long offset, long count, OutputStream target, byte[] scratch)
            throws IOException {
        long remaining = count;
        long at = offset;
        while (remaining > 0) {
            int want = (int) Math.min(scratch.length, remaining);
            int read = read(at, scratch, 0, want);
            if (read <= 0) {
                break;
            }
            target.write(scratch, 0, read);
            at += read;
            remaining -= read;
        }
    }

    /** A buffer of the standard transfer size. */
    static byte[] newScratch() {
        return new byte[SCRATCH_SIZE];
    }

    /** Opens a file for positional reads. The caller owns the returned source. */
    static PodDataSource open(Path path) throws IOException {
        return new FileSource(path);
    }

    /** Wraps bytes already in memory: a test fixture, a manifest build, or caller-built bytes. */
    static PodDataSource of(byte[] bytes) {
        return new MemorySource(bytes);
    }

    /**
     * Reads an archive straight from the file it lives in, one channel held open for
     * as long as the archive is.
     *
     * <p>Positional reads on a {@link FileChannel} need no shared file pointer, so
     * concurrent readers do not have to take turns. On Windows the JDK opens the
     * file with delete sharing, which is what lets the user save over the archive
     * they currently have open: the writer builds a temporary file beside the target
     * and moves it into place.
     */
    final class FileSource implements PodDataSource {
        private final Path path;
        private final FileChannel channel;
        private final long length;

        FileSource(Path path) throws IOException {
            this.path = path;
            this.channel = FileChannel.open(path, StandardOpenOption.READ);
            this.length = channel.size();
        }

        /** The file this source reads from. */
        public Path path() {
            return path;
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public int read(long offset, byte[] buffer, int bufferOffset, int count) {
            if (offset < 0 || count <= 0) {
                return 0;
            }
            ByteBuffer target = ByteBuffer.wrap(buffer, bufferOffset, count);
            int total = 0;
            try {
                while (target.hasRemaining()) {
                    int chunk = channel.read(target, offset + total);
                    if (chunk <= 0) {
                        break;
                    }
                    total += chunk;
                }
            } catch (ClosedChannelException ex) {
                throw new IllegalStateException("The archive has been closed: " + path, ex);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            return total;
        }

        @Override
        public void close() {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Closing a read-only channel has nothing worth reporting.
            }
        }
    }

    /** An archive already in memory. Holds no file handle, so closing it does nothing. */
    final class MemorySource implements PodDataSource {
        private final byte[] bytes;

        MemorySource(byte[] bytes) {
            if (bytes == null) {
                throw new IllegalArgumentException("bytes");
            }
            this.bytes = bytes;
        }

        @Override
        public long length() {
            return bytes.length;
        }

        @Override
        public int read(long offset, byte[] buffer, int bufferOffset, int count) {
            if (offset < 0 || offset >= bytes.length || count <= 0) {
                return 0;
            }
            int available = (int) Math.min(count, bytes.length - offset);
            System.arraycopy(bytes, (int) offset, buffer, bufferOffset, available);
            return available;
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
