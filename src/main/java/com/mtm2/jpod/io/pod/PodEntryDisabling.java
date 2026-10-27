package com.mtm2.jpod.io.pod;

import java.util.Locale;

/**
 * CommPatch 26 hides a track or truck from the game by renaming it to an extension
 * the engine does not recognise, and the engine passes over what it cannot read.
 *
 * <p>Nothing is deleted: {@code .TRK}, {@code .SIT} and {@code .SI2} become
 * {@code .TRX}, {@code .SIX} and {@code .SIY}, and renaming them back restores the
 * addon exactly as it was. Changing the status of a track pod can trigger the
 * multiplayer <em>Different Version</em> message, which is why this is a rename
 * rather than a removal.
 */
public final class PodEntryDisabling {

    private static final String[][] PAIRS = {
            {".TRK", ".TRX"},
            {".SIT", ".SIX"},
            {".SI2", ".SIY"},
    };

    private PodEntryDisabling() {}

    /** True when the entry is a track or truck the game currently reads. */
    public static boolean canDisable(String name) {
        return disable(name) != null;
    }

    /** True when the entry is a track or truck that has been renamed out of the way. */
    public static boolean canEnable(String name) {
        return enable(name) != null;
    }

    /** The disabled name for a track or truck entry, or null when the entry is neither. */
    public static String disable(String name) {
        return swap(name, false);
    }

    /** The enabled name for a disabled track or truck entry, or null otherwise. */
    public static String enable(String name) {
        return swap(name, true);
    }

    private static String swap(String name, boolean enable) {
        String entry = name == null ? "" : name;
        String upper = entry.toUpperCase(Locale.ROOT);
        for (String[] pair : PAIRS) {
            String from = enable ? pair[1] : pair[0];
            if (upper.endsWith(from)) {
                String to = enable ? pair[0] : pair[1];
                // The archive's own casing is kept: a pod written in lower case stays
                // that way, so the rename survives a round trip through the entry list
                // unchanged in every other respect.
                String replacement = Character.isLowerCase(entry.charAt(entry.length() - 1))
                        ? to.toLowerCase(Locale.ROOT) : to;
                return entry.substring(0, entry.length() - from.length()) + replacement;
            }
        }
        return null;
    }
}
