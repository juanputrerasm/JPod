package com.mtm2.jpod.io.model;

import com.mtm2.jpod.io.pod.PodArchive;

import java.util.Locale;
import java.util.Optional;

/** Resolves the archive assets referenced by a model's texture name. */
public final class BinTextureResolver {

    private static final String[] SEARCH_FOLDERS = {"ART", "MODELS", "DATA", "TEXTURES"};

    /**
     * The entries behind one texture name.
     *
     * @param diffuse         the art itself: PNG, then TGA, then TIF, then RAW
     * @param normal          a {@code _N.PNG} or {@code _N.TGA} normal map
     * @param sameNamePalette the {@code .ACT} beside a RAW diffuse
     * @param opacityPlane    the 4x4 Evolution {@code .OPA} beside a RAW diffuse. A real
     *                        0..255 gradient rather than a mask, so it is merged into the
     *                        decoded alpha instead of being reduced to the MTM colour key
     */
    public record Entries(PodArchive.Entry diffuse, PodArchive.Entry normal,
                          PodArchive.Entry sameNamePalette, PodArchive.Entry opacityPlane) {}

    private BinTextureResolver() {}

    public static Entries resolve(PodArchive archive, String textureName) {
        String stem = stem(textureName);
        PodArchive.Entry png = find(archive, stem, ".PNG");
        PodArchive.Entry tga = find(archive, stem, ".TGA");
        // Evo 2 model art is palette-indexed .TIF. It ranks below the HD forms and above
        // .RAW, the way a material names it.
        PodArchive.Entry tif = find(archive, stem, ".TIF");
        PodArchive.Entry raw = find(archive, stem, ".RAW");
        PodArchive.Entry diffuse = png != null ? png : tga != null ? tga : tif != null ? tif : raw;
        PodArchive.Entry normal = find(archive, stem + "_N", ".PNG");
        if (normal == null) normal = find(archive, stem + "_N", ".TGA");
        PodArchive.Entry act = null;
        PodArchive.Entry opacity = null;
        if (raw != null) {
            act = findBeside(archive, raw, ".ACT");
            if (act == null) act = find(archive, stem, ".ACT");
            opacity = findBeside(archive, raw, ".OPA");
            if (opacity == null) opacity = find(archive, stem, ".OPA");
        }
        return new Entries(diffuse, normal, act, opacity);
    }

    /** The file name without folder or extension, upper-cased. */
    public static String stem(String name) {
        String normalized = (name == null ? "" : name).replace('\\', '/').trim();
        int slash = normalized.lastIndexOf('/');
        String title = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = title.lastIndexOf('.');
        return (dot > 0 ? title.substring(0, dot) : title).toUpperCase(Locale.ROOT);
    }

    static PodArchive.Entry find(PodArchive archive, String stem, String extension) {
        String title = stem + extension;
        for (String folder : SEARCH_FOLDERS) {
            Optional<PodArchive.Entry> entry = archive.findEntry(folder + "\\" + title);
            if (entry.isEmpty()) entry = archive.findEntry(folder + "/" + title);
            if (entry.isPresent()) return entry.get();
        }
        Optional<PodArchive.Entry> rooted = archive.findEntry(title);
        if (rooted.isPresent()) return rooted.get();
        return archive.findEntryByTitle(title).orElse(null);
    }

    private static PodArchive.Entry findBeside(PodArchive archive, PodArchive.Entry entry, String extension) {
        int dot = entry.name().lastIndexOf('.');
        return dot < 0 ? null : archive.findEntry(entry.name().substring(0, dot) + extension).orElse(null);
    }
}
