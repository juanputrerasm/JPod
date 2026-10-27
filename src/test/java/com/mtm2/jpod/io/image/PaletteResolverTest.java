package com.mtm2.jpod.io.image;

import com.mtm2.jpod.io.RawImageDecoder;
import com.mtm2.jpod.io.pod.PodArchive;
import com.mtm2.jpod.io.pod.PodArchiveReader;
import com.mtm2.jpod.io.pod.PodArchiveWriter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaletteResolverTest {

    @Test
    void theSameNameActWinsOverEveryOtherPalette() throws Exception {
        PodArchive archive = archive(
                "ART\\DEMO1.RAW", new byte[16],
                "ART\\DEMO1.ACT", solidAct(63, 0, 0),
                "VGA.ACT", solidAct(0, 63, 0));
        assertEquals(0xFFFF0000, PaletteResolver.resolve("ART\\DEMO1.RAW", archive, null)[0]);
    }

    @Test
    void aDifferentlyNamedActIsNeverGuessedAtButIsStillOffered() throws Exception {
        PodArchive archive = archive(
                "ART\\DEMO1.RAW", new byte[16],
                "ART\\OTHER.ACT", solidAct(0, 0, 63));
        assertArrayEquals(RawImageDecoder.loadResourcePalette(),
                PaletteResolver.resolve("ART\\DEMO1.RAW", archive, null));

        PaletteResolver.Choices choices = PaletteResolver.resolveChoices("ART\\DEMO1.RAW", archive, null);
        assertEquals("METALCR2 (MTM1)", choices.choices().get(choices.defaultIndex()).label());
        assertTrue(choices.choices().get(choices.choices().size() - 1).label().endsWith("ART\\OTHER.ACT"));
    }

    @Test
    void theArchivesOwnMetalcr2IsPreferredOverItsVgaAndOverTheBundledCopy() throws Exception {
        // METALCR2 is not one palette: CPR ships a different one from MTM1 and MTM2.
        PodArchive archive = archive(
                "ART\\DEMO1.RAW", new byte[16],
                "METALCR2.ACT", solidAct(63, 63, 0),
                "VGA.ACT", solidAct(0, 63, 0));
        assertEquals(0xFFFFFF00, PaletteResolver.resolve("ART\\DEMO1.RAW", archive, null)[0]);
    }

    @Test
    void aVgaActAloneMeansAFlightGameAndIsUsedAsIs() throws Exception {
        PodArchive archive = archive(
                "ART\\DEMO1.RAW", new byte[16],
                "VGA.ACT", solidAct(0, 63, 0));
        assertEquals(0xFF00FF00, PaletteResolver.resolve("ART\\DEMO1.RAW", archive, null)[0]);
    }

    @Test
    void withNoArchiveTheBundledPaletteIsUsed() {
        assertArrayEquals(RawImageDecoder.loadResourcePalette(),
                PaletteResolver.resolve("LOOSE.RAW", null, null));
    }

    @Test
    void choicesPutTheSameNameActFirstAndAlwaysOfferGreyscale() throws Exception {
        PodArchive archive = archive(
                "ART\\DEMO1.RAW", new byte[16],
                "ART\\DEMO1.ACT", solidAct(63, 0, 0),
                "ART\\OTHER.ACT", solidAct(0, 0, 63));
        PaletteResolver.Choices choices = PaletteResolver.resolveChoices("ART\\DEMO1.RAW", archive, null);
        assertEquals(0, choices.defaultIndex());
        assertTrue(choices.choices().get(0).label().startsWith("Same-name ACT:"));
        assertTrue(choices.indexOf("Greyscale") >= 0);
    }

    @Test
    void withNoArchiveMtm1IsTheDefaultAndEveryBundledPaletteIsOffered() {
        PaletteResolver.Choices choices = PaletteResolver.resolveChoices("LOOSE.RAW", null, null);
        assertEquals("METALCR2 (MTM1)", choices.choices().get(choices.defaultIndex()).label());
        for (String label : List.of("METALCR2 (CPR)", "VGA (Hellbender)", "VGA (TV/F3)", "Greyscale")) {
            assertTrue(choices.indexOf(label) >= 0, label);
        }
    }

    @Test
    void bundledPalettesAreCompleteAndDistinct() {
        int[][] palettes = {
                BundledPalettes.metalCr2Mtm1(), BundledPalettes.metalCr2Cpr(),
                BundledPalettes.vgaHellbender(), BundledPalettes.vgaTerminalVelocity(),
        };
        for (int[] palette : palettes) assertEquals(256, palette.length);
        assertEquals(4, Arrays.stream(palettes).map(Arrays::toString).distinct().count());
    }

    @Test
    void thePaletteStoredInTheDirectoryFieldBeatsEveryGuess() throws Exception {
        // MTM1, Terminal Velocity, Fury3 and Hellbender record the palette a RAW was
        // authored against in the spare bytes of its directory field.
        PodArchive archive = archiveWithStoredPalette("ART\\DEMO1.RAW", "MORBOS.ACT",
                "ART\\DEMO1.ACT", solidAct(63, 0, 0),
                "ART\\MORBOS.ACT", solidAct(0, 63, 0));
        PodArchive.Entry raw = archive.getEntries().get(0);
        assertEquals("MORBOS.ACT", raw.embeddedPaletteName());
        assertEquals(0xFF00FF00, PaletteResolver.resolve(raw.name(), archive, raw.nameField())[0]);

        PaletteResolver.Choices choices = PaletteResolver.resolveChoices(raw.name(), archive, raw.nameField());
        assertEquals(0, choices.defaultIndex());
        assertTrue(choices.choices().get(0).label().startsWith("Stored in the archive:"));
        assertTrue(choices.choices().stream().anyMatch(c -> c.label().startsWith("Same-name ACT:")));
    }

    @Test
    void aStoredNameThatIsNotInTheArchiveFallsBackToTheHeuristic() throws Exception {
        PodArchive archive = archiveWithStoredPalette("ART\\DEMO1.RAW", "ABSENT.ACT",
                "ART\\DEMO1.ACT", solidAct(63, 0, 0));
        PodArchive.Entry raw = archive.getEntries().get(0);
        assertEquals(0xFFFF0000, PaletteResolver.resolve(raw.name(), archive, raw.nameField())[0]);
    }

    private static PodArchive archiveWithStoredPalette(String rawName, String paletteName,
            Object... rest) throws Exception {
        Object[] files = new Object[rest.length + 2];
        files[0] = rawName;
        files[1] = new byte[16];
        System.arraycopy(rest, 0, files, 2, rest.length);
        byte[] bytes = build(files);
        byte[] stored = paletteName.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(stored, 0, bytes, 84 + rawName.length() + 1, stored.length);
        return new PodArchiveReader().read(bytes);
    }

    static byte[] solidAct(int r, int g, int b) {
        byte[] act = new byte[RawImageDecoder.ACT_PALETTE_BYTES];
        for (int i = 0; i < 256; i++) {
            act[i * 3] = (byte) r;
            act[i * 3 + 1] = (byte) g;
            act[i * 3 + 2] = (byte) b;
        }
        return act;
    }

    /** Builds a POD1 archive from alternating names and payloads. */
    static byte[] build(Object... namesAndPayloads) throws Exception {
        List<PodArchiveWriter.Blob> blobs = new ArrayList<>();
        for (int i = 0; i < namesAndPayloads.length; i += 2) {
            blobs.add(new PodArchiveWriter.Blob((String) namesAndPayloads[i],
                    (byte[]) namesAndPayloads[i + 1], null, null, 0));
        }
        return new PodArchiveWriter().buildBytes("", blobs);
    }

    static PodArchive archive(Object... namesAndPayloads) throws Exception {
        return new PodArchiveReader().read(build(namesAndPayloads));
    }
}
