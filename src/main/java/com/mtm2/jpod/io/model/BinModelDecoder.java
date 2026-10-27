package com.mtm2.jpod.io.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounds-checked decoder for classic, animated, and Extended BIN command streams. */
public final class BinModelDecoder {

    private static final int SIGNATURE_LWO = 0x4D524F46;
    private static final int SIGNATURE_ANIMATED = 0x20;
    private static final int MAGNIFY = 0x14;
    private static final int TEXTURE64 = 62;
    private static final int MATERIAL = 63;
    private static final int MATERIAL_FACET = 64;
    private static final int KEYFRAME64 = 65;
    private static final int MATERIAL2 = 66;
    private static final int MAX_CORNERS = 256;
    private static final float UV_SCALE = 0xFF0000;

    private BinModelDecoder() {}

    /** Decodes a BIN. Never throws for bad data: problems become warnings. */
    public static BinModel decode(byte[] data, String name) {
        MutableModel model = new MutableModel(name);
        if (data.length < 4) return model.finish();

        Reader reader = new Reader(data);
        try {
            int first = reader.readInt32();
            if (first == SIGNATURE_LWO) {
                model.format = "LWO";
                return model.finish();
            }
            if (first == MAGNIFY) {
                model.format = "BIN";
                if (reader.remaining() < 4) return model.finish();
                int power = reader.readInt32();
                if (power > 0) model.magnifyPower = power;
                decodePayload(reader, model, 8, true);
            } else if (first == SIGNATURE_ANIMATED) {
                model.format = "ANIMATED_BIN";
                model.frameNames.addAll(readFrameNames(data));
                decodePayload(reader, model, 12, false);
            } else {
                model.format = String.format(Locale.ROOT, "0x%08X", first);
            }
        } catch (IllegalArgumentException ex) {
            model.warnings.add(ex.getMessage());
        }
        return model.finish();
    }

    /**
     * The frame model names an animated BIN carries.
     *
     * <p>Layout, confirmed against {@code MODELS\REX.BIN} in the stock CRAZY98 pod: the
     * header is {@code 0x20}, a zero, the frame count, then the usual 65536 magnify
     * constant; a zero vertex count and a zero end-of-blocks token follow, so an
     * animated BIN still parses as an ordinary model that happens to have no geometry.
     * The names begin at byte 24 as NUL-padded ASCII in 16-byte slots.
     */
    private static List<String> readFrameNames(byte[] data) {
        final int countOffset = 8;
        final int namesOffset = 24;
        final int slotBytes = 16;
        List<String> names = new ArrayList<>();
        if (data.length < namesOffset + slotBytes) return names;

        int count = readInt32(data, countOffset);
        if (count <= 0) return names;

        // A truncated file is read as far as it goes rather than rejected outright:
        // the frames that are there still name real models.
        int available = (data.length - namesOffset) / slotBytes;
        count = Math.min(count, available);
        for (int i = 0; i < count; i++) {
            int start = namesOffset + i * slotBytes;
            int length = 0;
            while (length < slotBytes - 1 && data[start + length] != 0) length++;
            String frame = new String(data, start, length, StandardCharsets.US_ASCII).trim();
            if (!frame.isEmpty()) names.add(frame.toUpperCase(Locale.ROOT));
        }
        return names;
    }

    private static void decodePayload(Reader reader, MutableModel model, int headerBytes,
            boolean applyMagnify) {
        if (!reader.canRead(headerBytes + 4)) {
            model.warnings.add("Truncated BIN header");
            return;
        }

        reader.skip(headerBytes);
        int vertexCount = reader.readInt32();
        if (vertexCount < 0 || vertexCount > 200000 || !reader.canRead((long) vertexCount * 12)) {
            model.warnings.add("Invalid or truncated BIN vertex list");
            return;
        }

        int[][] raw = new int[vertexCount][];
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int baseZ = 0;
        for (int i = 0; i < vertexCount; i++) {
            int x = reader.readInt32() >> 1;
            int z = reader.readInt32() >> 1;
            int y = reader.readInt32() >> 1;
            raw[i] = new int[] {x, y, z};
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            baseZ = Math.min(baseZ, z);
        }

        int adjustedBaseZ = baseZ - 31;
        model.rawBounds = new BinModel.Bounds(vertexCount, adjustedBaseZ, minX, maxX, minY, maxY, minZ, maxZ);
        float scale = applyMagnify ? 1024f / model.magnifyPower : 1f / 64f;
        for (int[] v : raw) {
            model.vertices.add(new float[] {v[0] * scale, v[1] * scale, v[2] * scale});
        }
        model.baseZ = adjustedBaseZ * scale;

        String texture = "";
        int solidColor = 0;
        BinModel.Material material = null;
        BinModel.Material2 material2 = null;
        int materialId = 0;
        boolean stop = false;
        while (!stop && reader.remaining() >= 4) {
            int tokenOffset = reader.position;
            int token = reader.readInt32();
            switch (token) {
                case 0 -> stop = true;
                case 2 -> {
                    if (!reader.canRead(8)) { stop = true; break; }
                    reader.skip(4);
                    int stripVertices = reader.readInt32();
                    long stripBytes = (long) stripVertices * 12 + 80;
                    if (stripVertices >= 0 && stripVertices <= MAX_CORNERS && reader.canRead(stripBytes)) {
                        reader.skip((int) stripBytes);
                    } else if (reader.canRead(136)) {
                        reader.skip(136);
                    } else {
                        stop = true;
                    }
                }
                case 3 -> {
                    if (!reader.canRead(8L + (long) vertexCount * 12)) { stop = true; break; }
                    reader.skip(8 + vertexCount * 12);
                }
                case 4 -> {
                    if (!reader.canRead(8)) { stop = true; break; }
                    reader.skip(4);
                    int count4 = reader.readInt32();
                    if (count4 < 0 || count4 > 4096 || !reader.canRead((long) count4 * 8)) { stop = true; break; }
                    reader.skip(count4 * 8);
                }
                case 0x0D -> {
                    if (!reader.canRead(20)) { stop = true; break; }
                    reader.skip(4);
                    texture = reader.readAscii(16).trim().toUpperCase(Locale.ROOT);
                }
                case TEXTURE64 -> {
                    if (!reader.canRead(68)) {
                        model.warnings.add("Truncated MRGL_TEXTURE64 record"); stop = true; break;
                    }
                    reader.skip(4);
                    texture = reader.readAscii(64).trim().toUpperCase(Locale.ROOT);
                }
                case MATERIAL -> {
                    if (!reader.canRead(44)) {
                        model.warnings.add("Truncated MRGL_MATERIAL record"); stop = true; break;
                    }
                    material = readMaterial(reader, ++materialId);
                }
                case MATERIAL2 -> {
                    if (!reader.canRead(28)) {
                        model.warnings.add("Truncated MRGL_MATERIAL2 record"); stop = true; break;
                    }
                    material2 = readMaterial2(reader);
                    for (int value : material2.reserved()) {
                        if (value != 0) {
                            model.warnings.add("MRGL_MATERIAL2 has non-zero reserved fields");
                            break;
                        }
                    }
                }
                case KEYFRAME64 -> {
                    if (!reader.canRead(4372)) {
                        model.warnings.add("Truncated MRGL_KEYFRAME64 record"); stop = true; break;
                    }
                    reader.skip(4372);
                }
                case 0x1D -> {
                    if (!reader.canRead(24)) { stop = true; break; }
                    reader.skip(4);
                    int frames = reader.readInt32();
                    reader.skip(16);
                    if (frames < 0 || frames > 1024) { stop = true; break; }
                    if (reader.canRead((long) frames * 32)) {
                        for (int i = 0; i < frames; i++) {
                            String frame = reader.readAscii(32).trim().toUpperCase(Locale.ROOT);
                            if (i == 0) texture = frame;
                        }
                    } else if (reader.canRead((long) frames * 8)) {
                        reader.skip(frames * 8);
                    } else {
                        stop = true;
                    }
                }
                case 0x0A -> {
                    if (!reader.canRead(4)) { stop = true; break; }
                    solidColor = reader.readInt32() & 0xFFFFFF;
                }
                case 0x0C -> { if (reader.canRead(24)) reader.skip(24); else stop = true; }
                case 0x12 -> { if (reader.canRead(4)) reader.skip(4); else stop = true; }
                case MAGNIFY -> { if (reader.canRead(4)) model.magnifyPower = reader.readInt32(); else stop = true; }
                case 0x16 -> { if (reader.canRead(12)) reader.skip(12); else stop = true; }
                case 0x17 -> { if (reader.canRead(8)) reader.skip(8); else stop = true; }
                case 0x1F -> {
                    if (!reader.canRead(8)) { stop = true; break; }
                    reader.skip(4);
                    int count1F = reader.readInt32();
                    if (count1F < 0 || count1F > 200000 || !reader.canRead((long) count1F * 4)) stop = true;
                    else reader.skip(count1F * 4);
                }
                case 0x11, 0x18, 0x22, 0x29, 0x33, 0x34, 0x0E -> {
                    Polygon mapped = readFace(reader, token, texture, vertexCount, true, null, null, solidColor);
                    if (mapped == null) stop = true; else model.polygons.add(mapped);
                }
                case 5, 0x19, 6, 0x0F -> {
                    String faceTexture = token == 0x19 ? "" : texture;
                    Polygon flat = readFace(reader, token, faceTexture, vertexCount, false, null, null, solidColor);
                    if (flat == null) stop = true; else model.polygons.add(flat);
                }
                case MATERIAL_FACET -> {
                    Polygon mat = readFace(reader, token, texture, vertexCount, true, material, material2, solidColor);
                    if (mat == null) {
                        model.warnings.add("Invalid MRGL_MATFACET at byte " + reader.position);
                        stop = true;
                    } else {
                        model.polygons.add(mat);
                    }
                }
                default -> {
                    model.warnings.add("Unsupported BIN opcode " + token + " (0x"
                            + Integer.toHexString(token).toUpperCase(Locale.ROOT) + ") at byte "
                            + tokenOffset + "; model truncated");
                    stop = true;
                }
            }
        }
    }

    private static Polygon readFace(Reader reader, int type, String texture, int vertexCount,
            boolean mapped, BinModel.Material material, BinModel.Material2 material2, int solidColor) {
        if (vertexCount < 1 || !reader.canRead(4)) return null;
        int count = reader.readInt32();
        long bytes = 16L + (long) count * (mapped ? 12 : 4);
        if (count < 3 || count > MAX_CORNERS || !reader.canRead(bytes)) return null;
        reader.skip(16);
        int[] indices = new int[count];
        int[] u = new int[count];
        int[] v = new int[count];
        for (int i = 0; i < count; i++) {
            indices[i] = reader.readInt32();
            if (mapped) { u[i] = reader.readInt32(); v[i] = reader.readInt32(); }
        }

        boolean outOfRange = false;
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) { outOfRange = true; break; }
        }
        if (outOfRange) {
            // Some exporters wrote one-based indices; accept them only if every one fits.
            for (int index : indices) {
                if (index - 1 < 0 || index - 1 >= vertexCount) return null;
            }
            for (int i = 0; i < indices.length; i++) indices[i]--;
        }
        return new Polygon(type, texture, indices, u, v, material, material2, solidColor);
    }

    private static BinModel.Material readMaterial(Reader reader, int id) {
        long flags = Integer.toUnsignedLong(reader.readInt32());
        float reflectivity = fixed(reader.readInt32());
        float fresnelBias = fixed(reader.readInt32());
        float fresnelStrength = fixed(reader.readInt32());
        float baseAlpha = fixed(reader.readInt32());
        float specPower = fixed(reader.readInt32());
        float emissive = fixed(reader.readInt32());
        float r = fixed(reader.readInt32());
        float g = fixed(reader.readInt32());
        float b = fixed(reader.readInt32());
        long foliage = Integer.toUnsignedLong(reader.readInt32());
        return new BinModel.Material(id, flags, reflectivity, fresnelBias, fresnelStrength,
                baseAlpha, specPower, emissive, r, g, b, (int) (foliage & 0xFFFF), (int) (foliage >>> 16));
    }

    private static BinModel.Material2 readMaterial2(Reader reader) {
        long flags = Integer.toUnsignedLong(reader.readInt32());
        float strength = fixed(reader.readInt32());
        int[] reserved = new int[5];
        for (int i = 0; i < reserved.length; i++) reserved[i] = reader.readInt32();
        return new BinModel.Material2(flags, (flags & 1) != 0 ? strength : 1, reserved);
    }

    private static float fixed(int value) {
        return value / 65536f;
    }

    private static int readInt32(byte[] data, int at) {
        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8)
                | ((data[at + 2] & 0xFF) << 16) | ((data[at + 3] & 0xFF) << 24);
    }

    private record Polygon(int type, String texture, int[] indices, int[] u, int[] v,
                           BinModel.Material material, BinModel.Material2 material2, int solidColor) {}

    private static final class MutableModel {
        private final String name;
        private String format = "UNKNOWN";
        private int magnifyPower = 65536;
        private float baseZ;
        private BinModel.Bounds rawBounds;
        private final List<float[]> vertices = new ArrayList<>();
        private final List<Polygon> polygons = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> frameNames = new ArrayList<>();

        private MutableModel(String name) {
            this.name = name;
        }

        private BinModel finish() {
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            for (float[] vertex : vertices) {
                minX = Math.min(minX, vertex[0]); maxX = Math.max(maxX, vertex[0]);
                minY = Math.min(minY, vertex[1]); maxY = Math.max(maxY, vertex[1]);
                minZ = Math.min(minZ, vertex[2]);
            }
            float[] anchor = vertices.isEmpty() ? new float[3]
                    : new float[] {(minX + maxX) / 2, (minY + maxY) / 2, minZ};

            Map<String, MeshBuilder> groups = new LinkedHashMap<>();
            Set<String> textureNames = new LinkedHashSet<>();
            for (Polygon polygon : polygons) {
                if (!polygon.texture().isEmpty()) textureNames.add(polygon.texture().toUpperCase(Locale.ROOT));
                long flags = polygon.material() != null ? polygon.material().flags() : 0;
                boolean transparent = polygon.material() != null
                        ? (flags & (BinModel.Material.BLEND | BinModel.Material.ALPHA_TEST
                                | BinModel.Material.TEXSOLID)) != 0
                        : polygon.type() == 0x11 || polygon.type() == 0x33;
                boolean solid = polygon.type() == 0x19;
                String materialKey = polygon.material() == null ? "legacy"
                        : "material:" + polygon.material().id() + ":"
                        + (polygon.material2() != null ? polygon.material2().normalStrength() : 1f);
                String key = polygon.texture().toUpperCase(Locale.ROOT) + "|" + transparent + "|"
                        + (solid ? "solid:" + polygon.solidColor() : "textured") + "|" + materialKey;
                MeshBuilder group = groups.computeIfAbsent(key, ignored -> new MeshBuilder(
                        polygon.texture(), transparent, polygon.material(), polygon.material2(),
                        solid, polygon.solidColor()));
                triangulate(polygon, group, anchor);
            }
            List<BinModel.Mesh> meshes = new ArrayList<>(groups.size());
            for (MeshBuilder group : groups.values()) meshes.add(group.build());
            return new BinModel(name, format, magnifyPower, baseZ, rawBounds, vertices.size(),
                    polygons.size(), new ArrayList<>(textureNames), meshes, warnings, frameNames);
        }

        /** Fans a polygon into triangles, each with its face normal at every corner. */
        private void triangulate(Polygon polygon, MeshBuilder mesh, float[] anchor) {
            int[] idx = polygon.indices();
            for (int i = 1; i < idx.length - 1; i++) {
                int[] corners = {0, i, i + 1};
                float[] a = vertices.get(idx[0]);
                float[] b = vertices.get(idx[i]);
                float[] c = vertices.get(idx[i + 1]);
                float[] normal = normal(a, b, c);
                for (int corner : corners) {
                    float[] point = vertices.get(idx[corner]);
                    mesh.positions.add(point[0] - anchor[0]);
                    mesh.positions.add(point[1] - anchor[1]);
                    mesh.positions.add(point[2] - anchor[2]);
                    mesh.normals.add(normal[0]);
                    mesh.normals.add(normal[1]);
                    mesh.normals.add(normal[2]);
                    mesh.uvs.add(polygon.u()[corner] / UV_SCALE);
                    mesh.uvs.add(1 - polygon.v()[corner] / UV_SCALE);
                }
            }
        }

        private static float[] normal(float[] a, float[] b, float[] c) {
            float abx = b[0] - a[0], aby = b[1] - a[1], abz = b[2] - a[2];
            float acx = c[0] - a[0], acy = c[1] - a[1], acz = c[2] - a[2];
            float x = aby * acz - abz * acy, y = abz * acx - abx * acz, z = abx * acy - aby * acx;
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            return length == 0 ? new float[] {0, 1, 0} : new float[] {x / length, y / length, z / length};
        }
    }

    private static final class MeshBuilder {
        private final String texture;
        private final boolean transparent;
        private final BinModel.Material material;
        private final BinModel.Material2 material2;
        private final boolean solid;
        private final int color;
        private final FloatList positions = new FloatList();
        private final FloatList normals = new FloatList();
        private final FloatList uvs = new FloatList();

        private MeshBuilder(String texture, boolean transparent, BinModel.Material material,
                BinModel.Material2 material2, boolean solid, int color) {
            this.texture = texture;
            this.transparent = transparent;
            this.material = material;
            this.material2 = material2;
            this.solid = solid;
            this.color = color;
        }

        private BinModel.Mesh build() {
            return new BinModel.Mesh(texture, transparent, material, material2, solid, color,
                    positions.toArray(), normals.toArray(), uvs.toArray(), false, "");
        }
    }

    private static final class Reader {
        private final byte[] data;
        private int position;

        private Reader(byte[] data) {
            this.data = data;
        }

        private int remaining() {
            return data.length - position;
        }

        private boolean canRead(long length) {
            return length >= 0 && position + length <= data.length;
        }

        private void skip(int length) {
            require(length);
            position += length;
        }

        private int readInt32() {
            require(4);
            int value = BinModelDecoder.readInt32(data, position);
            position += 4;
            return value;
        }

        private String readAscii(int length) {
            require(length);
            int end = position;
            while (end < position + length && data[end] != 0) end++;
            String value = new String(data, position, end - position, StandardCharsets.ISO_8859_1);
            position += length;
            return value;
        }

        private void require(int length) {
            if (!canRead(length)) {
                throw new IllegalArgumentException("Unexpected end of BIN payload at byte " + position);
            }
        }
    }
}
