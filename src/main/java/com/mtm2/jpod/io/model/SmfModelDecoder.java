package com.mtm2.jpod.io.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decoder for .SMF v2-v4, the static model format of 4x4 Evolution 1 and 2
 * ("C3DModel").
 *
 * <p>The grammar is:
 * <pre>
 * "C3DModel"
 * fileVersion
 * objectCount
 * if fileVersion &gt;= 4: lodEnabled,lodSwitchHeight
 *
 * repeat objectCount:
 *     objectName
 *     if fileVersion &gt;= 2: visible
 *     objectVersion
 *     vertexCount,frameCount,faceCount,unknown0
 *     ["v1"]                                        optional Evo 2 material marker
 *     material0,material1,material2,transparent,reflective,textureName
 *     if v1: bumpTextureName
 *     repeat frameCount:
 *         repeat vertexCount: x,y,z,nx,ny,nz,u,v
 *     repeat faceCount: i0,i1,i2
 * </pre>
 *
 * <p>Read as a counted state machine rather than by sniffing where the vertex block
 * ends: a vertex line and a face line are both just comma-separated numbers, and the
 * counts are the only thing that distinguishes them. Every count and every face index
 * is validated against the block it addresses.
 *
 * <p>Verified, in KPod, against all 118 models in the ASPEN, THEHILL, BAJBEACH and
 * PEAK stock tracks: 115 v4, 2 v2, 1 v3, every file consumed exactly to its trailing
 * blank line.
 *
 * <p>Output is a {@link BinModel} in the SAME convention the .BIN decoder uses, so one
 * viewer draws both formats with one code path. Evo is Y-up and .BIN is Z-up, so Y and
 * Z are swapped here. The renderer then applies its own (x, z, -y) transform to every
 * model alike, and the two compose to (x, y, -z): Evo's Z is negated on the way to the
 * screen. That negation is not cosmetic. Getting it wrong leaves the model a mirror
 * image of itself, which reads as the texture being mirrored, because a mirrored mesh
 * still carries its own UVs. Normals are negated with the swap, and V is inverted
 * because Evo's V runs top-down.
 */
public final class SmfModelDecoder {

    private static final String MAGIC = "C3DModel";
    private static final int MAX_OBJECTS = 4096;
    private static final int MAX_VERTICES = 1 << 20;
    private static final int MAX_FACES = 1 << 20;

    private SmfModelDecoder() {}

    /**
     * A reduced-detail group is its full-detail partner's name suffixed with "L":
     * OPAQUE/OPAQUEL, TRANSP/TRANSPL. TRANSPI and TRANSPE are full-detail groups, so
     * only these two spellings count.
     */
    private static boolean isReducedDetail(String groupName) {
        return groupName.equalsIgnoreCase("OPAQUEL") || groupName.equalsIgnoreCase("TRANSPL");
    }

    /** True when these bytes begin a C3DModel, whatever the entry is called. */
    public static boolean isSmfModel(byte[] data) {
        if (data == null || data.length < MAGIC.length()) return false;
        for (int i = 0; i < MAGIC.length(); i++) {
            if (data[i] != (byte) MAGIC.charAt(i)) return false;
        }
        return true;
    }

    /**
     * Decodes an SMF.
     *
     * @throws IllegalArgumentException when the file is not a C3DModel or its counts
     *                                  are implausible or truncated
     */
    public static BinModel decode(byte[] data, String name) {
        BinModel model = decode(data, name, false);
        // Dropping the hidden and reduced-detail groups must never empty a model. One
        // that carries only a low-detail or hidden group is still better drawn than
        // reported as having no geometry, so it is walked again keeping everything.
        if (!model.meshes().isEmpty()) return model;
        BinModel everything = decode(data, name, true);
        if (everything.meshes().isEmpty()) return model;
        List<String> warnings = new ArrayList<>(everything.warnings());
        warnings.add("model has only reduced-detail or hidden groups; drawing them anyway");
        return everything.withWarnings(warnings);
    }

    private static BinModel decode(byte[] data, String name, boolean keepAllGroups) {
        String[] lines = new String(data, StandardCharsets.ISO_8859_1)
                .replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        Cursor cursor = new Cursor(lines);
        List<String> warnings = new ArrayList<>();
        List<BinModel.Mesh> meshes = new ArrayList<>();
        List<String> textureNames = new ArrayList<>();
        Set<String> seenTextures = new HashSet<>();
        int totalVertices = 0;
        int totalPolygons = 0;

        if (!MAGIC.equals(cursor.next())) throw new IllegalArgumentException(name + ": not a C3DModel");
        int fileVersion = parseInt(cursor.next());
        if (fileVersion < 1 || fileVersion > 4) {
            throw new IllegalArgumentException(name + ": unsupported .SMF version " + fileVersion);
        }
        int objectCount = parseInt(cursor.next());
        if (objectCount < 0 || objectCount > MAX_OBJECTS) {
            throw new IllegalArgumentException(name + ": implausible object count " + objectCount);
        }

        boolean lodEnabled = false;
        if (fileVersion >= 4) {
            String[] lod = orEmpty(cursor.next()).split(",", -1);
            lodEnabled = lod.length > 0 && !lod[0].trim().equals("0");
        }

        for (int o = 0; o < objectCount; o++) {
            String groupName = cursor.next();
            if (groupName == null) {
                warnings.add("ran out of lines at object " + (o + 1) + " of " + objectCount);
                break;
            }
            boolean visible = fileVersion < 2 || !"0".equals(cursor.next());
            cursor.next(); // objectVersion; every stock model writes 1

            String[] counts = orEmpty(cursor.next()).split(",", -1);
            int vertexCount = parseInt(at(counts, 0));
            int frameCount = Math.max(1, parseInt(at(counts, 1)));
            int faceCount = parseInt(at(counts, 2));
            if (vertexCount < 0 || vertexCount > MAX_VERTICES || faceCount < 0 || faceCount > MAX_FACES) {
                throw new IllegalArgumentException(name + ": implausible counts in group \"" + groupName
                        + "\" (" + vertexCount + " verts, " + faceCount + " faces)");
            }

            // The Evo 2 bump form announces itself with a bare "v1" line before the material.
            boolean bumpForm = "v1".equals(cursor.peek());
            if (bumpForm) cursor.next();

            String[] material = orEmpty(cursor.next()).split(",", -1);
            String textureName = at(material, 5) == null ? "" : at(material, 5).trim();
            if (bumpForm) cursor.next(); // bump texture name

            // Frame 0 is what gets drawn. Later frames are still walked line by line so
            // the cursor stays aligned with the face block.
            float[] vx = new float[vertexCount * 3];
            float[] vn = new float[vertexCount * 3];
            float[] vt = new float[vertexCount * 2];
            for (int f = 0; f < frameCount; f++) {
                for (int v = 0; v < vertexCount; v++) {
                    String line = cursor.next();
                    if (line == null) {
                        throw new IllegalArgumentException(name + ": truncated vertex block in \""
                                + groupName + "\"");
                    }
                    if (f != 0) continue;
                    String[] p = line.split(",", -1);
                    // Evo (x, y, z) to the .BIN convention (x, z, y); normals negated with it.
                    vx[v * 3] = parseFloat(at(p, 0));
                    vx[v * 3 + 1] = parseFloat(at(p, 2));
                    vx[v * 3 + 2] = parseFloat(at(p, 1));
                    vn[v * 3] = -parseFloat(at(p, 3));
                    vn[v * 3 + 1] = -parseFloat(at(p, 5));
                    vn[v * 3 + 2] = -parseFloat(at(p, 4));
                    vt[v * 2] = parseFloat(at(p, 6));
                    vt[v * 2 + 1] = 1f - parseFloat(at(p, 7));
                }
            }

            FloatList positions = new FloatList(faceCount * 9);
            FloatList normals = new FloatList(faceCount * 9);
            FloatList uvs = new FloatList(faceCount * 6);
            int triangles = 0;
            for (int f = 0; f < faceCount; f++) {
                String line = cursor.next();
                if (line == null) {
                    throw new IllegalArgumentException(name + ": truncated face block in \""
                            + groupName + "\"");
                }
                String[] parts = line.split(",", -1);
                int[] tri = {parseInt(at(parts, 0)), parseInt(at(parts, 1)), parseInt(at(parts, 2))};
                boolean outside = false;
                for (int index : tri) {
                    if (index < 0 || index >= vertexCount) { outside = true; break; }
                }
                if (outside) {
                    warnings.add("\"" + groupName + "\" face " + f + " indexes outside its "
                            + vertexCount + " vertices");
                    continue;
                }
                for (int src : tri) {
                    positions.add(vx[src * 3]); positions.add(vx[src * 3 + 1]); positions.add(vx[src * 3 + 2]);
                    normals.add(vn[src * 3]); normals.add(vn[src * 3 + 1]); normals.add(vn[src * 3 + 2]);
                    uvs.add(vt[src * 2]); uvs.add(vt[src * 2 + 1]);
                }
                triangles++;
            }

            if (!textureName.isEmpty() && seenTextures.add(textureName.toUpperCase(Locale.ROOT))) {
                textureNames.add(textureName.toUpperCase(Locale.ROOT));
            }
            totalVertices += vertexCount;
            totalPolygons += triangles;

            // Material fields 3 and 4 are the transparency and reflectivity flags. Fields
            // 0-2 are three scalars of unknown meaning; every stock model writes 1, 1, 32.
            String transparency = at(material, 3);
            boolean transparent = transparency != null && !transparency.trim().equals("0");
            if (positions.size() == 0) continue;
            BinModel.Mesh mesh = new BinModel.Mesh(textureName.toUpperCase(Locale.ROOT), transparent,
                    null, null, false, 0xBFBFBF, positions.toArray(), normals.toArray(), uvs.toArray(),
                    true, groupName);
            if (keepAllGroups || (visible && !isReducedDetail(groupName))) {
                meshes.add(mesh);
            }
        }

        String format = "SMF v" + fileVersion + (lodEnabled ? " (LOD)" : "");
        return new BinModel(name, format, 65536, 0f, null, totalVertices, totalPolygons,
                textureNames, meshes, warnings, List.of());
    }

    private static String at(String[] values, int index) {
        return index < values.length ? values[index] : null;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(orEmpty(value).trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static float parseFloat(String value) {
        try {
            return Float.parseFloat(orEmpty(value).trim());
        } catch (NumberFormatException ex) {
            return 0f;
        }
    }

    private static final class Cursor {
        private final String[] lines;
        private int index;

        private Cursor(String[] lines) {
            this.lines = lines;
        }

        private String next() {
            return index < lines.length ? lines[index++].trim() : null;
        }

        private String peek() {
            return index < lines.length ? lines[index].trim() : null;
        }
    }
}
