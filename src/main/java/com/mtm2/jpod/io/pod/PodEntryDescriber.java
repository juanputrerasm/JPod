package com.mtm2.jpod.io.pod;

import com.mtm2.jpod.io.RawImageDecoder;
import com.mtm2.jpod.io.image.HdArtMaps;

import java.util.Locale;

/**
 * Turns an entry name and size into the human-readable description shown in the
 * third column of the entry list. Uses the size only, never the payload, so the list
 * can be described without reading the archive.
 */
public final class PodEntryDescriber {

    private PodEntryDescriber() {}

    public static String describe(String name, long byteCount) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return switch (ext) {
            case "act" -> "VGA palette";
            case "ani" -> "Animated texture table";
            case "bin" -> "3D model file";
            case "bmp" -> "Bitmap image";
            case "cl0" -> "Ground box face textures layer 0";
            case "cl1" -> "Ground box face textures layer 1";
            case "cl2" -> "Ground box face textures layer 2";
            case "cl3" -> "Ground box face textures layer 3";
            case "cl4" -> "Ground box face textures layer 4";
            case "cl5" -> "Ground box face textures layer 5";
            case "clr" -> "Terrain color layer";
            case "crs" -> "LVL auxiliary resource";
            case "def" -> "Object placement definition";
            case "dmo" -> "Demo manifest";
            case "gif" -> "GIF image";
            case "glt" -> "Glow/light table";
            case "grm" -> "Terrain geometry data";
            case "fog" -> "Fog density/color index";
            case "jpeg", "jpg" -> "JPEG image";
            case "json" -> "JSON data";
            case "klp" -> "Audio clip data";
            case "lte" -> "Terrain lighting grid/Fog grid";
            case "lvl" -> "Level definition file";
            case "lwo" -> "LightWave 3D object";
            case "map" -> "Map palette data";
            case "mic" -> "TV/F3 Intro text";
            case "mix" -> "MIX file";
            case "mod" -> "Module audio (MOD)";
            case "pit" -> "CPR Pits file";
            case "png" -> HdArtMaps.annotate("PNG image", name);
            case "pod" -> "POD archive";
            case "zip" -> "ZIP archive";
            case "ra0" -> "Ground box height layer 0";
            case "ra1" -> "Ground box height layer 1";
            case "ra2" -> "Ground box height layer 2";
            case "ra3" -> "Ground box height layer 3";
            case "ra4" -> "Ground box height layer 4";
            case "ra5" -> "Ground box height layer 5";
            case "opa" -> opacityDescription(byteCount);
            case "raw" -> HdArtMaps.annotate(rawDescription(byteCount), name);
            case "rtd" -> "4x4 Evo terrain auxiliary grid";
            case "smf" -> "3D model file (4x4 Evo C3DModel)";
            case "tif", "tiff" -> "TIFF image";
            case "veg" -> "4x4 Evo vegetation placement";
            case "wat" -> "4x4 Evo water material";
            case "sdw" -> "4x4 Evo shadow overlay grid";
            case "sit" -> "Track situation file";
            case "si2" -> "Track situation file (CommPatch 3+ engine)";
            case "six" -> "Track situation file (disabled)";
            case "siy" -> "Track situation file (CommPatch 3+ engine, disabled)";
            case "txv" -> "Track version manifest";
            case "smk" -> "Smacker video";
            case "tga" -> HdArtMaps.annotate("Targa image", name);
            case "aai" -> "Anti Alias information";
            case "car" -> "CPR Vehicle definition file";
            case "cmd" -> "CPR car data";
            case "dvp" -> "Developer/debug data";
            case "jsin" -> "JSON instrument data";
            case "loc" -> "Localization file";
            case "lvo" -> "Level object data";
            case "nav" -> "Navigation data";
            case "ndx" -> "Font data";
            case "rgn" -> "CPR dialog data";
            case "200" -> "320x200 cockpit data";
            case "400" -> "640x400 cockpit data";
            case "480" -> "640x480 cockpit data";
            case "tex" -> "Texture manifest";
            case "tnl" -> "Tunnel definition file";
            case "trk" -> isDataSubfolderEntry(name) ? "CPR track definition file" : "Truck definition file";
            case "trx" -> isDataSubfolderEntry(name)
                    ? "CPR track definition file (disabled)"
                    : "Truck definition file (disabled)";
            case "trn" -> "Tournament definition file";
            case "ttx" -> "CPR Race-track texture table";
            case "txx" -> "Traxx track file";
            case "txp" -> "Texture pattern library";
            case "txt" -> "Text file";
            case "tty" -> "Texture data";
            case "tvi" -> "TRI video info";
            case "wav" -> "Wave audio file";
            case "webp" -> "WebP image";
            case "lst" -> "Response list file";
            case "inf" -> "Info report";
            case "ini", "cfg" -> "Configuration file";
            default -> ext.isEmpty() ? "Binary data" : ext.toUpperCase(Locale.ROOT) + " file";
        };
    }

    /**
     * An .OPA is an unheadered byte per pixel like a .RAW, so its side length is worth
     * the same read-out; without it a 65,536-byte opacity plane says nothing about its
     * size.
     */
    private static String opacityDescription(long byteCount) {
        int[] dims = byteCount > Integer.MAX_VALUE ? null
                : RawImageDecoder.detectDimensions((int) byteCount);
        return dims == null
                ? "Opacity plane (non-standard size)"
                : "Opacity plane (" + dims[0] + "x" + dims[1] + ")";
    }

    private static String rawDescription(long byteCount) {
        int[] dims = byteCount > Integer.MAX_VALUE ? null
                : RawImageDecoder.detectDimensions((int) byteCount);
        if (dims == null) {
            return "RAW image data (non-standard size)";
        }
        int width = dims[0];
        int height = dims[1];
        if ((width == 64 && height == 64) || (width == 256 && height == 256)
                || (width == 320 && height == 200) || (width == 640 && height == 400)
                || (width == 640 && height == 480)) {
            return "RAW image data (" + width + "x" + height + ")";
        }
        return "RAW image data (non-standard " + width + "x" + height + ")";
    }

    /**
     * True when the entry lives under a {@code DATA} folder, which is how a CPR track
     * definition is told apart from an MTM truck definition. Only segments with a
     * separator after them are folders, so the file name itself never matches.
     */
    private static boolean isDataSubfolderEntry(String entryName) {
        int start = 0;
        for (int i = 0; i < entryName.length(); i++) {
            char c = entryName.charAt(i);
            if (c != '/' && c != '\\') {
                continue;
            }
            if (i - start == 4 && entryName.regionMatches(true, start, "DATA", 0, 4)) {
                return true;
            }
            start = i + 1;
        }
        return false;
    }
}
