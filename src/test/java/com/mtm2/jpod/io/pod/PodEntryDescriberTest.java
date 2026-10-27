package com.mtm2.jpod.io.pod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class PodEntryDescriberTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "VGA.ACT|VGA palette",
            "ART\\WALL.BMP|Bitmap image",
            "SONG.MOD|Module audio (MOD)",
            "SKY.TGA|Targa image",
            "CAR.200|320x200 cockpit data",
            "SCRIPT.JSIN|JSON instrument data",
            "NOTES|Binary data",
            "THING.QQQ|QQQ file",
            "DATA\\LAGUNA.SI2|Track situation file (CommPatch 3+ engine)",
            "DATA\\LAGUNA.SIX|Track situation file (disabled)",
            "DATA\\LAGUNA.SIY|Track situation file (CommPatch 3+ engine, disabled)",
            "DATA\\LAGUNA.TXV|Track version manifest",
            "MODELS\\PALMFAN.SMF|3D model file (4x4 Evo C3DModel)",
            "ART\\PALMFAN.TIF|TIFF image",
            "DATA\\PEAK.VEG|4x4 Evo vegetation placement",
            "DATA\\PEAK.WAT|4x4 Evo water material",
            "DATA\\PEAK.SDW|4x4 Evo shadow overlay grid",
            "DATA\\PEAK.RTD|4x4 Evo terrain auxiliary grid",
            "TRUCKS\\BIGFOOT.TRK|Truck definition file",
            "DATA\\LAGUNA.TRK|CPR track definition file",
            "TRUCKS\\BIGFOOT.TRX|Truck definition file (disabled)",
            "DATA\\LAGUNA.TRX|CPR track definition file (disabled)",
            "ART\\ROCK_N.TGA|Targa image (normal map)",
            "ART\\ROCK_AO.PNG|PNG image (ambient occlusion)",
            "ART\\ROCK_DTL.PNG|PNG image (terrain detail normal)",
            "ART\\ROCK_MASK.PNG|PNG image (terrain detail mask)",
            "ART\\ROCK.PNG|PNG image",
            "ART\\_N.PNG|PNG image",
    })
    void describesByName(String name, String expected) {
        assertEquals(expected, PodEntryDescriber.describe(name, 0));
    }

    @Test
    void aRawMapRoleJoinsTheDimensionsInsideOneBracket() {
        assertEquals("RAW image data (256x256, normal map)",
                PodEntryDescriber.describe("ART\\ROCK_N.RAW", 65536));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "4096|RAW image data (64x64)",
            "65536|RAW image data (256x256)",
            "1024|RAW image data (non-standard 32x32)",
            "64000|RAW image data (320x200)",
            "256000|RAW image data (640x400)",
            "307200|RAW image data (640x480)",
    })
    void rawSizeDrivesTheRawDescription(int byteCount, String expected) {
        assertEquals(expected, PodEntryDescriber.describe("TEX.RAW", byteCount));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "65536|Opacity plane (256x256)",
            "16384|Opacity plane (128x128)",
            "1234|Opacity plane (non-standard size)",
    })
    void describesAnOpacityPlaneBySize(int byteCount, String expected) {
        assertEquals(expected, PodEntryDescriber.describe("ART\\AS3PINE1.OPA", byteCount));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "TRUCKS\\BIGFOOT.TRK|TRUCKS\\BIGFOOT.TRX",
            "LAGUNA.SIT|LAGUNA.SIX",
            "LAGUNA.SI2|LAGUNA.SIY",
    })
    void disablingSwapsTheExtensionAndEnablingPutsItBack(String enabled, String disabled) {
        assertEquals(disabled, PodEntryDisabling.disable(enabled));
        assertEquals(enabled, PodEntryDisabling.enable(disabled));
        assertTrue(PodEntryDisabling.canDisable(enabled));
        assertTrue(PodEntryDisabling.canEnable(disabled));
        assertFalse(PodEntryDisabling.canEnable(enabled));
        assertFalse(PodEntryDisabling.canDisable(disabled));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ART\\WALL.RAW", "DATA\\LAGUNA.TXV", "BIGFOOT", ""})
    void everythingElseIsLeftAloneByDisabling(String name) {
        assertNull(PodEntryDisabling.disable(name));
        assertNull(PodEntryDisabling.enable(name));
    }

    @Test
    void theArchivesOwnCasingSurvivesTheRename() {
        assertEquals("trucks\\bigfoot.trx", PodEntryDisabling.disable("trucks\\bigfoot.trk"));
        assertEquals("trucks\\bigfoot.trk", PodEntryDisabling.enable("trucks\\bigfoot.trx"));
    }
}
