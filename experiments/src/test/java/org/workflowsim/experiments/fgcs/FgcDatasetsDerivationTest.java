package org.workflowsim.experiments.fgcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class FgcDatasetsDerivationTest {

    @TempDir
    Path directory;

    @Test
    void smallDagPreservesRawInputAndEmitsMatchingDerivationIdentity() throws Exception {
        for (String kind : new String[] {"chain", "fan", "independent"}) {
            Path root = directory.resolve(kind);
            Path raw = root.resolve("raw-" + kind + ".dax");
            Path derived = FgcDatasets.smallDag(root, kind);
            JsonObject sidecar = JsonParser.parseString(new String(
                            Files.readAllBytes(java.nio.file.Paths.get(derived.toString() + ".derivation.json")),
                            StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertTrue(Files.isRegularFile(raw));
            assertEquals(sha256(raw), sidecar.get("sourceSha256").getAsString());
            assertEquals(sha256(derived), sidecar.get("derivedSha256").getAsString());
            assertEquals(
                    raw.toAbsolutePath().toString(), sidecar.get("sourcePath").getAsString());
            assertEquals(
                    derived.toAbsolutePath().toString(),
                    sidecar.get("derivedPath").getAsString());
            assertTrue(derived.toString().endsWith(kind + ".dax"));
        }
    }

    private static String sha256(Path path) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder value = new StringBuilder();
        for (byte item : digest) {
            value.append(String.format("%02x", item & 0xff));
        }
        return value.toString();
    }
}
