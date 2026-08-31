package com.mtm2.jpod.io;

import com.mtm2.jpod.PodSession;
import com.mtm2.jpod.io.pod.PodArchive;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Exports human-readable reports about the open POD archive.
 *
 * <p>Two report types are supported:
 * <ul>
 *   <li>{@code .inf} — fixed-column text report: filename, byte size, entry
 *       count, archive comment, and one padded line per entry showing name,
 *       size, and byte offset.</li>
 *   <li>{@code .lst} — plain entry-names list, one name per line; suitable
 *       as input for {@link PodManifestParser}.</li>
 * </ul>
 */
public final class PodReportExporter {

    private final PodSession session;

    public PodReportExporter(PodSession session) {
        this.session = session;
    }

    /**
     * Writes a fixed-column {@code .inf} report to {@code outputPath}.
     *
     * <p>Output format:
     * <pre>
     *   Pod Filename = &lt;name&gt;
     *   Pod Size     = &lt;bytes&gt;
     *   Pod Entries  = &lt;count&gt;
     *   Pod Title    = &lt;comment&gt;
     *
     *   Filename                     File Size      File Offset
     *   &lt;name padded to col 30&gt;      &lt;size col 30&gt;  &lt;offset col 45&gt;
     *   (a name wider than its column shifts the later columns right)
     *   …
     * </pre>
     *
     * @param outputPath path of the file to create or overwrite
     * @throws IOException              on write failure
     * @throws IllegalStateException    if no archive is open in the session
     */
    public void writeInfoReport(Path outputPath) throws IOException {
        PodArchive archive = requireOpenArchive();
        List<PodArchive.Entry> entries = archive.getEntries();

        try (BufferedWriter w = Files.newBufferedWriter(outputPath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write("Pod Filename = " + session.getSourceFileName());
            w.newLine();
            w.write("Pod Size     = " + session.getArchiveByteSize());
            w.newLine();
            w.write("Pod Entries  = " + entries.size());
            w.newLine();
            w.write("Pod Title    = " + archive.getComment());
            w.newLine();
            w.newLine();
            w.write("Filename                     File Size      File Offset");
            w.newLine();

            for (PodArchive.Entry entry : entries) {
                // Name left-justified, size at col 30, offset at col 45. A POD1-64
                // name can be wider than its column, in which case the remaining
                // columns shift right instead of overwriting the name.
                StringBuilder line = new StringBuilder(96);
                line.append(entry.name().replace('\0', ' ').strip());
                padTo(line, 30);
                line.append(entry.length());
                padTo(line, 45);
                line.append(entry.offset());
                w.write(line.toString().stripTrailing());
                w.newLine();
            }
        }
    }

    /**
     * Pads {@code line} with spaces up to {@code column}, or appends a single
     * separating space when the content already reaches past it.
     */
    private static void padTo(StringBuilder line, int column) {
        if (line.length() >= column) {
            line.append(' ');
            return;
        }
        while (line.length() < column) {
            line.append(' ');
        }
    }

    /**
     * Writes a plain-text {@code .lst} entry-names file to {@code outputPath},
     * one entry name per line with no sizes or offsets.
     *
     * <p>The resulting file can be used directly as input to {@link PodManifestParser}.
     *
     * @param outputPath path of the file to create or overwrite
     * @throws IOException              on write failure
     * @throws IllegalStateException    if no archive is open in the session
     */
    public void writeListFile(Path outputPath) throws IOException {
        PodArchive archive = requireOpenArchive();
        try (BufferedWriter w = Files.newBufferedWriter(outputPath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (PodArchive.Entry entry : archive.getEntries()) {
                w.write(entry.name());
                w.newLine();
            }
        }
    }

    private PodArchive requireOpenArchive() {
        PodArchive a = session.getOpenArchive();
        if (a == null) throw new IllegalStateException("No archive is open.");
        return a;
    }
}
