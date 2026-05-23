package com.mtm2.jpod.io;

import com.mtm2.jpod.PodSession;
import com.mtm2.jpod.io.pod.PodArchive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Extracts entries from an open POD archive to disk.
 *
 * <p>Uses a {@link java.nio.channels.FileChannel} transfer loop with a 64 KiB
 * buffer. Missing parent directories are created automatically via
 * {@link java.nio.file.Files#createDirectories}.
 *
 * <p>POD entry names may contain backslash path separators; these are normalised
 * to the host OS separator before writing output files.
 */
public final class PodExtractService {

    private static final int TRANSFER_BUFFER_SIZE = 65536;

    private final PodSession session;

    public PodExtractService(PodSession session) {
        this.session = session;
    }

    /**
     * Extracts every entry in the open archive to {@code session.getTargetFolderPath()}.
     *
     * @param progressCallback receives the 1-based index of each entry as it starts
     * @throws IOException if any read or write fails
     */
    public void extractAll(IntConsumer progressCallback) throws IOException {
        PodArchive archive = requireOpenArchive();
        Path targetRoot = requireTargetFolder();
        List<PodArchive.Entry> entries = archive.getEntries();

        try (FileChannel src = FileChannel.open(session.getSourcePath(), StandardOpenOption.READ)) {
            for (int i = 0; i < entries.size(); i++) {
                if (progressCallback != null) progressCallback.accept(i + 1);
                extractEntry(src, entries.get(i), targetRoot);
            }
        }
    }

    /**
     * Extracts only the supplied subset of entries.
     *
     * @param selectedEntries entries to extract
     * @param progressCallback receives the 1-based index within {@code selectedEntries}
     * @throws IOException if any read or write fails
     */
    public void extractSelected(List<PodArchive.Entry> selectedEntries, IntConsumer progressCallback)
            throws IOException {
        requireOpenArchive();
        Path targetRoot = requireTargetFolder();

        try (FileChannel src = FileChannel.open(session.getSourcePath(), StandardOpenOption.READ)) {
            for (int i = 0; i < selectedEntries.size(); i++) {
                if (progressCallback != null) progressCallback.accept(i + 1);
                PodArchive.Entry entry = selectedEntries.get(i);
                extractEntry(src, entry, targetRoot);
            }
        }
    }

    private void extractEntry(FileChannel src, PodArchive.Entry entry, Path targetRoot)
            throws IOException {
        Path dest = resolveDestination(targetRoot, entry.name());
        Files.createDirectories(dest.getParent());
        extractEntryToFile(src, entry, dest);
    }

    private void extractEntryToFile(FileChannel src, PodArchive.Entry entry, Path dest)
            throws IOException {
        try (FileChannel dst = FileChannel.open(dest,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long remaining = entry.length();
            long srcPos = entry.offset();
            ByteBuffer buf = ByteBuffer.allocate((int) Math.min(TRANSFER_BUFFER_SIZE, remaining + 1));
            while (remaining > 0) {
                buf.clear();
                if (buf.limit() > remaining) buf.limit((int) remaining);
                int read = src.read(buf, srcPos);
                if (read <= 0) break;
                buf.flip();
                dst.write(buf);
                srcPos += read;
                remaining -= read;
            }
        }
    }

    /**
     * Resolves a POD entry name (which may contain backslash path separators) to
     * a {@link Path} under {@code targetRoot}, converting path separators for the
     * current OS.
     */
    private static Path resolveDestination(Path targetRoot, String entryName) {
        String normalized = entryName.replace('\\', '/').replace('\0', ' ').strip();
        Path relative = Path.of(normalized.replace('/', java.io.File.separatorChar));
        return targetRoot.resolve(relative);
    }

    private PodArchive requireOpenArchive() {
        PodArchive archive = session.getOpenArchive();
        if (archive == null) throw new IllegalStateException("No archive is open.");
        return archive;
    }

    private Path requireTargetFolder() {
        Path p = session.getTargetFolderPath();
        if (p == null) throw new IllegalStateException("No target folder is set.");
        return p;
    }
}
