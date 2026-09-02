package com.mtm2.jpod.io.pod;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Shared pre-save and opened-archive validation for POD1/POD1-64/POD2. */
public final class PodArchiveValidator {
    private PodArchiveValidator() {}

    public record Result(PodArchive.Format outputFormat, List<String> errors,
                         List<String> warnings) {
        public boolean isValid() { return errors.isEmpty(); }
    }

    public static Result validateForSave(String comment, List<PodArchiveWriter.Blob> blobs,
            PodArchive.Format requested) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        PodArchive.Format actual = requested;
        try {
            actual = PodArchiveWriter.actualFormat(blobs, requested);
            new PodArchiveWriter().buildBytes(comment, blobs,
                    new PodArchiveWriter.WriteOptions(requested, null, List.of()));
        } catch (IOException | IllegalArgumentException | ArithmeticException ex) {
            errors.add(ex.getMessage());
        }
        if (actual == PodArchive.Format.POD1_64 && requested == PodArchive.Format.POD1) {
            warnings.add("The archive requires the Extended POD1 directory.");
        }
        return new Result(actual, List.copyOf(errors), List.copyOf(warnings));
    }

    public static Result validateOpened(PodArchive archive) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        HashSet<String> names = new HashSet<>();
        for (PodArchive.Entry entry : archive.getEntries()) {
            try {
                PodArchiveWriter.validatePath(PodArchiveWriter.normalizeName(entry.name()));
            } catch (IOException ex) {
                errors.add(ex.getMessage());
            }
            if (!names.add(entry.name().toUpperCase(Locale.ROOT))) {
                errors.add("Duplicate POD entry name: " + entry.name());
            }
            String palette = entry.embeddedPaletteName();
            if (palette != null && !palette.toUpperCase(Locale.ROOT).endsWith(".ACT")) {
                warnings.add("Malformed embedded palette name for " + entry.name() + ": " + palette);
            }
            if (!archive.isEntryChecksumValid(entry)) {
                errors.add("POD2 entry checksum mismatch: " + entry.name());
            }
        }
        if (!archive.isChecksumValid()) errors.add("POD2 archive checksum mismatch");
        List<PodArchive.Entry> byOffset = new ArrayList<>(archive.getEntries());
        byOffset.sort(Comparator.comparingLong(PodArchive.Entry::offset));
        for (int i = 1; i < byOffset.size(); i++) {
            PodArchive.Entry previous = byOffset.get(i - 1);
            PodArchive.Entry current = byOffset.get(i);
            if (previous.offset() + previous.length() > current.offset()) {
                errors.add("Overlapping POD entries: " + previous.name() + " and " + current.name());
            }
        }
        return new Result(archive.getFormat(), List.copyOf(errors), List.copyOf(warnings));
    }
}
