package com.mtm2.jpod.io.model;

import com.mtm2.jpod.io.pod.PodArchive;
import com.mtm2.jpod.io.pod.PodArchiveReader;
import com.mtm2.jpod.io.pod.PodArchiveWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelDecoderTest {

    // -------------------------------------------------------------------------
    // BIN
    // -------------------------------------------------------------------------

    @Test
    void aColorBlockDoesNotClearTheActiveTexture() {
        ByteArrayOutputStream data = staticHeader();
        addTexture(data, 13, "LOADER.RAW", 16);
        addMappedTriangle(data, 0x18, 0, 1, 2);
        addInt(data, 0x0A);
        addInt(data, 0x00332211);
        addFlatTriangle(data, 0x19, 0, 1, 2);
        addMappedTriangle(data, 0x18, 0, 1, 2);
        addInt(data, 0);

        BinModel model = BinModelDecoder.decode(data.toByteArray(), "LOADER.BIN");

        assertEquals("BIN", model.format());
        assertEquals(3, model.polygonCount());
        assertEquals(List.of("LOADER.RAW"), model.textureNames());
        BinModel.Mesh textured = model.meshes().stream()
                .filter(mesh -> mesh.textureName().equals("LOADER.RAW")).findFirst().orElseThrow();
        assertEquals(18, textured.positions().length);
        BinModel.Mesh solid = model.meshes().stream().filter(BinModel.Mesh::solid).findFirst().orElseThrow();
        assertEquals("", solid.textureName());
        assertEquals(0x00332211, solid.color());
    }

    @Test
    void decodesAnExtendedTextureMaterialAndNormalStrength() {
        ByteArrayOutputStream data = staticHeader();
        addTexture(data, 62, "A_VERY_LONG_TEXTURE_NAME.PNG", 64);
        addInt(data, 63);
        addInt(data, 0x248F);
        for (int value : new int[] {32768, 0, 65536, 32768, 16 * 65536, 16384, 65536, 32768, 65536}) {
            addInt(data, value);
        }
        addInt(data, 0x8040);
        addInt(data, 66);
        addInt(data, 1);
        addInt(data, 131072);
        for (int i = 0; i < 5; i++) addInt(data, 0);
        addMappedTriangle(data, 64, 1, 2, 3);
        addInt(data, 0);

        BinModel model = BinModelDecoder.decode(data.toByteArray(), "HD.BIN");

        assertEquals(1, model.meshes().size());
        BinModel.Mesh mesh = model.meshes().get(0);
        assertEquals("A_VERY_LONG_TEXTURE_NAME.PNG", mesh.textureName());
        assertNotNull(mesh.material());
        assertEquals(0.5f, mesh.material().baseAlpha());
        assertEquals(2f, mesh.material2().normalStrength());
        assertEquals(9, mesh.positions().length);
    }

    @Test
    void anUnknownOpcodeTruncatesWithAWarning() {
        ByteArrayOutputStream data = staticHeader();
        addInt(data, 123456);
        BinModel model = BinModelDecoder.decode(data.toByteArray(), "BAD.BIN");
        assertTrue(model.warnings().stream().anyMatch(w -> w.contains("Unsupported BIN opcode")));
    }

    @Test
    void anAnimatedBinNamesItsFramesAndHasNoGeometryOfItsOwn() {
        BinModel model = BinModelDecoder.decode(animatedHeader("rex1.bin", "rex2.bin", "rex3.bin", "rex4.bin"), "REX.BIN");
        assertEquals("ANIMATED_BIN", model.format());
        assertEquals(List.of("REX1.BIN", "REX2.BIN", "REX3.BIN", "REX4.BIN"), model.frameNames());
        assertEquals(0, model.vertexCount());
        assertTrue(model.meshes().isEmpty());
        assertTrue(BinModelDecoder.decode(staticHeader().toByteArray(), "HAY.BIN").frameNames().isEmpty());
    }

    @Test
    void aTruncatedFrameTableIsReadAsFarAsItGoes() {
        byte[] data = animatedHeader("rex1.bin", "rex2.bin");
        data[8] = 4; // the count claims four frames but only two slots were written
        assertEquals(List.of("REX1.BIN", "REX2.BIN"), BinModelDecoder.decode(data, "REX.BIN").frameNames());
    }

    @Test
    void framesResolveFromModelsFirstAndReportTheMissingOnes() throws Exception {
        PodArchive archive = archive("MODELS\\REX1.BIN", new byte[4], "REX2.BIN", new byte[4]);
        BinModel model = BinModelDecoder.decode(animatedHeader("rex1", "rex2", "rex3"), "REX.BIN");
        List<BinFrameResolver.Frame> frames = BinFrameResolver.resolve(archive, model);
        assertEquals("MODELS\\REX1.BIN", frames.get(0).entry().name());
        assertEquals("REX2.BIN", frames.get(1).entry().name());
        assertNull(frames.get(2).entry());
    }

    private static byte[] animatedHeader(String... frames) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        addInt(data, 0x20);
        addInt(data, 0);
        addInt(data, frames.length);
        addInt(data, 0);
        addInt(data, 0); // vertex count, read by the shared payload parser
        addInt(data, 0); // end-of-blocks token, so that parser stops before the names
        for (String frame : frames) {
            byte[] encoded = frame.getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < 16; i++) data.write(i < encoded.length ? encoded[i] : 0);
        }
        return data.toByteArray();
    }

    private static ByteArrayOutputStream staticHeader() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        addInt(data, 0x14);
        addInt(data, 65536);
        addInt(data, 0);
        addInt(data, 0);
        addInt(data, 3);
        addVertex(data, 0, 0, 0);
        addVertex(data, 128, 0, 0);
        addVertex(data, 0, 128, 0);
        return data;
    }

    private static void addVertex(ByteArrayOutputStream data, int x, int y, int z) {
        addInt(data, x * 2);
        addInt(data, z * 2);
        addInt(data, y * 2);
    }

    private static void addTexture(ByteArrayOutputStream data, int opcode, String name, int nameBytes) {
        addInt(data, opcode);
        addInt(data, 0);
        byte[] encoded = name.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < nameBytes; i++) data.write(i < encoded.length ? encoded[i] : 0);
    }

    private static void addMappedTriangle(ByteArrayOutputStream data, int opcode, int... indices) {
        addInt(data, opcode);
        addInt(data, indices.length);
        for (int i = 0; i < 4; i++) addInt(data, 0);
        for (int index : indices) {
            addInt(data, index);
            addInt(data, 0);
            addInt(data, 0);
        }
    }

    private static void addFlatTriangle(ByteArrayOutputStream data, int opcode, int... indices) {
        addInt(data, opcode);
        addInt(data, indices.length);
        for (int i = 0; i < 4; i++) addInt(data, 0);
        for (int index : indices) addInt(data, index);
    }

    private static void addInt(ByteArrayOutputStream data, int value) {
        data.write(value);
        data.write(value >>> 8);
        data.write(value >>> 16);
        data.write(value >>> 24);
    }

    // -------------------------------------------------------------------------
    // SMF
    // -------------------------------------------------------------------------

    @Test
    void decodesAVersionFourModelIntoTriangleSoupDrawnFromBothSides() {
        BinModel model = SmfModelDecoder.decode(smf(4, quad("OPAQUE", "WALL.RAW"), 1), "WALL.SMF");
        assertEquals("SMF v4", model.format());
        assertEquals(List.of("WALL.RAW"), model.textureNames());
        BinModel.Mesh mesh = model.meshes().get(0);
        assertEquals(1, model.meshes().size());
        assertEquals(18, mesh.positions().length);
        assertEquals(18, mesh.normals().length);
        assertEquals(12, mesh.uvs().length);
        assertEquals(2, model.polygonCount());
        assertEquals(4, model.vertexCount());
        assertTrue(model.warnings().isEmpty());
        assertTrue(mesh.doubleSided());
    }

    /**
     * Evo is Y-up and .BIN is Z-up, so Y and Z swap and normals negate; composed with
     * the renderer's own (x, z, -y), Evo's Z comes out negated and nothing else moves.
     */
    @Test
    void swapsEvoAxesSoTheRendererNegatesEvoZAndNothingElse() {
        BinModel.Mesh mesh = SmfModelDecoder.decode(smf(4, quad("OPAQUE", "WALL.RAW"), 1), "WALL.SMF")
                .meshes().get(0);
        assertEquals(0f, mesh.positions()[1]);
        assertEquals(10f, mesh.positions()[2]);
        assertEquals(-1f, mesh.normals()[1]);
        assertEquals(0.75f, mesh.uvs()[1]); // V inverted: 1 - 0.25

        float[][] evo = {{0, 10, 0}, {1, 10, 0}, {1, 0, 0}, {0, 0, 0}};
        int[] cornerToVertex = {0, 1, 2, 0, 2, 3};
        for (int corner = 0; corner < cornerToVertex.length; corner++) {
            float[] source = evo[cornerToVertex[corner]];
            float[] p = mesh.positions();
            assertEquals(source[0], p[corner * 3]);
            assertEquals(source[1], p[corner * 3 + 2]);
            assertEquals(-source[2], -p[corner * 3 + 1]);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void olderVersionsHaveNoLodHeader(int version) {
        BinModel model = SmfModelDecoder.decode(smf(version, quad("transp", "GLASS.RAW"), 1), "GLASS.SMF");
        assertEquals("SMF v" + version, model.format());
        assertEquals(18, model.meshes().get(0).positions().length);
    }

    @Test
    void readsTheEvoTwoBumpMaterialFormAndSkipsExtraFrames() {
        String bump = String.join("\n", "OPAQUE", "1", "1", "4,1,2,0", "v1",
                "1,1,32,0,0,PALM.TIF", "PALM_BUMP.TIF", vertices(), faces());
        BinModel model = SmfModelDecoder.decode(smf(4, bump, 1), "PALM.SMF");
        assertEquals(List.of("PALM.TIF"), model.textureNames());
        assertTrue(model.warnings().isEmpty());

        String animated = String.join("\n", "OPAQUE", "1", "1", "4,3,2,0",
                "1,1,32,0,0,FLAG.RAW", vertices(), vertices(), vertices(), faces());
        BinModel flag = SmfModelDecoder.decode(smf(4, animated, 1), "FLAG.SMF");
        assertEquals(18, flag.meshes().get(0).positions().length);
        assertTrue(flag.warnings().isEmpty());
    }

    @Test
    void aFaceIndexOutsideTheVertexBlockIsDroppedAndReported() {
        String group = String.join("\n", "OPAQUE", "1", "1", "4,1,2,0",
                "1,1,32,0,0,WALL.RAW", vertices(), "0,1,2", "0,2,99");
        BinModel model = SmfModelDecoder.decode(smf(4, group, 1), "BROKEN.SMF");
        assertEquals(9, model.meshes().get(0).positions().length);
        assertTrue(model.warnings().stream().anyMatch(w -> w.contains("indexes outside")));
    }

    @Test
    void reducedDetailAndHiddenGroupsAreDroppedButNeverEmptyAModel() {
        BinModel pair = SmfModelDecoder.decode(smf(4,
                quad("OPAQUE", "HI.RAW") + "\n" + quad("OPAQUEL", "LO.RAW"), 2), "TREE.SMF");
        assertEquals(1, pair.meshes().size());
        assertEquals("OPAQUE", pair.meshes().get(0).groupName());

        BinModel lonely = SmfModelDecoder.decode(smf(4, quad("OPAQUEL", "LO.RAW"), 1), "STUMP.SMF");
        assertEquals("OPAQUEL", lonely.meshes().get(0).groupName());
        assertTrue(lonely.warnings().stream().anyMatch(w -> w.contains("reduced-detail")));

        BinModel transpi = SmfModelDecoder.decode(smf(4,
                quad("OPAQUE", "HI.RAW") + "\n" + quad("TRANSPI", "OTHER.RAW"), 2), "PROP.SMF");
        assertEquals(2, transpi.meshes().size());

        String hidden = String.join("\n", "TRANSP", "0", "1", "4,1,2,0", "1,1,32,0,0,X.RAW", vertices(), faces());
        BinModel withHidden = SmfModelDecoder.decode(smf(4, quad("OPAQUE", "HI.RAW") + "\n" + hidden, 2), "P.SMF");
        assertEquals(1, withHidden.meshes().size());
    }

    @Test
    void recognisesAndRefusesByContent() {
        assertTrue(SmfModelDecoder.isSmfModel(smf(4, quad("OPAQUE", "W.RAW"), 1)));
        assertFalse(SmfModelDecoder.isSmfModel("C3D".getBytes(StandardCharsets.ISO_8859_1)));
        assertThrows(IllegalArgumentException.class,
                () -> SmfModelDecoder.decode(smf(9, quad("OPAQUE", "W.RAW"), 1), "FUTURE.SMF"));
        assertThrows(IllegalArgumentException.class,
                () -> SmfModelDecoder.decode("nope\n".getBytes(StandardCharsets.ISO_8859_1), "NOPE.SMF"));
        String truncated = String.join("\n", "OPAQUE", "1", "1", "4,1,2,0", "1,1,32,0,0,W.RAW",
                "0,10,0,0,0,1,0,0.25");
        assertThrows(IllegalArgumentException.class,
                () -> SmfModelDecoder.decode(smf(4, truncated, 1), "SHORT.SMF"));
    }

    private static byte[] smf(int version, String groups, int objectCount) {
        List<String> lines = new ArrayList<>(List.of("C3DModel", Integer.toString(version),
                Integer.toString(objectCount)));
        if (version >= 4) lines.add("0,50.000000");
        lines.add(groups);
        lines.add("");
        return String.join("\n", lines).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String quad(String groupName, String textureName) {
        return String.join("\n", groupName, "1", "1", "4,1,2,0", "1,1,32,0,0," + textureName,
                vertices(), faces());
    }

    private static String vertices() {
        return String.join("\n", "0,10,0,0,0,1,0,0.25", "1,10,0,0,0,1,1,0.25",
                "1,0,0,0,0,1,1,0.75", "0,0,0,0,0,1,0,0.75");
    }

    private static String faces() {
        return String.join("\n", "0,1,2", "0,2,3");
    }

    // -------------------------------------------------------------------------
    // Texture resolution
    // -------------------------------------------------------------------------

    @Test
    void prefersPngThenTgaThenTifThenRawAndFindsCompanions() throws Exception {
        PodArchive archive = archive(
                "ART\\BODY.RAW", new byte[4], "ART\\BODY.ACT", new byte[768],
                "ART\\BODY.TGA", new byte[4], "ART\\BODY.PNG", new byte[4],
                "ART\\BODY_N.PNG", new byte[4], "ART\\BODY.OPA", new byte[4]);
        BinTextureResolver.Entries result = BinTextureResolver.resolve(archive, "BODY.RAW");
        assertEquals("ART\\BODY.PNG", result.diffuse().name());
        assertEquals("ART\\BODY_N.PNG", result.normal().name());
        assertEquals("ART\\BODY.ACT", result.sameNamePalette().name());
        assertEquals("ART\\BODY.OPA", result.opacityPlane().name());

        PodArchive tiff = archive("ART\\PALMFAN.RAW", new byte[4], "ART\\PALMFAN.TIF", new byte[4]);
        assertEquals("ART\\PALMFAN.TIF", BinTextureResolver.resolve(tiff, "PALMFAN.TIF").diffuse().name());
    }

    @Test
    void searchesKnownFoldersBeforeTheTitleFallback() throws Exception {
        PodArchive archive = archive("OTHER\\STONE.TGA", new byte[4], "DATA\\STONE.TGA", new byte[4]);
        assertEquals("DATA\\STONE.TGA", BinTextureResolver.resolve(archive, "STONE").diffuse().name());
    }

    private static PodArchive archive(Object... namesAndPayloads) throws Exception {
        List<PodArchiveWriter.Blob> blobs = new ArrayList<>();
        for (int i = 0; i < namesAndPayloads.length; i += 2) {
            blobs.add(new PodArchiveWriter.Blob((String) namesAndPayloads[i],
                    (byte[]) namesAndPayloads[i + 1], null, null, 0));
        }
        return new PodArchiveReader().read(new PodArchiveWriter().buildBytes("", blobs));
    }
}
