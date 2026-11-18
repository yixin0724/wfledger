package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiments.rerun.RerunExecutor.RerunExecution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

class RerunExecutorTest {

    private static Path fixtureRun;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void generateFixture() throws Exception {
        fixtureRun = Files.createTempDirectory("rerun-executor-fixture");
        RerunTestSupport.generateEvidence(fixtureRun);
    }

    @Test
    void executesRerunPipelineAndWritesNewTriple() throws Exception {
        Path output = tempDir.resolve("rerun-out");
        RerunExecution execution = RerunExecutor.execute(fixtureRun, output);

        Path rerunDir = output.resolve("rerun");
        assertEquals(
                rerunDir.resolve("result.manifest.json"),
                execution.getArtifacts().getManifest());
        assertEquals(
                rerunDir.resolve("result.metrics.json"),
                execution.getArtifacts().getMetrics());
        assertEquals(
                rerunDir.resolve("result.events.jsonl"),
                execution.getArtifacts().getEvents());
        assertTrue(Files.exists(execution.getArtifacts().getManifest()));
        assertTrue(Files.exists(execution.getArtifacts().getMetrics()));
        assertTrue(Files.exists(execution.getArtifacts().getEvents()));

        RerunEvidence rerunEvidence = RerunEvidenceReader.read(rerunDir);
        assertTrue(rerunEvidence.getEventCount() > 0);

        String rerunInputPath = rerunEvidence
                .getManifest()
                .getAsJsonArray("inputs")
                .get(0)
                .getAsJsonObject()
                .get("path")
                .getAsString();
        assertEquals(execution.getInputs().get(0).getResolvedPath().toString(), rerunInputPath);

        assertEquals(
                metricsMakespan(fixtureRun.resolve("result.metrics.json")),
                metricsMakespan(execution.getArtifacts().getMetrics()));
    }

    @Test
    void refusesNonEmptyOutputDirectory() throws Exception {
        Path output = Files.createDirectories(tempDir.resolve("occupied"));
        Files.write(output.resolve("stranger.txt"), new byte[] {1});

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> RerunExecutor.execute(fixtureRun, output));
        assertTrue(failure.getMessage().contains("must be empty"), failure.getMessage());
        assertFalse(Files.exists(output.resolve("rerun")));
    }

    @Test
    void propagatesInputUnresolvedWithoutWritingArtifacts() throws Exception {
        Path run = copyFixtureTo("unresolved");
        rewriteInputPath(run, "/nonexistent-root/datasets/missing.dax");
        Path output = tempDir.resolve("unresolved-out");

        RerunFailureException failure =
                assertThrows(RerunFailureException.class, () -> RerunExecutor.execute(run, output));
        assertEquals(RerunVerdict.INPUT_UNRESOLVED, failure.getVerdict());
        assertFalse(Files.exists(output.resolve("rerun")));
    }

    private static double metricsMakespan(Path metricsPath) throws Exception {
        JsonObject document = JsonParser.parseString(
                        new String(Files.readAllBytes(metricsPath), StandardCharsets.UTF_8))
                .getAsJsonObject();
        return document.getAsJsonObject("metrics").get("makespanSeconds").getAsDouble();
    }

    private Path copyFixtureTo(String name) throws Exception {
        Path run = Files.createDirectories(tempDir.resolve(name));
        RerunTestSupport.copyEvidenceFiles(fixtureRun, run);
        return run;
    }

    private static JsonObject readManifest(Path run) throws Exception {
        return JsonParser.parseString(
                        new String(Files.readAllBytes(run.resolve("result.manifest.json")), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static void writeManifest(Path run, JsonObject manifest) throws Exception {
        Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        Files.write(run.resolve("result.manifest.json"), gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));
    }

    private static void rewriteInputPath(Path run, String newPath) throws Exception {
        JsonObject manifest = readManifest(run);
        manifest.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("path", newPath);
        JsonArray workflowPaths = manifest.getAsJsonObject("configuration").getAsJsonArray("workflowPaths");
        workflowPaths.set(0, new JsonPrimitive(newPath));
        manifest.getAsJsonObject("result")
                .getAsJsonArray("workflowOutcomes")
                .get(0)
                .getAsJsonObject()
                .addProperty("path", newPath);
        writeManifest(run, manifest);
    }
}
