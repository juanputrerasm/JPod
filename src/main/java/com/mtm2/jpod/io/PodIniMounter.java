package com.mtm2.jpod.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds a POD filename to the game's {@code pod.ini} mount list.
 *
 * <p>{@code pod.ini} format:
 * <pre>
 *   &lt;count&gt;        ← integer on line 1: number of currently mounted PODs
 *   FILENAME.POD   ← one mounted POD filename per subsequent line
 *   …
 * </pre>
 *
 * <p>The maximum mount count is 32. If {@code pod.ini} is absent in
 * {@code searchRoot}, the parent directory is tried as a fallback.
 * The file is updated atomically via a {@code pod.wrk} temporary file.
 */
public final class PodIniMounter {

    private static final int MAX_MOUNTED_PODS = 32;
    private static final String POD_INI = "pod.ini";
    private static final String POD_WRK = "pod.wrk";

    /**
     * Mounts {@code podFileName} in the pod.ini closest to {@code searchRoot}.
     *
     * @param podFileName  the filename to add (e.g. "MYTRACK.POD")
     * @param searchRoot   directory where pod.ini is expected; parent is tried as fallback
     * @throws IOException          on read/write failure
     * @throws AlreadyMountedException if the POD is already listed in pod.ini
     * @throws MaxPodsExceededException if the mount list is already full
     * @throws PodIniNotFoundException if pod.ini cannot be found in searchRoot or its parent
     */
    public void mount(String podFileName, Path searchRoot) throws IOException {
        Path iniPath = locatePodIni(searchRoot);
        doMount(podFileName, iniPath, podFileName);
    }

    /**
     * Mounts {@code podFileName} in pod.ini, writing the entry as
     * {@code subfolder\podFileName} when pod.ini lives in a parent directory.
     *
     * @param podFileName  bare filename (e.g. "MYTRACK.POD")
     * @param searchRoot   directory containing (or near) the POD file
     * @param entryLabel   the string actually written to pod.ini
     */
    public void mount(String podFileName, Path searchRoot, String entryLabel) throws IOException {
        Path iniPath = locatePodIni(searchRoot);
        doMount(podFileName, iniPath, entryLabel);
    }

    private Path locatePodIni(Path dir) throws IOException {
        Path candidate = dir.resolve(POD_INI);
        if (Files.exists(candidate)) return candidate;

        Path parent = dir.getParent();
        if (parent != null) {
            candidate = parent.resolve(POD_INI);
            if (Files.exists(candidate)) return candidate;
        }

        throw new PodIniNotFoundException("pod.ini cannot be located near: " + dir);
    }

    private void doMount(String podFileName, Path iniPath, String entryLabel) throws IOException {
        List<String> lines = Files.readAllLines(iniPath);
        if (lines.isEmpty()) lines.add("0");

        int count = parseCount(lines.get(0));
        if (count >= MAX_MOUNTED_PODS) {
            throw new MaxPodsExceededException("Maximum Pods already mounted!");
        }

        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equalsIgnoreCase(entryLabel.strip())) {
                throw new AlreadyMountedException("POD already mounted: " + podFileName);
            }
        }

        List<String> newLines = new ArrayList<>(lines.size() + 1);
        newLines.add(String.valueOf(count + 1));
        newLines.addAll(lines.subList(1, lines.size()));
        newLines.add(entryLabel);

        Path workPath = iniPath.resolveSibling(POD_WRK);
        Files.write(workPath, newLines);
        Files.move(workPath, iniPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private static int parseCount(String line) {
        try {
            return Integer.parseInt(line.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // --- Typed exceptions so callers can display appropriate messages ---

    public static final class AlreadyMountedException extends IOException {
        public AlreadyMountedException(String msg) { super(msg); }
    }

    public static final class MaxPodsExceededException extends IOException {
        public MaxPodsExceededException(String msg) { super(msg); }
    }

    public static final class PodIniNotFoundException extends IOException {
        public PodIniNotFoundException(String msg) { super(msg); }
    }
}
