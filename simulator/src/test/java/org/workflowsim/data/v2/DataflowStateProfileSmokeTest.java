package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** End-to-end profile/comparator smoke, with semantic fingerprints and no timing threshold (B5). */
class DataflowStateProfileSmokeTest {

    @TempDir
    Path directory;

    @Test
    void profileAndIndependentComparatorAcceptMatchingEvidence() throws Exception {
        Path before = directory.resolve("before-ONLINE_V3-OFF.jsonl");
        Path after = directory.resolve("after-ONLINE_V3-OFF.jsonl");
        runProfile(before);
        runProfile(after);
        Path comparison = directory.resolve("comparison.json");
        Path markdown = directory.resolve("comparison.md");
        Process comparator = new ProcessBuilder(
                        "python3",
                        "-B",
                        Paths.get("..", "scripts", "compare-dataflow-profile.py")
                                .toAbsolutePath()
                                .toString(),
                        directory.toString(),
                        comparison.toString(),
                        markdown.toString())
                .redirectErrorStream(true)
                .start();
        String diagnostics = readUtf8(comparator);
        assertEquals(0, comparator.waitFor(), diagnostics);
        JsonObject report = JsonParser.parseString(new String(Files.readAllBytes(comparison), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("PASS", report.get("status").getAsString());
        assertEquals(2, report.get("caseCount").getAsInt());
    }

    private static String readUtf8(Process process) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = process.getInputStream().read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private void runProfile(Path output) throws IOException, InterruptedException {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(
                        java,
                        "-Xms128m",
                        "-Xmx128m",
                        "-cp",
                        System.getProperty("java.class.path"),
                        "org.workflowsim.data.v2.DataflowStateProfile",
                        "ONLINE_V3",
                        "10,20",
                        "OFF",
                        "2")
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        assertEquals(0, process.waitFor(), new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
    }
}
