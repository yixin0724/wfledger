package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.algorithm;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.algorithms;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.configuration;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.json;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.localConfiguration;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.object;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentManifestWriter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Regression coverage for the Workbench recording switch. The former JSON allow-list had no outlet for the core
 * builder option; accepting it without the lifecycle compatibility check could silently discard requested evidence.
 */
class WorkbenchEventRecordingTest {
    @TempDir
    Path directory;

    private int serial;
    private boolean loggingWasDisabled;

    @BeforeEach
    void prepare() throws Exception {
        loggingWasDisabled = Log.isDisabled();
        Log.disable();
        workflow(directory, "workflow.dax", true);
    }

    @AfterEach
    void restoreLogging() {
        Log.setDisabled(loggingWasDisabled);
    }

    @Test
    void defaultsOnAndExplicitValuesReachEveryCandidateAndSeedCopy() throws Exception {
        for (JsonObject source : Arrays.asList(base(), withEventRecording(base(), true))) {
            WorkbenchConfig loaded = read(source);
            assertEquals(source, loaded.getSource(), "parsing must not rewrite the submitted configuration");
            for (WorkbenchConfig.Candidate candidate : loaded.getCandidates()) {
                assertTrue(candidate.config.isEventRecordingEnabled());
                assertTrue(candidate.config.withRandomSeed(Long.MIN_VALUE).isEventRecordingEnabled());
                assertFalse(
                        ExperimentManifestWriter.configurationSnapshot(candidate.config)
                                .containsKey("eventRecordingEnabled"),
                        "the enabled default keeps the old manifest shape");
            }
        }

        JsonObject source = withEventRecording(base(), false);
        WorkbenchConfig loaded = read(source);
        assertEquals(2, loaded.getCandidates().size());
        for (WorkbenchConfig.Candidate candidate : loaded.getCandidates()) {
            assertFalse(candidate.config.isEventRecordingEnabled());
            assertFalse(candidate.config.withRandomSeed(Long.MAX_VALUE).isEventRecordingEnabled());
            assertEquals(
                    Boolean.FALSE,
                    ExperimentManifestWriter.configurationSnapshot(candidate.config)
                            .get("eventRecordingEnabled"));
        }
    }

    @Test
    void requiresALiteralJsonBoolean() throws Exception {
        for (JsonElement invalid : Arrays.<JsonElement>asList(
                JsonNull.INSTANCE,
                new JsonPrimitive("false"),
                new JsonPrimitive(0),
                new JsonPrimitive(1),
                new JsonArray(),
                new JsonObject())) {
            JsonObject source = base();
            source.getAsJsonObject("simulation").add("eventRecordingEnabled", invalid);
            IllegalArgumentException rejection =
                    assertThrows(IllegalArgumentException.class, () -> read(source), invalid.toString());
            assertContains(rejection, "simulation.eventRecordingEnabled");
            assertContains(rejection, "boolean");
        }
    }

    @Test
    void disabledRecordingRejectsRequestedLifecycleEvidenceBeforeOutputCreation() throws Exception {
        JsonObject source = localConfiguration("workflow.dax", 2, false);
        JsonObject simulation = source.getAsJsonObject("simulation");
        simulation.addProperty("dataMovementModel", "PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1");
        JsonObject evidence = new JsonObject();
        evidence.addProperty("mode", "FLUID_GROUP_LEDGER_V1");
        evidence.addProperty("maxTraceRecords", 100);
        simulation.add("networkEvidence", evidence);
        simulation.addProperty("eventRecordingEnabled", false);
        Path config = json(directory.resolve("incompatible.json"), source);
        Path output = directory.resolve("must-stay-absent");

        IllegalArgumentException rejection =
                assertThrows(IllegalArgumentException.class, () -> Workbench.run(config, output));
        assertContains(rejection, "simulation.eventRecordingEnabled");
        assertContains(rejection, "simulation.networkEvidence");
        assertFalse(Files.exists(output), "invalid evidence combinations must fail during preflight");

        JsonObject enabled = source.deepCopy();
        enabled.getAsJsonObject("simulation").addProperty("eventRecordingEnabled", true);
        WorkbenchConfig loaded = read(enabled);
        assertTrue(loaded.getCandidates().get(0).config.isEventRecordingEnabled());
        assertTrue(
                loaded.getCandidates().get(0).config.getNetworkEvidenceConfig().isEnabled());
    }

    @Test
    void disabledRecordingCompletesTheRunAndPublishesAnExplicitZeroEventManifest() throws Exception {
        JsonObject source = withEventRecording(base(), false);
        Path experiment = Workbench.run(
                json(directory.resolve("disabled-run.json"), source), directory.resolve("disabled-output"));
        JsonObject record = object(experiment.resolve("experiment.json"));
        assertEquals("COMPLETED_SUCCESSFULLY", record.get("status").getAsString());
        JsonObject run = record.getAsJsonArray("runs").get(0).getAsJsonObject();
        assertEquals("COMPLETED_SUCCESSFULLY", run.get("status").getAsString());
        assertTrue(run.get("makespanSeconds").getAsDouble() > 0.0);

        Path manifestPath = experiment.resolve(run.get("manifest").getAsString());
        ExperimentArtifactValidator.ValidationResult validated = ExperimentArtifactValidator.validate(manifestPath);
        assertEquals(0, validated.getEventCount());
        assertEquals(0L, Files.size(validated.getEvents()));
        JsonObject manifest = object(manifestPath);
        assertFalse(manifest.getAsJsonObject("configuration")
                .get("eventRecordingEnabled")
                .getAsBoolean());
        assertEquals(0, manifest.getAsJsonObject("events").get("eventCount").getAsInt());
        assertEquals(2, manifest.getAsJsonArray("artifacts").size(), "only metrics and the empty event stream");
    }

    private JsonObject base() {
        JsonObject source = configuration("workflow.dax", 2);
        algorithms(source, algorithm("fcfs", "FCFS", null), algorithm("minmin", "READY_BATCH_MINMIN", null));
        return source;
    }

    private static JsonObject withEventRecording(JsonObject source, boolean enabled) {
        source.getAsJsonObject("simulation").addProperty("eventRecordingEnabled", enabled);
        return source;
    }

    private WorkbenchConfig read(JsonObject source) throws Exception {
        return WorkbenchConfig.read(json(directory.resolve("config-" + (serial++) + ".json"), source));
    }

    private static void assertContains(Throwable failure, String expected) {
        assertTrue(
                failure.getMessage().toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT)),
                "Expected diagnostic containing '" + expected + "', got: " + failure.getMessage());
    }
}
