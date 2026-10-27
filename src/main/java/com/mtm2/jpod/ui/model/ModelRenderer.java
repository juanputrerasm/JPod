package com.mtm2.jpod.ui.model;

import com.mtm2.jpod.io.model.BinModel;
import com.mtm2.jpod.io.model.BinTextureResolver;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Software rasterizer for the model preview.
 *
 * <p>KPod draws models with OpenGL 3.3. JPod has no native dependencies, so it
 * rasterizes in Java instead, with the same camera, transform and material rules:
 * perspective-correct texturing with a depth buffer, front-face culling (a {@code .BIN}
 * is wound inward), the MTM colour key on black RAW texels, alpha test, blending,
 * additive and TEXSOLID passes, two-sided and no-depth-write materials, tint,
 * emissive, specular lighting and {@code _N} normal maps in the DirectX green-down
 * convention.
 *
 * <p>Models are in the {@code .BIN} Z-up convention and are drawn through (x, z, -y),
 * so Y is up on screen. A {@code .SMF} arrives already converted, so nothing here
 * knows which format a model came from.
 *
 * <p>Every animation frame is prepared once; stepping the animation is a single
 * assignment and never disturbs the camera or the grid. Not thread-safe: use it from
 * the event thread.
 */
public final class ModelRenderer {

    /** Render toggles, the same set the KPod viewer offers. */
    public record Options(boolean textures, boolean wireframe, boolean grid, boolean smooth,
                          boolean lighting, float[] lightPosition, Color background) {}

    /** One frame to prepare: a decoded model and the textures resolved for it. */
    public record FrameGeometry(BinModel model, List<LoadedTexture> textures) {}

    /** Vertical field of view, radians. */
    private static final float FIELD_OF_VIEW = (float) Math.toRadians(48);

    private static final int GRID_COLOR = 0xFF40404A;
    private static final int WIRE_COLOR = 0xFFFFFF00;

    private final List<List<RenderMesh>> frames = new ArrayList<>();
    private int frameIndex;
    private float modelSize = 1;
    private float yaw;
    private float pitch = 0.32f;
    private float distance = 3;
    private float[] target = new float[3];
    private float[] modelCenter = new float[3];
    private float[] gridLines = new float[0];

    // Per-render state.
    private int width;
    private int height;
    private int[] color;
    private float[] depth = new float[0];
    private final float[] eye = new float[3];
    private final float[] right = new float[3];
    private final float[] up = new float[3];
    private final float[] forward = new float[3];
    private float near;
    private float focalPixels;
    private Options options;

    /**
     * Prepares every frame and fits the grid and camera to all of them together, so
     * neither jumps as an animation swings through its poses.
     *
     * @param resetView false when only the textures changed, such as a palette switch,
     *                  so the camera stays where the user put it
     */
    public void setFrames(List<FrameGeometry> geometry, boolean resetView) {
        frames.clear();
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (FrameGeometry frame : geometry) {
            Map<String, LoadedTexture> textures = new HashMap<>();
            for (LoadedTexture texture : frame.textures()) {
                textures.put(BinTextureResolver.stem(texture.name()), texture);
            }
            List<RenderMesh> meshes = new ArrayList<>();
            for (BinModel.Mesh mesh : frame.model().meshes()) {
                RenderMesh prepared = new RenderMesh(mesh, textures.get(BinTextureResolver.stem(mesh.textureName())));
                meshes.add(prepared);
                for (int i = 0; i + 2 < prepared.positions.length; i += 3) {
                    for (int axis = 0; axis < 3; axis++) {
                        min[axis] = Math.min(min[axis], prepared.positions[i + axis]);
                        max[axis] = Math.max(max[axis], prepared.positions[i + axis]);
                    }
                }
            }
            frames.add(meshes);
        }
        frameIndex = Math.min(frameIndex, Math.max(0, frames.size() - 1));
        boolean empty = min[0] == Float.MAX_VALUE;
        modelCenter = empty ? new float[3]
                : new float[] {(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2};
        modelSize = empty ? 1 : Math.max(1, (float) Math.sqrt(sq(max[0] - min[0])
                + sq(max[1] - min[1]) + sq(max[2] - min[2])));
        buildGrid(modelSize, modelCenter[0], empty ? 0 : min[1], modelCenter[2]);
        if (resetView) {
            resetView();
        }
    }

    /** Draws a different frame. The view is left alone. */
    public void selectFrame(int index) {
        if (frames.isEmpty()) return;
        frameIndex = ((index % frames.size()) + frames.size()) % frames.size();
    }

    public void resetView() {
        yaw = 0;
        pitch = 0.32f;
        target = modelCenter.clone();
        distance = modelSize * 1.6f;
    }

    public void orbit(float dx, float dy) {
        yaw += dx * 0.01f;
        pitch = Math.max(-1.45f, Math.min(1.45f, pitch + dy * 0.01f));
    }

    /** Zooms in for a negative wheel rotation (away from the user), out otherwise. */
    public void zoom(int wheelRotation) {
        distance = Math.max(modelSize * 0.08f, distance * (wheelRotation < 0 ? 0.88f : 1.14f));
    }

    public void strafe(float direction) {
        float rx = (float) Math.cos(yaw);
        float rz = -(float) Math.sin(yaw);
        target[0] += rx * distance * 0.04f * direction;
        target[2] += rz * distance * 0.04f * direction;
    }

    /** Renders into {@code image}, which must be {@link BufferedImage#TYPE_INT_RGB}. */
    public void render(BufferedImage image, Options options) {
        this.options = options;
        width = image.getWidth();
        height = image.getHeight();
        color = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        if (depth.length != color.length) {
            depth = new float[color.length];
        }
        java.util.Arrays.fill(color, options.background().getRGB() | 0xFF000000);
        java.util.Arrays.fill(depth, 0f);
        if (width < 1 || height < 1) return;

        float cosPitch = (float) Math.cos(pitch);
        eye[0] = target[0] + (float) Math.sin(yaw) * cosPitch * distance;
        eye[1] = target[1] + (float) Math.sin(pitch) * distance;
        eye[2] = target[2] + (float) Math.cos(yaw) * cosPitch * distance;
        forward[0] = target[0] - eye[0];
        forward[1] = target[1] - eye[1];
        forward[2] = target[2] - eye[2];
        normalize(forward);
        cross(forward, new float[] {0, 1, 0}, right);
        normalize(right);
        cross(right, forward, up);
        near = Math.max(0.001f, modelSize * 0.01f);
        focalPixels = (height / 2f) / (float) Math.tan(FIELD_OF_VIEW / 2);

        if (options.grid()) {
            for (int i = 0; i + 5 < gridLines.length; i += 6) {
                drawLine(gridLines[i], gridLines[i + 1], gridLines[i + 2],
                        gridLines[i + 3], gridLines[i + 4], gridLines[i + 5], GRID_COLOR, true, 1f);
            }
        }
        if (frames.isEmpty()) return;
        for (RenderMesh mesh : frames.get(frameIndex)) {
            BinModel.Material material = mesh.source.material();
            if (material != null && material.has(BinModel.Material.TEXSOLID)) {
                drawMesh(mesh, true);
            }
            drawMesh(mesh, false);
            if (options.wireframe()) {
                drawWireframe(mesh);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Mesh drawing
    // -------------------------------------------------------------------------

    /** The material state for one pass over a mesh, worked out once. */
    private static final class Pass {
        boolean alphaTest;
        boolean blend;
        boolean additive;
        boolean depthWrite;
        boolean cull;
        boolean useTexture;
        boolean useNormal;
        boolean rawCutout;
        boolean lighting;
        float alphaReference;
        float opacity;
        float shininess;
        float emissive;
        float normalStrength;
        float tintR;
        float tintG;
        float tintB;
        Texture diffuse;
        Texture normal;
    }

    private Pass passFor(RenderMesh mesh, boolean solidPass) {
        BinModel.Material material = mesh.source.material();
        long flags = material != null ? material.flags() : 0;
        Pass pass = new Pass();
        // An .SMF states transparency two ways and its material flag is the weaker one:
        // stock Evo vegetation writes that flag as 0 while naming a two-sample .TIF
        // whose second sample is opacity. An alpha channel in the art is the intent.
        pass.alphaTest = solidPass || (material != null
                ? (flags & BinModel.Material.ALPHA_TEST) != 0
                : mesh.source.transparent() || mesh.hasTextureAlpha);
        pass.blend = !solidPass && material != null && (flags & BinModel.Material.BLEND) != 0 && !pass.alphaTest;
        pass.additive = !solidPass && (flags & BinModel.Material.ADDITIVE) != 0;
        pass.depthWrite = solidPass || pass.alphaTest || (flags & BinModel.Material.NO_DEPTH_WRITE) == 0;
        pass.cull = !(mesh.source.doubleSided() || (flags & BinModel.Material.TWO_SIDED) != 0);
        pass.useTexture = options.textures() && mesh.diffuse != null;
        pass.useNormal = pass.useTexture && options.lighting() && mesh.normal != null;
        pass.rawCutout = mesh.isRaw && (mesh.source.transparent()
                || (flags & (BinModel.Material.BLEND | BinModel.Material.ALPHA_TEST | BinModel.Material.TEXSOLID)) != 0);
        pass.alphaReference = solidPass ? 0.5f
                : (flags & BinModel.Material.ALPHA_REFERENCE) != 0 && material != null
                ? material.alphaReference() / 255f : 0.5f;
        pass.opacity = pass.blend ? clamp(material.baseAlpha()) : 1f;
        pass.shininess = Math.max(1, material != null ? material.specPower() : 1);
        pass.emissive = (flags & BinModel.Material.EMISSIVE) != 0 && material != null ? clamp(material.emissive()) : 0;
        pass.normalStrength = mesh.source.material2() != null ? mesh.source.material2().normalStrength() : 1;
        if ((flags & BinModel.Material.TINT) != 0 && material != null) {
            pass.tintR = material.tintR();
            pass.tintG = material.tintG();
            pass.tintB = material.tintB();
        } else if (pass.useTexture) {
            pass.tintR = pass.tintG = pass.tintB = 1;
        } else {
            int solid = mesh.source.color();
            pass.tintR = ((solid >> 16) & 0xFF) / 255f;
            pass.tintG = ((solid >> 8) & 0xFF) / 255f;
            pass.tintB = (solid & 0xFF) / 255f;
        }
        pass.lighting = options.lighting() && (material == null || (flags & BinModel.Material.LIT) != 0);
        pass.diffuse = mesh.diffuse;
        pass.normal = mesh.normal;
        return pass;
    }

    private void drawMesh(RenderMesh mesh, boolean solidPass) {
        Pass pass = passFor(mesh, solidPass);
        float[] p = mesh.positions;
        float[] n = mesh.normals;
        float[] uv = mesh.uvs;
        float[][] corners = new float[3][];
        for (int t = 0; t + 8 < p.length; t += 9) {
            for (int c = 0; c < 3; c++) {
                int at = t + c * 3;
                int uvAt = (t / 3 + c) * 2;
                corners[c] = vertex(p[at], p[at + 1], p[at + 2], uv[uvAt], uv[uvAt + 1],
                        n[at], n[at + 1], n[at + 2]);
            }
            float[] tangents = pass.useNormal ? tangentFrame(p, uv, t) : null;
            List<float[]> polygon = clipNear(corners);
            if (polygon.size() < 3) continue;
            for (float[] vertex : polygon) project(vertex);
            if (pass.cull && isFrontFacing(polygon)) continue;
            for (int i = 1; i + 1 < polygon.size(); i++) {
                rasterize(polygon.get(0), polygon.get(i), polygon.get(i + 1), pass, tangents);
            }
        }
    }

    private void drawWireframe(RenderMesh mesh) {
        BinModel.Material material = mesh.source.material();
        long flags = material != null ? material.flags() : 0;
        float[] p = mesh.positions;
        float[] n = mesh.normals;
        float[] uv = mesh.uvs;
        float[][] corners = new float[3][];
        boolean cull = true;
        for (int t = 0; t + 8 < p.length; t += 9) {
            for (int c = 0; c < 3; c++) {
                int at = t + c * 3;
                int uvAt = (t / 3 + c) * 2;
                corners[c] = vertex(p[at], p[at + 1], p[at + 2], uv[uvAt], uv[uvAt + 1],
                        n[at], n[at + 1], n[at + 2]);
            }
            List<float[]> polygon = clipNear(corners);
            if (polygon.size() < 3) continue;
            for (float[] vertex : polygon) project(vertex);
            if (cull && isFrontFacing(polygon) && (flags & BinModel.Material.TWO_SIDED) == 0
                    && !mesh.source.doubleSided()) {
                continue;
            }
            for (int c = 0; c < 3; c++) {
                float[] a = corners[c];
                float[] b = corners[(c + 1) % 3];
                drawLine(a[WX], a[WY], a[WZ], b[WX], b[WY], b[WZ], WIRE_COLOR, false, 1.002f);
            }
        }
    }

    // Vertex layout: view x, y, z, u, v, world x, y, z, normal x, y, z, screen x, y, 1/z.
    private static final int VX = 0, VY = 1, VZ = 2, U = 3, V = 4, WX = 5, WY = 6, WZ = 7;
    private static final int NX = 8, NY = 9, NZ = 10, SX = 11, SY = 12, IZ = 13, STRIDE = 14;

    private float[] vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        float dx = x - eye[0];
        float dy = y - eye[1];
        float dz = z - eye[2];
        float[] out = new float[STRIDE];
        out[VX] = dx * right[0] + dy * right[1] + dz * right[2];
        out[VY] = dx * up[0] + dy * up[1] + dz * up[2];
        out[VZ] = dx * forward[0] + dy * forward[1] + dz * forward[2];
        out[U] = u;
        out[V] = v;
        out[WX] = x;
        out[WY] = y;
        out[WZ] = z;
        out[NX] = nx;
        out[NY] = ny;
        out[NZ] = nz;
        return out;
    }

    /** Clips a triangle against the near plane, returning a polygon of up to four corners. */
    private List<float[]> clipNear(float[][] triangle) {
        List<float[]> output = new ArrayList<>(4);
        for (int i = 0; i < 3; i++) {
            float[] a = triangle[i];
            float[] b = triangle[(i + 1) % 3];
            boolean aIn = a[VZ] >= near;
            boolean bIn = b[VZ] >= near;
            if (aIn) output.add(a.clone());
            if (aIn != bIn) {
                float t = (near - a[VZ]) / (b[VZ] - a[VZ]);
                float[] mid = new float[STRIDE];
                for (int k = 0; k <= NZ; k++) mid[k] = a[k] + (b[k] - a[k]) * t;
                output.add(mid);
            }
        }
        return output;
    }

    private void project(float[] vertex) {
        float inverse = 1f / vertex[VZ];
        vertex[SX] = width / 2f + vertex[VX] * focalPixels * inverse;
        vertex[SY] = height / 2f - vertex[VY] * focalPixels * inverse;
        vertex[IZ] = inverse;
    }

    /**
     * True for a face wound counter-clockwise as OpenGL sees it, which is the face the
     * KPod renderer culls. Screen Y grows downward here, so the sign is reversed.
     */
    private static boolean isFrontFacing(List<float[]> polygon) {
        float[] a = polygon.get(0);
        float[] b = polygon.get(1);
        float[] c = polygon.get(2);
        float area = (b[SX] - a[SX]) * (c[SY] - a[SY]) - (c[SX] - a[SX]) * (b[SY] - a[SY]);
        return area < 0;
    }

    /**
     * The tangent and bitangent of one triangle, from its edges and UVs. These are
     * dP/du and dP/dv, which is what a normal map's red and green axes follow.
     */
    private static float[] tangentFrame(float[] p, float[] uv, int t) {
        int uvAt = t / 3 * 2;
        float q1x = p[t + 3] - p[t], q1y = p[t + 4] - p[t + 1], q1z = p[t + 5] - p[t + 2];
        float q2x = p[t + 6] - p[t], q2y = p[t + 7] - p[t + 1], q2z = p[t + 8] - p[t + 2];
        float s1 = uv[uvAt + 2] - uv[uvAt], t1 = uv[uvAt + 3] - uv[uvAt + 1];
        float s2 = uv[uvAt + 4] - uv[uvAt], t2 = uv[uvAt + 5] - uv[uvAt + 1];
        float det = s1 * t2 - s2 * t1;
        if (Math.abs(det) < 1e-12f) return null;
        float r = 1f / det;
        float[] frame = {
                (q1x * t2 - q2x * t1) * r, (q1y * t2 - q2y * t1) * r, (q1z * t2 - q2z * t1) * r,
                (q2x * s1 - q1x * s2) * r, (q2y * s1 - q1y * s2) * r, (q2z * s1 - q1z * s2) * r,
        };
        normalize3(frame, 0);
        normalize3(frame, 3);
        return frame;
    }

    private void rasterize(float[] a, float[] b, float[] c, Pass pass, float[] tangents) {
        float area = (b[SX] - a[SX]) * (c[SY] - a[SY]) - (c[SX] - a[SX]) * (b[SY] - a[SY]);
        if (Math.abs(area) < 1e-6f) return;
        int minX = Math.max(0, (int) Math.floor(Math.min(a[SX], Math.min(b[SX], c[SX]))));
        int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(a[SX], Math.max(b[SX], c[SX]))));
        int minY = Math.max(0, (int) Math.floor(Math.min(a[SY], Math.min(b[SY], c[SY]))));
        int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(a[SY], Math.max(b[SY], c[SY]))));
        if (minX > maxX || minY > maxY) return;

        float inverseArea = 1f / area;
        // Edge functions: w0 weights a, w1 weights b, w2 weights c.
        float a0 = (b[SY] - c[SY]) * inverseArea, b0 = (c[SX] - b[SX]) * inverseArea;
        float c0 = (b[SX] * c[SY] - c[SX] * b[SY]) * inverseArea;
        float a1 = (c[SY] - a[SY]) * inverseArea, b1 = (a[SX] - c[SX]) * inverseArea;
        float c1 = (c[SX] * a[SY] - a[SX] * c[SY]) * inverseArea;

        float iza = a[IZ], izb = b[IZ], izc = c[IZ];
        float[] fragment = new float[8];
        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            int row = y * width;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f;
                float w0 = a0 * px + b0 * py + c0;
                if (w0 < 0) continue;
                float w1 = a1 * px + b1 * py + c1;
                if (w1 < 0) continue;
                float w2 = 1f - w0 - w1;
                if (w2 < 0) continue;
                float iz = w0 * iza + w1 * izb + w2 * izc;
                int index = row + x;
                if (iz <= depth[index]) continue;
                float z = 1f / iz;
                float pa = w0 * iza * z, pb = w1 * izb * z, pc = w2 * izc * z;
                for (int k = 0; k < 8; k++) {
                    int attr = U + k;
                    fragment[k] = a[attr] * pa + b[attr] * pb + c[attr] * pc;
                }
                shade(index, iz, fragment, pass, tangents);
            }
        }
    }

    /** Runs the material for one pixel. {@code f} holds u, v, world xyz, normal xyz. */
    private void shade(int index, float iz, float[] f, Pass pass, float[] tangents) {
        int texel = pass.useTexture ? pass.diffuse.sample(f[0], f[1], options.smooth()) : 0xFFFFFFFF;
        if (pass.rawCutout && (texel & 0x00FFFFFF) == 0) return;
        float alpha = (texel >>> 24) / 255f;
        if (pass.alphaTest && alpha < pass.alphaReference) return;

        float nx = f[5], ny = f[6], nz = f[7];
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 0) { nx /= length; ny /= length; nz /= length; }
        if (pass.useNormal && tangents != null) {
            int mapped = pass.normal.sample(f[0], f[1], options.smooth());
            float mx = (((mapped >> 16) & 0xFF) / 255f * 2 - 1) * pass.normalStrength;
            float my = -(((mapped >> 8) & 0xFF) / 255f * 2 - 1) * pass.normalStrength;
            float mz = (mapped & 0xFF) / 255f * 2 - 1;
            float tx = tangents[0] * mx + tangents[3] * my + nx * mz;
            float ty = tangents[1] * mx + tangents[4] * my + ny * mz;
            float tz = tangents[2] * mx + tangents[5] * my + nz * mz;
            float l = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (l > 0) { nx = tx / l; ny = ty / l; nz = tz / l; }
        }

        float r = ((texel >> 16) & 0xFF) / 255f * pass.tintR;
        float g = ((texel >> 8) & 0xFF) / 255f * pass.tintG;
        float b = (texel & 0xFF) / 255f * pass.tintB;
        float light = 1f;
        if (pass.lighting) {
            float lx = options.lightPosition()[0] * modelSize - f[2];
            float ly = options.lightPosition()[1] * modelSize - f[3];
            float lz = options.lightPosition()[2] * modelSize - f[4];
            float ll = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
            if (ll > 0) { lx /= ll; ly /= ll; lz /= ll; }
            float vx = eye[0] - f[2], vy = eye[1] - f[3], vz = eye[2] - f[4];
            float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (vl > 0) { vx /= vl; vy /= vl; vz /= vl; }
            float hx = lx + vx, hy = ly + vy, hz = lz + vz;
            float hl = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
            if (hl > 0) { hx /= hl; hy /= hl; hz /= hl; }
            float diffuse = Math.max(nx * lx + ny * ly + nz * lz, 0);
            float specular = (float) Math.pow(Math.max(nx * hx + ny * hy + nz * hz, 0), pass.shininess);
            light = 0.8f + diffuse * 0.75f + specular * 0.35f;
        }
        float scale = light + pass.emissive;
        r *= scale;
        g *= scale;
        b *= scale;

        int destination = color[index];
        if (pass.blend || pass.additive) {
            float a = alpha * pass.opacity;
            float dr = ((destination >> 16) & 0xFF) / 255f;
            float dg = ((destination >> 8) & 0xFF) / 255f;
            float db = (destination & 0xFF) / 255f;
            if (pass.additive) {
                r = dr + r * a; g = dg + g * a; b = db + b * a;
            } else {
                r = dr * (1 - a) + r * a; g = dg * (1 - a) + g * a; b = db * (1 - a) + b * a;
            }
        }
        color[index] = 0xFF000000 | (channel(r) << 16) | (channel(g) << 8) | channel(b);
        if (pass.depthWrite) {
            depth[index] = iz;
        }
    }

    // -------------------------------------------------------------------------
    // Lines
    // -------------------------------------------------------------------------

    /**
     * Draws a depth-tested world-space line. {@code bias} above one lets a line win
     * against the surface it lies on, which is what a wireframe overlay needs.
     */
    private void drawLine(float x0, float y0, float z0, float x1, float y1, float z1,
            int argb, boolean writeDepth, float bias) {
        float[] a = vertex(x0, y0, z0, 0, 0, 0, 0, 0);
        float[] b = vertex(x1, y1, z1, 0, 0, 0, 0, 0);
        if (a[VZ] < near && b[VZ] < near) return;
        if (a[VZ] < near || b[VZ] < near) {
            float t = (near - a[VZ]) / (b[VZ] - a[VZ]);
            float[] mid = new float[STRIDE];
            for (int k = 0; k <= NZ; k++) mid[k] = a[k] + (b[k] - a[k]) * t;
            if (a[VZ] < near) a = mid; else b = mid;
        }
        project(a);
        project(b);
        float dx = b[SX] - a[SX];
        float dy = b[SY] - a[SY];
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
        if (steps > 20000) return;
        for (int i = 0; i <= steps; i++) {
            float t = steps == 0 ? 0 : i / (float) steps;
            int x = (int) (a[SX] + dx * t);
            int y = (int) (a[SY] + dy * t);
            if (x < 0 || y < 0 || x >= width || y >= height) continue;
            float iz = a[IZ] + (b[IZ] - a[IZ]) * t;
            int index = y * width + x;
            if (iz * bias <= depth[index]) continue;
            color[index] = argb;
            if (writeDepth) depth[index] = iz;
        }
    }

    private void buildGrid(float size, float centerX, float height, float centerZ) {
        int divisions = Math.max(10, Math.min(40, (int) Math.ceil(size * 2)));
        float[] lines = new float[(divisions + 1) * 12];
        int at = 0;
        for (int i = 0; i <= divisions; i++) {
            float p = -size * 1.5f + size * 3 * i / divisions;
            float[] pair = {
                    centerX + p, height, centerZ - size * 1.5f, centerX + p, height, centerZ + size * 1.5f,
                    centerX - size * 1.5f, height, centerZ + p, centerX + size * 1.5f, height, centerZ + p,
            };
            System.arraycopy(pair, 0, lines, at, 12);
            at += 12;
        }
        gridLines = lines;
    }

    // -------------------------------------------------------------------------
    // Prepared data
    // -------------------------------------------------------------------------

    /** A mesh in view orientation with its textures ready to sample. */
    private static final class RenderMesh {
        private final BinModel.Mesh source;
        private final float[] positions;
        private final float[] normals;
        private final float[] uvs;
        private final Texture diffuse;
        private final Texture normal;
        private final boolean isRaw;
        private final boolean hasTextureAlpha;

        private RenderMesh(BinModel.Mesh source, LoadedTexture texture) {
            this.source = source;
            float[] p = source.positions();
            float[] n = source.normals();
            float[] t = source.uvs();
            int vertices = p.length / 3;
            positions = new float[vertices * 3];
            normals = new float[vertices * 3];
            uvs = new float[vertices * 2];
            diffuse = texture != null && texture.diffuse() != null ? new Texture(texture.diffuse()) : null;
            normal = texture != null && texture.normal() != null ? new Texture(texture.normal()) : null;
            for (int i = 0; i < vertices; i++) {
                // (x, y, z) to (x, z, -y): the .BIN Z-up convention onto a Y-up screen.
                positions[i * 3] = p[i * 3];
                positions[i * 3 + 1] = p[i * 3 + 2];
                positions[i * 3 + 2] = -p[i * 3 + 1];
                normals[i * 3] = n[i * 3];
                normals[i * 3 + 1] = n[i * 3 + 2];
                normals[i * 3 + 2] = -n[i * 3 + 1];
                uvs[i * 2] = snapUv(t[i * 2], diffuse == null ? 0 : diffuse.width);
                uvs[i * 2 + 1] = snapUv(t[i * 2 + 1], diffuse == null ? 0 : diffuse.height);
            }
            // A RAW with an .OPA merged in carries real alpha, so it must not also be
            // run through the colour-key cutout the MTM family needs.
            isRaw = texture != null && texture.isRaw() && !texture.hasAlpha();
            hasTextureAlpha = texture != null && texture.hasAlpha();
        }

        /** Clamps a UV and moves it to the nearest texel centre, as KPod does. */
        private static float snapUv(float value, int size) {
            float clamped = Math.max(0, Math.min(1, value));
            if (size <= 1) return clamped;
            float texel = ((float) Math.floor(clamped * (size - 1)) + 0.5f) / size;
            return Math.max(0.5f / size, Math.min(1 - 0.5f / size, texel));
        }
    }

    /** ARGB pixels with row 0 at the top, sampled with V running bottom-up. */
    private static final class Texture {
        private final int width;
        private final int height;
        private final int[] pixels;

        private Texture(BufferedImage image) {
            width = image.getWidth();
            height = image.getHeight();
            pixels = image.getRGB(0, 0, width, height, null, 0, width);
        }

        private int sample(float u, float v, boolean smooth) {
            float x = u * width;
            float y = (1 - v) * height;
            if (!smooth) {
                int ix = Math.max(0, Math.min(width - 1, (int) x));
                int iy = Math.max(0, Math.min(height - 1, (int) y));
                return pixels[iy * width + ix];
            }
            x -= 0.5f;
            y -= 0.5f;
            int x0 = (int) Math.floor(x);
            int y0 = (int) Math.floor(y);
            float fx = x - x0;
            float fy = y - y0;
            int c00 = at(x0, y0), c10 = at(x0 + 1, y0), c01 = at(x0, y0 + 1), c11 = at(x0 + 1, y0 + 1);
            int result = 0;
            for (int shift = 0; shift < 32; shift += 8) {
                float top = ((c00 >>> shift) & 0xFF) * (1 - fx) + ((c10 >>> shift) & 0xFF) * fx;
                float bottom = ((c01 >>> shift) & 0xFF) * (1 - fx) + ((c11 >>> shift) & 0xFF) * fx;
                result |= Math.round(top * (1 - fy) + bottom * fy) << shift;
            }
            return result;
        }

        private int at(int x, int y) {
            return pixels[Math.max(0, Math.min(height - 1, y)) * width + Math.max(0, Math.min(width - 1, x))];
        }
    }

    // -------------------------------------------------------------------------
    // Small vector helpers
    // -------------------------------------------------------------------------

    private static float sq(float value) {
        return value * value;
    }

    private static float clamp(float value) {
        return Math.max(0, Math.min(1, value));
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255)));
    }

    private static void normalize(float[] v) {
        normalize3(v, 0);
    }

    private static void normalize3(float[] v, int at) {
        float length = (float) Math.sqrt(v[at] * v[at] + v[at + 1] * v[at + 1] + v[at + 2] * v[at + 2]);
        if (length > 0) {
            v[at] /= length;
            v[at + 1] /= length;
            v[at + 2] /= length;
        }
    }

    private static void cross(float[] a, float[] b, float[] out) {
        out[0] = a[1] * b[2] - a[2] * b[1];
        out[1] = a[2] * b[0] - a[0] * b[2];
        out[2] = a[0] * b[1] - a[1] * b[0];
    }
}
