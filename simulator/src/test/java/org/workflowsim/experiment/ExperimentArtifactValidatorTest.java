package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class ExperimentArtifactValidatorTest {

    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void validatesGeneratedBundleAndRejectsAChangedSidecar(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .build();
        SimulationReport report =
                new SimulationRunner().run(config, PlatformProfiles.homogeneousLocal("artifact-validator", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output, "validator-run");
        String manifest = new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"eventCount\""));
        assertTrue(manifest.contains("\"study\": null"));

        ExperimentArtifactValidator.ValidationResult result =
                ExperimentArtifactValidator.validate(artifacts.getManifest());
        assertEquals(report.getEvents().size(), result.getEventCount());

        Files.write(artifacts.getEvents(), "{}\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
    }

    @Test
    void rejectsEventSequenceTamperingEvenWhenTheManifestChecksumIsRecomputed(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner()
                .run(
                        SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                                .build(),
                        PlatformProfiles.homogeneousLocal("validator-sequence", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output, "sequence-validator-run");

        Gson gson = JSON;
        java.util.List<String> lines = Files.readAllLines(artifacts.getEvents(), StandardCharsets.UTF_8);
        JsonObject firstEvent = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        firstEvent.addProperty("sequence", 99L);
        lines.set(0, gson.toJson(firstEvent));
        Files.write(artifacts.getEvents(), lines, StandardCharsets.UTF_8);

        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonArray artifactEntries = manifest.getAsJsonArray("artifacts");
        for (int index = 0; index < artifactEntries.size(); index++) {
            JsonObject entry = artifactEntries.get(index).getAsJsonObject();
            if ("events".equals(entry.get("role").getAsString())) {
                entry.addProperty("sha256", ExperimentProvenance.fingerprint(artifacts.getEvents()));
                entry.addProperty("sizeBytes", Files.size(artifacts.getEvents()));
            }
        }
        Files.write(artifacts.getManifest(), gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));

        java.io.IOException exception = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(exception.getMessage().contains("sequence mismatch"));
    }

    @Test
    void rejectsADeclaredSequenceBoundThatDoesNotMatchTheStream(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner()
                .run(
                        SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                                .build(),
                        PlatformProfiles.homogeneousLocal("validator-sequence", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output, "sequence-validator-run");

        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject summary = manifest.getAsJsonObject("events");
        long declaredLast = summary.get("lastSequence").getAsLong();
        summary.addProperty("lastSequence", declaredLast + 1);
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));

        java.io.IOException exception = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(
                exception.getMessage().contains("lastSequence"),
                "the refusal must name the declared bound: " + exception.getMessage());
    }

    @Test
    void rejectsArtifactPathsThatEscapeTheBundleDirectory(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner()
                .run(
                        SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                                .build(),
                        PlatformProfiles.homogeneousLocal("validator-path", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output, "path-validator-run");

        Gson gson = JSON;
        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        manifest.getAsJsonArray("artifacts").get(0).getAsJsonObject().addProperty("path", "../escaped.metrics.json");
        Files.write(artifacts.getManifest(), gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));

        java.io.IOException exception = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(exception.getMessage().contains("path is invalid"));
    }

    @Test
    void writesAndValidatesAnExplicitStudyIdentity(@TempDir Path output) throws Exception {
        Log.disable();
        Path datasetRoot = Files.createDirectory(output.resolve("datasets"));
        ExperimentEvidenceContext context = ExperimentEvidenceContext.builder("validator-study")
                .artifact("org.workflowsim", "validator-study", "1.0")
                .driver(ExperimentArtifactValidatorTest.class, "org/workflowsim/experiment")
                .protocol("validator-study-protocol", null)
                .datasetRoot(datasetRoot)
                .build();
        SimulationReport report = new SimulationRunner()
                .run(
                        SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                                .build(),
                        PlatformProfiles.homogeneousLocal("validator-context", 2));

        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output.resolve("bundle"), "context-run", context);
        String manifestText = new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8);
        assertTrue(manifestText.contains("workflowsim-experiment-manifest-v4"));
        assertTrue(manifestText.contains("validator-study"));
        assertEquals(
                report.getEvents().size(),
                ExperimentArtifactValidator.validate(artifacts.getManifest()).getEventCount());

        JsonObject manifest = JsonParser.parseString(manifestText).getAsJsonObject();
        manifest.getAsJsonObject("provenance")
                .getAsJsonObject("study")
                .getAsJsonObject("dataset")
                .addProperty("root", "relative-datasets");
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
    }

    @Test
    void rejectsRelativeDatasetRootInStudyIdentity() {
        assertThrows(IllegalArgumentException.class, () -> ExperimentEvidenceContext.builder("relative-root")
                .datasetRoot(Paths.get("datasets")));
    }

    @Test
    void rejectsV3ManifestWithoutAnExplicitStudyField(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner()
                .run(
                        SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                                .build(),
                        PlatformProfiles.homogeneousLocal("validator-study-shape", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output, "missing-study-run");
        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        manifest.getAsJsonObject("provenance").remove("study");
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));

        java.io.IOException exception = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(exception.getMessage().contains("explicit study field"));
    }

    @Test
    void rejectsMetricsSidecarThatDiffersFromManifestEvenWithUpdatedChecksum(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner()
                .run(
                        SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                                .build(),
                        PlatformProfiles.homogeneousLocal("validator-metrics", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output, "metrics-mismatch-run");

        JsonObject sidecar = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getMetrics()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        sidecar.getAsJsonObject("metrics").addProperty("testOnlyTamper", 1);
        Files.write(artifacts.getMetrics(), JSON.toJson(sidecar).getBytes(StandardCharsets.UTF_8));

        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (int index = 0; index < manifest.getAsJsonArray("artifacts").size(); index++) {
            JsonObject entry = manifest.getAsJsonArray("artifacts").get(index).getAsJsonObject();
            if ("metrics".equals(entry.get("role").getAsString())) {
                entry.addProperty("sha256", ExperimentProvenance.fingerprint(artifacts.getMetrics()));
                entry.addProperty("sizeBytes", Files.size(artifacts.getMetrics()));
            }
        }
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));

        java.io.IOException exception = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(exception.getMessage().contains("metrics sidecar"));
    }

    @Test
    void rejectsOutdatedMetricsSchemaAndMissingRequiredMetricFields(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner()
                .run(
                        SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                                .build(),
                        PlatformProfiles.homogeneousLocal("validator-schema-v2", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output, "schema-v2-run");

        JsonObject sidecar = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getMetrics()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        sidecar.addProperty("schema", "workflowsim-simulation-metrics-v1");
        rewriteMetricsSidecar(artifacts, sidecar);
        java.io.IOException schemaException = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(schemaException.getMessage().contains("Unsupported metrics schema"));

        sidecar.addProperty("schema", "workflowsim-simulation-metrics-v2");
        sidecar.getAsJsonObject("metrics").remove("meanComputeTrueSlowdown");
        rewriteMetricsSidecar(artifacts, sidecar);
        java.io.IOException fieldException = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(fieldException.getMessage().contains("meanComputeTrueSlowdown"));
    }

    private static void rewriteMetricsSidecar(
            ExperimentArtifactWriter.ExperimentArtifacts artifacts, JsonObject sidecar) throws Exception {
        Files.write(artifacts.getMetrics(), JSON.toJson(sidecar).getBytes(StandardCharsets.UTF_8));
        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (int index = 0; index < manifest.getAsJsonArray("artifacts").size(); index++) {
            JsonObject entry = manifest.getAsJsonArray("artifacts").get(index).getAsJsonObject();
            if ("metrics".equals(entry.get("role").getAsString())) {
                entry.addProperty("sha256", ExperimentProvenance.fingerprint(artifacts.getMetrics()));
                entry.addProperty("sizeBytes", Files.size(artifacts.getMetrics()));
            }
        }
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
    }

    private static void rewriteEventsSidecar(
            ExperimentArtifactWriter.ExperimentArtifacts artifacts, java.util.List<String> lines) throws Exception {
        Files.write(artifacts.getEvents(), lines, StandardCharsets.UTF_8);
        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (int index = 0; index < manifest.getAsJsonArray("artifacts").size(); index++) {
            JsonObject entry = manifest.getAsJsonArray("artifacts").get(index).getAsJsonObject();
            if ("events".equals(entry.get("role").getAsString())) {
                entry.addProperty("sha256", ExperimentProvenance.fingerprint(artifacts.getEvents()));
                entry.addProperty("sizeBytes", Files.size(artifacts.getEvents()));
            }
        }
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsManifestMissingARequiredTopLevelSection(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner()
                .run(
                        SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                                .build(),
                        PlatformProfiles.homogeneousLocal("validator-top-level", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output, "missing-runtime-run");
        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        manifest.remove("runtime");
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));

        java.io.IOException exception = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(exception.getMessage().contains("runtime"));
    }

    @Test
    void rejectsAMakespanThatContradictsTheResultSection(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner()
                .run(
                        SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                                .build(),
                        PlatformProfiles.homogeneousLocal("validator-recompute-makespan", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output, "recompute-makespan-run");

        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        double end =
                manifest.getAsJsonObject("result").get("simulationEndSeconds").getAsDouble();
        manifest.getAsJsonObject("metrics").addProperty("makespanSeconds", end + 1.0);
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));

        JsonObject sidecar = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getMetrics()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        sidecar.getAsJsonObject("metrics").addProperty("makespanSeconds", end + 1.0);
        rewriteMetricsSidecar(artifacts, sidecar);

        java.io.IOException exception = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(
                exception.getMessage().contains("makespanSeconds"),
                "the refusal must name the recomputed field: " + exception.getMessage());
    }

    @Test
    void rejectsAQueueWaitMeanThatContradictsTheJobOutcomes(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner()
                .run(
                        SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                                .build(),
                        PlatformProfiles.homogeneousLocal("validator-recompute-queue-wait", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output, "recompute-queue-wait-run");

        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject metrics = manifest.getAsJsonObject("metrics");
        metrics.addProperty(
                "meanJobVmQueueWaitingTimeSeconds",
                metrics.get("meanJobVmQueueWaitingTimeSeconds").getAsDouble() + 1.0);
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));

        JsonObject sidecar = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getMetrics()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject sidecarMetrics = sidecar.getAsJsonObject("metrics");
        sidecarMetrics.addProperty(
                "meanJobVmQueueWaitingTimeSeconds",
                sidecarMetrics.get("meanJobVmQueueWaitingTimeSeconds").getAsDouble() + 1.0);
        rewriteMetricsSidecar(artifacts, sidecar);

        java.io.IOException exception = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(
                exception.getMessage().contains("meanJobVmQueueWaitingTimeSeconds"),
                "the refusal must name the recomputed field: " + exception.getMessage());
    }

    /**
     * FGCS-R6 (2026-11-25): the envelope input-read total and the declared slowdown floor are bound to the
     * event stream and the configuration; a value written consistently into manifest and sidecar but
     * contradicting them is refused, naming the field.
     */
    @Test
    void rejectsR6MetricsThatContradictTheEventStreamOrConfiguration(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner()
                .run(
                        SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                                .boundedSlowdownThresholdSeconds(0.5)
                                .build(),
                        PlatformProfiles.homogeneousLocal("validator-recompute-r6", 2));
        assertEquals(0.5, report.getMetrics().getBoundedSlowdownThresholdSeconds(), 0.0);
        for (String field : new String[] {"totalInputReadSecondsInEnvelope", "boundedSlowdownThresholdSeconds"}) {
            ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                    ExperimentArtifactWriter.write(report, output.resolve(field), "recompute-r6-run");
            ExperimentArtifactValidator.validate(artifacts.getManifest());
            JsonObject manifest = JsonParser.parseString(
                            new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject metrics = manifest.getAsJsonObject("metrics");
            metrics.addProperty(field, metrics.get(field).getAsDouble() + 1.0);
            Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
            JsonObject sidecar = JsonParser.parseString(
                            new String(Files.readAllBytes(artifacts.getMetrics()), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            sidecar.getAsJsonObject("metrics")
                    .addProperty(field, metrics.get(field).getAsDouble());
            rewriteMetricsSidecar(artifacts, sidecar);

            java.io.IOException exception = assertThrows(
                    java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
            assertTrue(
                    exception.getMessage().contains(field),
                    "the refusal must name the recomputed field: " + exception.getMessage());
        }
    }

    @Test
    void rejectsATerminalClockThatContradictsTheDeclaredEnd(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner()
                .run(
                        SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                                .build(),
                        PlatformProfiles.homogeneousLocal("validator-terminal-clock", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output, "terminal-clock-run");

        java.util.List<String> lines = Files.readAllLines(artifacts.getEvents(), StandardCharsets.UTF_8);
        int terminalIndex = -1;
        for (int index = 0; index < lines.size(); index++) {
            JsonObject event = JsonParser.parseString(lines.get(index)).getAsJsonObject();
            if ("SIMULATION_TERMINATED".equals(event.get("type").getAsString())) {
                terminalIndex = index;
            }
        }
        assertTrue(terminalIndex >= 0, "a naturally completed run must terminate its event stream");
        JsonObject terminal = JsonParser.parseString(lines.get(terminalIndex)).getAsJsonObject();
        terminal.addProperty("simulationTime", terminal.get("simulationTime").getAsDouble() + 0.5);
        lines.set(terminalIndex, JSON.toJson(terminal));
        rewriteEventsSidecar(artifacts, lines);

        java.io.IOException exception = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(
                exception.getMessage().contains("Terminal"),
                "the refusal must name the terminal clock identity: " + exception.getMessage());
    }

    @Test
    void rejectsAStartedEventWithABrokenDerivation(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner()
                .run(
                        SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                                .build(),
                        PlatformProfiles.homogeneousLocal("validator-start-derivation", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output, "start-derivation-run");

        java.util.List<String> lines = Files.readAllLines(artifacts.getEvents(), StandardCharsets.UTF_8);
        boolean tampered = false;
        for (int index = 0; index < lines.size() && !tampered; index++) {
            JsonObject event = JsonParser.parseString(lines.get(index)).getAsJsonObject();
            if ("JOB_EXECUTION_STARTED".equals(event.get("type").getAsString())) {
                JsonObject attributes = event.getAsJsonObject("attributes");
                attributes.addProperty(
                        "modeledJobStartSeconds",
                        attributes.get("modeledJobStartSeconds").getAsDouble() + 1.0);
                lines.set(index, JSON.toJson(event));
                tampered = true;
            }
        }
        assertTrue(tampered, "the fixture run must record at least one started event");
        rewriteEventsSidecar(artifacts, lines);

        java.io.IOException exception = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(
                exception.getMessage().contains("derivation"),
                "the refusal must name the job-start derivation: " + exception.getMessage());
    }

    @Test
    void rejectsAnExecutedJobWithoutAStartedMarker(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner()
                .run(
                        SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                                .build(),
                        PlatformProfiles.homogeneousLocal("validator-start-coverage", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output, "start-coverage-run");

        java.util.List<String> lines = Files.readAllLines(artifacts.getEvents(), StandardCharsets.UTF_8);
        java.util.Set<Integer> executedJobIds = new java.util.HashSet<>();
        for (String line : lines) {
            JsonObject event = JsonParser.parseString(line).getAsJsonObject();
            if ("TASK_EXECUTION_MODELED".equals(event.get("type").getAsString())) {
                executedJobIds.add(event.get("jobId").getAsInt());
            }
        }
        boolean tampered = false;
        for (int index = 0; index < lines.size() && !tampered; index++) {
            JsonObject event = JsonParser.parseString(lines.get(index)).getAsJsonObject();
            if ("JOB_EXECUTION_STARTED".equals(event.get("type").getAsString())
                    && executedJobIds.contains(event.get("jobId").getAsInt())) {
                event.addProperty("jobId", 999999);
                lines.set(index, JSON.toJson(event));
                tampered = true;
            }
        }
        assertTrue(tampered, "the fixture run must start at least one executed job");
        rewriteEventsSidecar(artifacts, lines);

        java.io.IOException exception = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(
                exception.getMessage().contains("coverage"),
                "the refusal must name the job-start coverage: " + exception.getMessage());
    }

    @Test
    void keepsDeclaredAuthorityForLegacyManifestSchemas(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner()
                .run(
                        SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                                .build(),
                        PlatformProfiles.homogeneousLocal("validator-legacy-schema", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output, "legacy-schema-run");

        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        double end =
                manifest.getAsJsonObject("result").get("simulationEndSeconds").getAsDouble();
        manifest.addProperty("schema", ExperimentArtifactValidator.MANIFEST_SCHEMA_V3);
        manifest.getAsJsonObject("metrics").addProperty("makespanSeconds", end + 1.0);
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));

        JsonObject sidecar = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getMetrics()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        sidecar.getAsJsonObject("metrics").addProperty("makespanSeconds", end + 1.0);
        rewriteMetricsSidecar(artifacts, sidecar);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
    }

    @Test
    void rejectsADeclaredRevisionContractWithoutItsTerminalEvent(@TempDir Path output) throws Exception {
        ExperimentArtifactWriter.ExperimentArtifacts artifacts = writeBundle(output, "revision-terminal");
        JsonObject manifest = readManifest(artifacts.getManifest());
        rewriteEvents(artifacts, manifest, event -> "SIMULATION_TERMINATED"
                .equals(event.get("type").getAsString()));

        java.io.IOException exception = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(
                exception.getMessage().contains("revisionContract"),
                "the refusal must name the declared contract: " + exception.getMessage());
        assertTrue(
                exception.getMessage().contains("SIMULATION_TERMINATED"),
                "the refusal must name the missing marker: " + exception.getMessage());
    }

    @Test
    void rejectsAStreamThatRemovesEveryMarkerUnderItsDeclaredContract(@TempDir Path output) throws Exception {
        ExperimentArtifactWriter.ExperimentArtifacts artifacts = writeBundle(output, "revision-markers");
        JsonObject manifest = readManifest(artifacts.getManifest());
        rewriteEvents(
                artifacts,
                manifest,
                event -> "SIMULATION_TERMINATED".equals(event.get("type").getAsString())
                        || "JOB_EXECUTION_STARTED".equals(event.get("type").getAsString()));

        java.io.IOException exception = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(
                exception.getMessage().contains("revisionContract"),
                "the refusal must name the declared contract: " + exception.getMessage());
    }

    @Test
    void rejectsAnUnknownDeclaredRevisionContract(@TempDir Path output) throws Exception {
        ExperimentArtifactWriter.ExperimentArtifacts artifacts = writeBundle(output, "revision-unknown");
        JsonObject manifest = readManifest(artifacts.getManifest());
        manifest.getAsJsonObject("events").addProperty("revisionContract", "v0");
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));

        java.io.IOException exception = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(
                exception.getMessage().contains("Unsupported events revisionContract"),
                "the refusal must name the unsupported contract: " + exception.getMessage());
    }

    @Test
    void keepsTheManifestAuthorityBoundaryForAStreamWithoutTheDeclaration(@TempDir Path output) throws Exception {
        ExperimentArtifactWriter.ExperimentArtifacts artifacts = writeBundle(output, "revision-legacy");
        JsonObject manifest = readManifest(artifacts.getManifest());
        manifest.getAsJsonObject("events").remove("revisionContract");
        rewriteEvents(
                artifacts,
                manifest,
                event -> "SIMULATION_TERMINATED".equals(event.get("type").getAsString())
                        || "JOB_EXECUTION_STARTED".equals(event.get("type").getAsString()));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
    }

    private static ExperimentArtifactWriter.ExperimentArtifacts writeBundle(Path output, String runId)
            throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner()
                .run(
                        SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                                .build(),
                        PlatformProfiles.homogeneousLocal(runId, 2));
        return ExperimentArtifactWriter.write(report, output, runId);
    }

    private static JsonObject readManifest(Path manifest) throws Exception {
        return JsonParser.parseString(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static void rewriteEvents(
            ExperimentArtifactWriter.ExperimentArtifacts artifacts, JsonObject manifest, Predicate<JsonObject> drop)
            throws Exception {
        List<String> kept = new ArrayList<>();
        long sequence = 0L;
        for (String line : Files.readAllLines(artifacts.getEvents(), StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            JsonObject event = JsonParser.parseString(line).getAsJsonObject();
            if (drop.test(event)) {
                continue;
            }
            event.addProperty("sequence", sequence++);
            kept.add(JSON.toJson(event));
        }
        Files.write(artifacts.getEvents(), kept, StandardCharsets.UTF_8);
        JsonObject summary = manifest.getAsJsonObject("events");
        summary.addProperty("eventCount", kept.size());
        summary.addProperty("lastSequence", kept.size() - 1L);
        for (JsonElement element : manifest.getAsJsonArray("artifacts")) {
            JsonObject entry = element.getAsJsonObject();
            if ("events".equals(entry.get("role").getAsString())) {
                entry.addProperty("sha256", ExperimentProvenance.fingerprint(artifacts.getEvents()));
                entry.addProperty("sizeBytes", Files.size(artifacts.getEvents()));
            }
        }
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
    }

    private static String resourcePath(String resource) throws Exception {
        URL url = ExperimentArtifactValidatorTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
