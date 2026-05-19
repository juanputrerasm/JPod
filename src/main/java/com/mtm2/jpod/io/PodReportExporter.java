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
                // Build an 80-char padded line: name left-justified, size at col 30, offset at col 45
                StringBuilder line = new StringBuilder(80);
                String name = entry.name().replace('\0', ' ').strip();
                line.append(name);
                while (line.length() < 30) line.append(' ');
                String size = String.valueOf(entry.length());
                line.replace(30, 30 + size.length(), size);
                while (line.length() < 45) line.append(' ');
                String offset = String.valueOf(entry.offset());
                line.replace(45, 45 + offset.length(), offset);
                w.write(line.toString().stripTrailing());
                w.newLine();
            }
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
