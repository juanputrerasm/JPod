package com.mtm2.jpod.io.image;

import com.mtm2.jpod.io.RawImageDecoder;
import com.mtm2.jpod.io.pod.PodArchive;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the palette to draw a RAW or CLR entry with. Pure archive logic, kept out of
 * the UI so it can be tested headless.
 */
public final class PaletteResolver {

    private static final String VGA_PALETTE = "VGA.ACT";
    private static final String METAL_PALETTE = "METALCR2.ACT";

    /** Label of the bundled MTM1 palette, the default when the archive offers nothing. */
    public static final String BUNDLED_MTM1_LABEL = "METALCR2 (MTM1)";

    /** One selectable palette, with the label the UI shows for it. */
    public record Choice(String label, int[] palette) {
        @Override public String toString() { return label; }
    }

    /** The palettes offered for a RAW preview, and which one starts selected. */
    public record Choices(List<Choice> choices, int defaultIndex) {
        /** Index of the choice with this label, or -1. */
        public int indexOf(String label) {
            for (int i = 0; i < choices.size(); i++) {
                if (choices.get(i).label().equals(label)) return i;
            }
            return -1;
        }
    }

    private PaletteResolver() {}

    /**
     * Resolves the palette for a RAW or CLR entry, in priority order:
     * <ol>
     *   <li>the palette named in the entry's own directory field</li>
     *   <li>the same base name with an {@code .act} extension</li>
     *   <li>{@code METALCR2.ACT} anywhere in the archive</li>
     *   <li>{@code VGA.ACT} anywhere in the archive</li>
     *   <li>the bundled {@code metalcr2.act} resource</li>
     * </ol>
     *
     * <p>The archive's own {@code METALCR2.ACT} outranks the bundled copy because they
     * are not the same file: CPR ships a different METALCR2 from MTM1 and MTM2, and
     * taking the one the pod carries is right whichever game it came from. A
     * {@code VGA.ACT} in the pod says the archive belongs to one of the flight games,
     * and since nothing distinguishes Terminal Velocity from Fury3 or Hellbender at
     * this point, that copy is the only one that can be trusted.
     *
     * <p>An {@code .act} whose name matches nothing is never guessed at. Picking
     * whichever differently-named palette happened to sit in the same folder was a coin
     * toss; those are offered last in the choice list and chosen by hand.
     *
     * <p>Step one matters more than its position suggests. Where the packer wrote a
     * palette name, the rules below it are close to useless: on Hellbender's
     * {@code GAME.POD}, Fury3's {@code FURY3.POD}, MTM1's {@code GAME.POD} and Terminal
     * Velocity's {@code CDROM.POD} the stored name disagrees with a same-directory guess
     * for all but three of 8 224 RAW entries.
     *
     * @param nameField the entry's directory field, when the caller has it; supplying
     *                  it is what enables step one
     */
    public static int[] resolve(String entryName, PodArchive archive, byte[] nameField) {
        if (archive != null) {
            Optional<PodArchive.Entry> stored = findStoredPalette(archive, nameField);
            if (stored.isPresent()) return tryDecodeAct(archive, stored.get());

            int dot = entryName.lastIndexOf('.');
            if (dot > 0) {
                Optional<PodArchive.Entry> exact = archive.findEntry(entryName.substring(0, dot) + ".act");
                if (exact.isPresent()) return tryDecodeAct(archive, exact.get());
            }

            Optional<PodArchive.Entry> metal = findArchivePalette(archive, METAL_PALETTE);
            if (metal.isPresent()) return tryDecodeAct(archive, metal.get());

            Optional<PodArchive.Entry> vga = findArchivePalette(archive, VGA_PALETTE);
            if (vga.isPresent()) return tryDecodeAct(archive, vga.get());
        }
        return RawImageDecoder.loadResourcePalette();
    }

    /**
     * The complete palette list offered by every RAW preview. Its default follows
     * {@link #resolve}, the four bundled game palettes and greyscale are always
     * available, and every other {@code .act} in the archive is offered last for
     * correcting an ambiguous archive by hand.
     */
    public static Choices resolveChoices(String entryName, PodArchive archive, byte[] nameField) {
        List<Choice> choices = new ArrayList<>();
        Set<String> added = new HashSet<>();
        int defaultIndex = -1;

        if (archive != null) {
            Optional<PodArchive.Entry> stored = findStoredPalette(archive, nameField);
            if (stored.isPresent() && addArchive(choices, added, archive, stored.get(),
                    "Stored in the archive: " + stored.get().name())) {
                defaultIndex = choices.size() - 1;
            }

            int dot = entryName.lastIndexOf('.');
            if (dot > 0) {
                Optional<PodArchive.Entry> sameName = archive.findEntry(entryName.substring(0, dot) + ".act");
                if (sameName.isPresent() && addArchive(choices, added, archive, sameName.get(),
                        "Same-name ACT: " + sameName.get().name()) && defaultIndex < 0) {
                    defaultIndex = choices.size() - 1;
                }
            }

            Optional<PodArchive.Entry> metal = findArchivePalette(archive, METAL_PALETTE);
            if (metal.isPresent() && addArchive(choices, added, archive, metal.get(),
                    "Archive " + metal.get().name()) && defaultIndex < 0) {
                defaultIndex = choices.size() - 1;
            }

            Optional<PodArchive.Entry> vga = findArchivePalette(archive, VGA_PALETTE);
            if (vga.isPresent() && addArchive(choices, added, archive, vga.get(),
                    "Archive " + vga.get().name()) && defaultIndex < 0) {
                defaultIndex = choices.size() - 1;
            }
        }

        int mtm1Index = choices.size();
        choices.add(new Choice(BUNDLED_MTM1_LABEL, BundledPalettes.metalCr2Mtm1()));
        choices.add(new Choice("METALCR2 (CPR)", BundledPalettes.metalCr2Cpr()));
        choices.add(new Choice("VGA (Hellbender)", BundledPalettes.vgaHellbender()));
        choices.add(new Choice("VGA (TV/F3)", BundledPalettes.vgaTerminalVelocity()));
        choices.add(new Choice("Greyscale", RawImageDecoder.greyscalePalette()));
        if (defaultIndex < 0) {
            defaultIndex = mtm1Index;
        }

        // Every remaining palette in the archive, last and never the default. These are
        // the differently-named ones: real enough to be worth offering, never a safe guess.
        if (archive != null) {
            for (PodArchive.Entry entry : archive.getEntries()) {
                if (entry.name().toUpperCase(Locale.ROOT).endsWith(".ACT")) {
                    addArchive(choices, added, archive, entry, "Archive ACT: " + entry.name());
                }
            }
        }
        return new Choices(List.copyOf(choices), defaultIndex);
    }

    private static boolean addArchive(List<Choice> choices, Set<String> added, PodArchive archive,
            PodArchive.Entry entry, String label) {
        if (!added.add(entry.name().toUpperCase(Locale.ROOT))) {
            return false;
        }
        choices.add(new Choice(label, tryDecodeAct(archive, entry)));
        return true;
    }

    /**
     * The archive entry for the palette named in a directory field, or empty when the
     * field names none or the archive does not hold it.
     */
    private static Optional<PodArchive.Entry> findStoredPalette(PodArchive archive, byte[] nameField) {
        String palette = PodArchive.Entry.secondString(nameField);
        if (palette == null || !palette.toUpperCase(Locale.ROOT).endsWith(".ACT")) {
            return Optional.empty();
        }
        return findArchivePalette(archive, palette);
    }

    private static Optional<PodArchive.Entry> findArchivePalette(PodArchive archive, String fileName) {
        Optional<PodArchive.Entry> entry = archive.findEntry(fileName);
        return entry.isPresent() ? entry : archive.findEntryByTitle(fileName);
    }

    private static int[] tryDecodeAct(PodArchive archive, PodArchive.Entry entry) {
        try {
            return RawImageDecoder.decodeAct(archive.getEntryBytes(entry));
        } catch (RuntimeException ex) {
            return RawImageDecoder.greyscalePalette();
        }
    }
}
