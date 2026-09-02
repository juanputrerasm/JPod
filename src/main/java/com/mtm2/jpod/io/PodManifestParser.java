package com.mtm2.jpod.io;

import com.mtm2.jpod.io.pod.PodArchiveWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a plain-text manifest ({@code .lst}) file into a list of POD blobs.
 *
 * <p>Manifest format — one entry per non-blank line:
 * <pre>
 *   filename              → stored in the archive under that name
 *   filename,archiveName  → file read as {@code filename}, stored as {@code archiveName}
 * </pre>
 *
 * <p>Archive entry names are upper-cased (Terminal Reality convention).
 */
public final class PodManifestParser {

    /** Parsed PODTool response-file metadata plus the resolved archive entries. */
    public record Manifest(String podFileName, String volumeName,
                           List<PodArchiveWriter.Blob> blobs) {}

    /**
     * Parses {@code manifestPath} and resolves each entry's bytes from disk.
     *
     * <p>Files are looked up relative to {@code sourceFolder}. If a file is not
     * found there the immediate parent of {@code sourceFolder} is also tried
     * as a fallback, preserving compatibility with archives whose source files
     * span two directory levels.
     *
     * @param manifestPath  path to the {@code .lst} manifest file
     * @param sourceFolder  base directory for resolving listed filenames
     * @return list of {@link PodArchiveWriter.Blob} ready to pass to
     *         {@link PodArchiveWriter#write}
     * @throws IOException if any listed source file cannot be read
     */
    public List<PodArchiveWriter.Blob> parse(Path manifestPath, Path sourceFolder) throws IOException {
        return parseManifest(manifestPath, sourceFolder).blobs();
    }

    public Manifest parseManifest(Path manifestPath, Path sourceFolder) throws IOException {
        List<String> lines = Files.readAllLines(manifestPath);
        List<PodArchiveWriter.Blob> blobs = new ArrayList<>();
        String podFileName = "noname.pod";
        String volumeName = "";

        Path parentFolder = sourceFolder.getParent();

        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("//")) continue;
            if (line.regionMatches(true, 0, "podFilename:", 0, 12)) {
                podFileName = line.substring(12).strip();
                continue;
            }
            if (line.regionMatches(true, 0, "volumeName:", 0, 11)) {
                volumeName = line.substring(11).strip();
                continue;
            }

            String fileName;
            String entryName;

            int comma = line.indexOf(',');
            if (comma > 0) {
                fileName = line.substring(0, comma).strip();
                entryName = line.substring(comma + 1).strip();
            } else {
                fileName = line;
                entryName = line;
            }

            byte[] data = resolveFileBytes(fileName, sourceFolder, parentFolder);
            blobs.add(new PodArchiveWriter.Blob(entryName.toUpperCase(java.util.Locale.ROOT), data));
        }

        return new Manifest(podFileName, volumeName, List.copyOf(blobs));
    }

    private static byte[] resolveFileBytes(String fileName, Path sourceFolder, Path parentFolder)
            throws IOException {
        Path candidate = sourceFolder.resolve(fileName);
        if (Files.exists(candidate)) {
            return Files.readAllBytes(candidate);
        }
        if (parentFolder != null) {
            Path fallback = parentFolder.resolve(fileName);
            if (Files.exists(fallback)) {
                return Files.readAllBytes(fallback);
            }
        }
        throw new IOException("Manifest entry not found: " + fileName);
    }
}
