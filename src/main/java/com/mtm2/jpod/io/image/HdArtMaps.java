package com.mtm2.jpod.io.image;

import java.util.Locale;

/**
 * The derived-map suffixes CommPatch 26 resolves beside an HD texture stem.
 *
 * <p>A texture is looked up by stem, so {@code FOO.PNG} carries {@code FOO_N},
 * {@code FOO_AO}, {@code FOO_DTL} and {@code FOO_MASK} into a pod with it. Those
 * companions are ordinary art files, and without the suffix spelled out the entry
 * list gives no hint that they are maps rather than pictures.
 */
public final class HdArtMaps {

    /** Longest first, so a stem is never matched by a suffix that is only part of its own. */
    private static final String[][] ROLES = {
            {"_MASK", "terrain detail mask"},
            {"_DTL", "terrain detail normal"},
            {"_AO", "ambient occlusion"},
            {"_N", "normal map"},
    };

    private HdArtMaps() {}

    /**
     * The map role the file name's stem declares, or null when the name is a plain
     * texture. The extension is ignored: the suffix sits on the stem.
     */
    public static String role(String name) {
        String stem = stem(name).toUpperCase(Locale.ROOT);
        for (String[] role : ROLES) {
            // A bare "_N.PNG" has no stem to be a map of, so the suffix has to be
            // carried by something.
            if (stem.length() > role[0].length() && stem.endsWith(role[0])) {
                return role[1];
            }
        }
        return null;
    }

    /**
     * The description with the map role folded in. A description that already ends
     * in a parenthetical gains the role inside it, so a RAW keeps one set of brackets
     * rather than two.
     */
    public static String annotate(String description, String name) {
        String role = role(name);
        if (role == null) {
            return description;
        }
        return description.endsWith(")")
                ? description.substring(0, description.length() - 1) + ", " + role + ")"
                : description + " (" + role + ")";
    }

    private static String stem(String name) {
        String normalized = name == null ? "" : name;
        int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        String title = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = title.lastIndexOf('.');
        return dot > 0 ? title.substring(0, dot) : title;
    }
}
