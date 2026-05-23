package com.mtm2.jpod;

import com.mtm2.jpod.io.pod.PodArchive;

import java.nio.file.Path;

/**
 * Holds the mutable state shared across services for the currently loaded POD archive.
 *
 * <p>A session object is passed to {@link com.mtm2.jpod.io.PodExtractService},
 * {@link com.mtm2.jpod.io.PodReportExporter}, and {@link com.mtm2.jpod.io.PodIniMounter}
 * so they can read source/target paths, the archive comment, and operation flags
 * without taking individual parameters for each value.
 */
public final class PodSession {

    /** Folder that contains the open source POD file. */
    private Path sourceFolderPath;

    /** Filename of the open source POD (e.g. "GAME.POD"). */
    private String sourceFileName;

    /** The parsed in-memory representation of the open archive, or null if none is open. */
    private PodArchive openArchive;

    /** Destination folder chosen by the user for extract / build / report operations. */
    private Path targetFolderPath;

    /** Output filename for build/export operations (without extension). */
    private String targetFileName;

    /** Comment stored in the 80-byte POD header field. */
    private String archiveComment = "";

    /** Total byte size of the open POD file on disk. */
    private long archiveByteSize;

    /** When true, extracted entries keep their POD folder structure under the chosen destination. */
    private boolean preserveExtractFolderStructure = true;

    /** True if any non-fatal issue occurred during the last operation (e.g. a missing source file). */
    private boolean hadOperationIssue;

    public PodSession() {}

    public Path getSourceFolderPath() { return sourceFolderPath; }
    public void setSourceFolderPath(Path sourceFolderPath) { this.sourceFolderPath = sourceFolderPath; }

    public String getSourceFileName() { return sourceFileName; }
    public void setSourceFileName(String sourceFileName) { this.sourceFileName = sourceFileName; }

    public Path getSourcePath() {
        if (sourceFolderPath == null || sourceFileName == null) return null;
        return sourceFolderPath.resolve(sourceFileName);
    }

    public PodArchive getOpenArchive() { return openArchive; }
    public void setOpenArchive(PodArchive openArchive) { this.openArchive = openArchive; }

    public boolean isArchiveOpen() { return openArchive != null; }

    public Path getTargetFolderPath() { return targetFolderPath; }
    public void setTargetFolderPath(Path targetFolderPath) { this.targetFolderPath = targetFolderPath; }

    public String getTargetFileName() { return targetFileName; }
    public void setTargetFileName(String targetFileName) { this.targetFileName = targetFileName; }

    public String getArchiveComment() { return archiveComment; }
    public void setArchiveComment(String archiveComment) {
        this.archiveComment = archiveComment != null ? archiveComment : "";
    }

    public long getArchiveByteSize() { return archiveByteSize; }
    public void setArchiveByteSize(long archiveByteSize) { this.archiveByteSize = archiveByteSize; }

    public boolean isPreserveExtractFolderStructure() { return preserveExtractFolderStructure; }
    public void setPreserveExtractFolderStructure(boolean preserveExtractFolderStructure) {
        this.preserveExtractFolderStructure = preserveExtractFolderStructure;
    }

    public boolean isHadOperationIssue() { return hadOperationIssue; }
    public void setHadOperationIssue(boolean hadOperationIssue) { this.hadOperationIssue = hadOperationIssue; }

    /** Clears all transient state; called when a new archive is opened. */
    public void reset() {
        openArchive = null;
        sourceFolderPath = null;
        sourceFileName = null;
        targetFolderPath = null;
        targetFileName = null;
        archiveComment = "";
        archiveByteSize = 0;
        preserveExtractFolderStructure = true;
        hadOperationIssue = false;
    }
}
