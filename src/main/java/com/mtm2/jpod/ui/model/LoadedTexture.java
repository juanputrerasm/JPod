package com.mtm2.jpod.ui.model;

import com.mtm2.jpod.io.RawImageDecoder;
import com.mtm2.jpod.io.image.TgaImageDecoder;
import com.mtm2.jpod.io.image.TiffImageDecoder;
import com.mtm2.jpod.io.model.BinModel;
import com.mtm2.jpod.io.model.BinTextureResolver;
import com.mtm2.jpod.io.pod.PodArchive;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One model texture as resolved from the archive and decoded.
 *
 * @param entry        the archive entry the diffuse art came from, or null when missing
 * @param diffuse      the decoded art, or null when it could not be found or read
 * @param normal       a decoded {@code _N} normal map, or null
 * @param isRaw        the art is an 8-bit RAW, which the MTM family colour-keys on black
 * @param usedFallback the RAW had no same-name ACT and was drawn with the selectable palette
 * @param warning      a missing asset, a decode failure or a non-standard HD size
 * @param hasAlpha     the art carries a real alpha channel: a RAW with its {@code .OPA}
 *                     merged in, or a two-sample TIF. Such art is alpha-tested on its own
 *                     channel and never colour-keyed
 */
public record LoadedTexture(String name, PodArchive.Entry entry, BufferedImage diffuse,
                            BufferedImage normal, boolean isRaw, boolean usedFallback,
                            String warning, boolean hasAlpha) {

    /** Resolves and decodes every texture a model names. */
    public static List<LoadedTexture> load(BinModel model, PodArchive archive, int[] fallbackPalette) {
        List<LoadedTexture> loaded = new ArrayList<>();
        for (String textureName : model.textureNames()) {
            loaded.add(load(textureName, archive, fallbackPalette));
        }
        return loaded;
    }

    private static LoadedTexture load(String textureName, PodArchive archive, int[] fallbackPalette) {
        if (archive == null) {
            return new LoadedTexture(textureName, null, null, null, false, false,
                    "No archive is available for texture lookup.", false);
        }
        BinTextureResolver.Entries entries = BinTextureResolver.resolve(archive, textureName);
        if (entries.diffuse() == null) {
            return new LoadedTexture(textureName, null, null, null, false, false,
                    "Missing texture: " + textureName, false);
        }

        String diffuseName = entries.diffuse().name().toUpperCase(Locale.ROOT);
        boolean raw = diffuseName.endsWith(".RAW");
        boolean tif = diffuseName.endsWith(".TIF");
        boolean usedFallback = false;
        boolean hasAlpha = false;
        String warning = null;
        BufferedImage diffuse = null;
        BufferedImage normal = null;
        try {
            byte[] bytes = archive.getEntryBytes(entries.diffuse());
            if (raw) {
                int[] dims = RawImageDecoder.detectDimensions(bytes.length);
                if (dims == null) {
                    throw new IllegalArgumentException("RAW texture has an unknown size: " + bytes.length + " bytes");
                }
                int[] palette;
                if (entries.sameNamePalette() != null) {
                    palette = RawImageDecoder.decodeAct(archive.getEntryBytes(entries.sameNamePalette()));
                } else {
                    palette = fallbackPalette;
                    usedFallback = true;
                }
                diffuse = RawImageDecoder.decodeRaw(bytes, palette, dims[0], dims[1]);
                // A 4x4 Evolution .OPA is a real gradient, merged into alpha rather than
                // reduced to the MTM colour key, which would harden soft foliage edges.
                if (entries.opacityPlane() != null) {
                    BufferedImage merged = RawImageDecoder.applyOpacityPlane(
                            diffuse, archive.getEntryBytes(entries.opacityPlane()));
                    hasAlpha = merged != diffuse;
                    diffuse = merged;
                }
            } else if (tif) {
                diffuse = TiffImageDecoder.decode(bytes, entries.diffuse().name());
                hasAlpha = TiffImageDecoder.hasAlphaSample(bytes, entries.diffuse().name());
            } else if (diffuseName.endsWith(".TGA")) {
                diffuse = TgaImageDecoder.decode(bytes, entries.diffuse().name());
            } else {
                diffuse = decodeImage(bytes, entries.diffuse().name());
            }

            if (entries.normal() != null) {
                byte[] normalBytes = archive.getEntryBytes(entries.normal());
                normal = entries.normal().name().toUpperCase(Locale.ROOT).endsWith(".TGA")
                        ? TgaImageDecoder.decode(normalBytes, entries.normal().name())
                        : decodeImage(normalBytes, entries.normal().name());
            }

            // The HD size rule is about PNG and TGA replacements. A TIF or RAW is the
            // game's own art in its own format, so it is exempt.
            if (!raw && !tif && !validHdSize(diffuse)) {
                warning = entries.diffuse().name() + " is " + diffuse.getWidth() + "x" + diffuse.getHeight()
                        + "; the engine will resample it to a square power-of-two size in 32..1024";
            }
            if (normal != null && !validHdSize(normal)) {
                warning = entries.normal().name() + " is " + normal.getWidth() + "x" + normal.getHeight()
                        + "; the engine will resample it to a square power-of-two size in 32..1024";
            }
        } catch (RuntimeException | IOException ex) {
            diffuse = null;
            normal = null;
            warning = textureName + ": " + ex.getMessage();
        }
        return new LoadedTexture(textureName, entries.diffuse(), diffuse, normal, raw, usedFallback,
                warning, hasAlpha);
    }

    private static BufferedImage decodeImage(byte[] bytes, String name) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("unsupported image format: " + name);
        }
        return image;
    }

    private static boolean validHdSize(BufferedImage image) {
        int side = image.getWidth();
        return side == image.getHeight() && side >= 32 && side <= 1024 && (side & (side - 1)) == 0;
    }
}
