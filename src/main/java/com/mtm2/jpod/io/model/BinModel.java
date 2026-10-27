package com.mtm2.jpod.io.model;

import java.util.List;

/**
 * A decoded model, from a {@code .BIN} or a 4x4 Evolution {@code .SMF}, as triangle
 * soup grouped into meshes that share a texture and material.
 *
 * <p>Every mesh is in the {@code .BIN} convention: Z-up, anchored at the model's
 * footprint centre, V running bottom-up. The viewer applies one transform to all of
 * them, so nothing downstream has to know which format a model came from.
 *
 * @param frameNames the models this BIN names as its animation frames, upper-cased,
 *                   in order. An animated BIN holds no geometry of its own: it is a
 *                   list of the other BINs that are its frames, and the game cycles
 *                   them in place. Empty for every ordinary model.
 */
public record BinModel(
        String name,
        String format,
        int magnifyPower,
        float baseZ,
        Bounds rawVertexBounds,
        int vertexCount,
        int polygonCount,
        List<String> textureNames,
        List<Mesh> meshes,
        List<String> warnings,
        List<String> frameNames) {

    public BinModel {
        textureNames = List.copyOf(textureNames);
        meshes = List.copyOf(meshes);
        warnings = List.copyOf(warnings);
        frameNames = List.copyOf(frameNames);
    }

    /** The same model with different warnings. */
    public BinModel withWarnings(List<String> replacement) {
        return new BinModel(name, format, magnifyPower, baseZ, rawVertexBounds, vertexCount,
                polygonCount, textureNames, meshes, replacement, frameNames);
    }

    /** Number of meshes drawn with transparency. */
    public long transparentMeshCount() {
        return meshes.stream().filter(Mesh::transparent).count();
    }

    /** Raw integer vertex bounds as stored in a {@code .BIN}. */
    public record Bounds(int vertexCount, int baseZ, int minX, int maxX, int minY, int maxY,
                         int minZ, int maxZ) {}

    /** An Extended BIN {@code MRGL_MATERIAL} record. Fixed-point fields already scaled. */
    public record Material(int id, long flags, float reflectivity, float fresnelBias,
                           float fresnelStrength, float baseAlpha, float specPower,
                           float emissive, float tintR, float tintG, float tintB,
                           int alphaReference, int translucency) {

        /** Lighting applies. */
        public static final long LIT = 0x0001;
        /** Blended with its base alpha. */
        public static final long BLEND = 0x0004;
        /** Alpha-tested against its reference. */
        public static final long ALPHA_TEST = 0x0008;
        /** Added to what is behind it. */
        public static final long ADDITIVE = 0x0010;
        /** Both faces drawn. */
        public static final long TWO_SIDED = 0x0080;
        /** Leaves the depth buffer alone. */
        public static final long NO_DEPTH_WRITE = 0x0100;
        /** Glows by its emissive amount. */
        public static final long EMISSIVE = 0x0200;
        /** Tinted by its RGB. */
        public static final long TINT = 0x0400;
        /** Uses its own alpha reference rather than one half. */
        public static final long ALPHA_REFERENCE = 0x0800;
        /** Drawn as a solid alpha-tested pass, then again normally. */
        public static final long TEXSOLID = 0x2000;

        public boolean has(long flag) {
            return (flags & flag) != 0;
        }
    }

    /** An Extended BIN {@code MRGL_MATERIAL2} record. */
    public record Material2(long flags, float normalStrength, int[] reserved) {}

    /**
     * One draw group.
     *
     * @param positions   x, y, z per corner, three corners per triangle
     * @param normals     one normal per corner
     * @param uvs         u, v per corner
     * @param doubleSided whether both faces are drawn. A {@code .BIN} is wound so that
     *                    back-face culling is what the renderer wants, and a material's
     *                    TWOSIDED flag turns that off. A 4x4 Evolution {@code .SMF}
     *                    states nothing: its foliage, fences and banners are
     *                    single-sided sheets meant to be seen from behind, so the SMF
     *                    decoder sets this.
     * @param groupName   the source group's name when the format names groups; empty
     *                    for {@code .BIN}
     */
    public record Mesh(String textureName, boolean transparent, Material material,
                       Material2 material2, boolean solid, int color, float[] positions,
                       float[] normals, float[] uvs, boolean doubleSided, String groupName) {

        public int vertexCount() {
            return positions.length / 3;
        }
    }
}
