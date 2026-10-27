package com.mtm2.jpod.io.model;

import com.mtm2.jpod.io.pod.PodArchive;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the models an animated BIN names as its frames.
 *
 * <p>Frames are stored as bare file names, so they are looked up the way the game looks
 * them up: in {@code MODELS} first, then anywhere in the archive. A frame that resolves
 * from another pod entirely is invisible here, which is exactly the case that makes an
 * animation work for its author and fail for everyone else.
 */
public final class BinFrameResolver {

    private static final String[] SEARCH_FOLDERS = {"MODELS", "ART", "DATA"};

    /** Where one frame lives in the archive; {@code entry} is null when it is not there. */
    public record Frame(String name, PodArchive.Entry entry) {}

    private BinFrameResolver() {}

    public static List<Frame> resolve(PodArchive archive, BinModel model) {
        List<Frame> frames = new ArrayList<>(model.frameNames().size());
        for (String name : model.frameNames()) {
            frames.add(new Frame(name, archive == null ? null : find(archive, name)));
        }
        return frames;
    }

    private static PodArchive.Entry find(PodArchive archive, String name) {
        String title = name.indexOf('.') >= 0 ? name : name + ".BIN";
        for (String folder : SEARCH_FOLDERS) {
            Optional<PodArchive.Entry> entry = archive.findEntry(folder + "\\" + title);
            if (entry.isEmpty()) entry = archive.findEntry(folder + "/" + title);
            if (entry.isPresent()) return entry.get();
        }
        Optional<PodArchive.Entry> rooted = archive.findEntry(title);
        return rooted.isPresent() ? rooted.get() : archive.findEntryByTitle(title).orElse(null);
    }
}
