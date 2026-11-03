package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

    /**
     * Review round (2026-11-02): the event-sequence bound self-declared by the manifest must be
     * bound to the event stream -- a tampered lastSequence must be rejected. Previously both sides
     * only checked eventCount, and nobody verified the declared bound.
     */
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

        // Variant 1: a v1 schema (the legacy field-name contract) must be rejected, not silently
        // accepted.
        JsonObject sidecar = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getMetrics()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        sidecar.addProperty("schema", "workflowsim-simulation-metrics-v1");
        rewriteMetricsSidecar(artifacts, sidecar);
        java.io.IOException schemaException = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(schemaException.getMessage().contains("Unsupported metrics schema"));

        // Variant 2: a correct schema missing a required v2 field must be rejected.
        sidecar.addProperty("schema", "workflowsim-simulation-metrics-v2");
        sidecar.getAsJsonObject("metrics").remove("meanComputeTrueSlowdown");
        rewriteMetricsSidecar(artifacts, sidecar);
        java.io.IOException fieldException = assertThrows(
                java.io.IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(fieldException.getMessage().contains("meanComputeTrueSlowdown"));
    }

    /** Rewrite the metrics sidecar and sync the checksum and size of the matching manifest artifact. */
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

    /**
     * Rewrite the events JSONL sidecar and sync the checksum and size of the matching manifest
     * artifact, so a tampered stream reaches the deeper validation stages instead of failing the
     * reference check first.
     */
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

    /**
     * AUD-F05 (2026-11-03): the validator must recompute the registered metric families from
     * the result section instead of trusting two identical copies of the same wrong number --
     * before this, tampering manifest.metrics and the sidecar identically passed validation, so
     * a wrong value survived "mvn verify" (the audited gap). The expected value is the manifest
     * result.simulationEndSeconds: the writer's makespan, the manifest end, and the terminal
     * event clock are one and the same simulation clock.
     */
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

    /**
     * AUD-F05 (2026-11-03): second recomputed family -- the queue-wait mean is the average of
     * max(0, startTime - submissionTime) over the COMPUTE job outcomes of result.jobs, exactly
     * the population and formula the metrics writer and the Python auditor use.
     */
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
     * AUD-F05 (2026-11-03): the F-006 terminal clock identity -- on a revision stream the
     * SIMULATION_TERMINATED event time must equal the manifest end exactly; a shifted terminal
     * clock silently changes the makespan family that the stream claims to recompute.
     */
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

    /**
     * AUD-F05 (2026-11-03): the F-007 job-start derivation -- modeledJobStartSeconds must equal
     * jobEnvelopeStartSeconds + effectiveDataStageSeconds exactly; a broken derivation corrupts
     * the decision-to-start and waiting families that claim to be event-recomputable.
     */
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

    /**
     * AUD-F05 (2026-11-03): the F-007 coverage rule -- on a revision stream every executed job
     * instance must carry its JOB_EXECUTION_STARTED marker; re-pointing one marker at a phantom
     * job id leaves an executed job uncovered.
     */
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

    /**
     * AUD-F05 (2026-11-03): the recompute follows the registered v4-only boundary -- legacy
     * schema manifests keep the declared manifest authority, mirroring the Python auditor's
     * "old schemas are not silently upgraded" gate. This is a compatibility pin: the new
     * recompute must not silently change what an older manifest is allowed to declare.
     */
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

    private static String resourcePath(String resource) throws Exception {
        URL url = ExperimentArtifactValidatorTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
