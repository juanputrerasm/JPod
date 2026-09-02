package com.mtm2.jpod.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PodManifestParserTest {
    @TempDir Path temp;

    @Test
    void readsPodToolDirectivesCommentsAndRelativeFiles() throws Exception {
        Files.createDirectories(temp.resolve("ART"));
        Files.write(temp.resolve("ART/WALL.RAW"), new byte[] {1, 2});
        Path response = temp.resolve("build.rsp");
        Files.writeString(response, "// PODTool response\npodFilename: demo.pod\n"
                + "volumeName: Demo volume\nART/WALL.RAW\n");

        PodManifestParser.Manifest parsed = new PodManifestParser().parseManifest(response, temp);
        assertEquals("demo.pod", parsed.podFileName());
        assertEquals("Demo volume", parsed.volumeName());
        assertEquals("ART\\WALL.RAW", parsed.blobs().get(0).name());
    }
}
