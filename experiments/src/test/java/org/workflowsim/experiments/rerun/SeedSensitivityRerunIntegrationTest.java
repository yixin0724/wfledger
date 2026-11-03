package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Review round (2026-11-02): the rerun contract under a randomness-sensitive workload.
 *
 * <p>An earlier rerun fuzz test observed that "tampering with rootSeed still yields
 * IDENTICAL_CORE", which was briefly suspected to be a defect; measurement showed that the
 * workload was <b>insensitive to randomness</b>: the rerun faithfully reproduced the tampered
 * record conditions, so both sides carried the same seed. This test pins that property down
 * with a workload of stochastic failure retries:</p>
 *
 * <ol>
 *   <li>same seed rerun -> IDENTICAL_CORE (the record conditions are reproducible);</li>
 *   <li>tampered rootSeed -> DIVERGED (the seed is a scientific condition, and a workload
 *       sensitive to it must diverge).</li>
 * </ol>
 *
 * <p>Expected-value provenance: the core comparison domain of RERUN_DIFF_CONTRACT.md
 * (configuration is inside the domain; only algorithmContract and workflowPaths are
 * whitelisted).</p>
 */
class SeedSensitivityRerunIntegrationTest {

    @TempDir
    Path directory;

    private boolean logWasDisabled;

    @BeforeEach
    void quiet() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restore() {
        Log.setDisabled(logWasDisabled);
    }

    @Test
    void aStochasticWorkloadRerunsIdenticallyAndSeedTamperingDiverges() throws Exception {
        Path study = directory.resolve("seed");
        Path run = writeStochasticRun(study, 20261107L);

        RerunReport sameSeed = RerunDiffExecutor.execute(run, study.resolve("out-same"));
        assertEquals(RerunVerdict.IDENTICAL_CORE, sameSeed.getVerdict(), sameSeed.toJson());

        Path tampered = study.resolve("tampered");
        copyDirectory(run, tampered);
        bumpSeed(tampered);
        RerunReport changedSeed = RerunDiffExecutor.execute(tampered, study.resolve("out-changed"));
        assertEquals(
                RerunVerdict.DIVERGED,
                changedSeed.getVerdict(),
                "a stochastic workload must diverge when the recorded seed changes: " + changedSeed.toJson());
        // Divergence lands on the result face, not the configuration face: the rebuild starts
        // from the (tampered) record conditions, so both sides carry the same configuration,
        // but a different seed changes the course of stochastic failures and retries, so the
        // result face must go red.
        assertFalse(
                changedSeed.getCoreDivergences().isEmpty(),
                "a changed seed must produce core divergences in the result face: " + pointers(changedSeed));
        assertTrue(
                changedSeed.getCoreDivergences().stream()
                        .anyMatch(entry -> entry.getPointer().startsWith("/result")
                                || entry.getPointer().startsWith("/metrics")),
                "the divergence must land on recomputed results: " + pointers(changedSeed));
    }

    /**
     * Stochastic failure-retry workload: FAILURE_JOB plus a Weibull distribution; the seed
     * decides which jobs fail and when they retry.
     */
    private Path writeStochasticRun(Path study, long seed) throws Exception {
        Path workflow = study.resolve("inputs/seed.dax");
        Files.createDirectories(workflow.getParent());
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"4\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"30\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"20\"/>"
                                + "<job id=\"c\" name=\"c\" runtime=\"15\"/>"
                                + "<job id=\"d\" name=\"d\" runtime=\"25\"/>"
                                + "<child ref=\"b\"><parent ref=\"a\"/></child>"
                                + "<child ref=\"c\"><parent ref=\"b\"/></child>"
                                + "<child ref=\"d\"><parent ref=\"c\"/></child></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        DistributionSpec perDepth = DistributionSpec.of(DistributionFamily.WEIBULL, 20.0, 1.0);
        DistributionSpec[] byDepth = new DistributionSpec[8];
        Arrays.fill(byDepth, perDepth);
        FailureModelConfig failure = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_JOB)
                .generatorSpecs(new DistributionSpec[][] {byDepth})
                .maxTotalRetryJobs(200)
                .build();
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .failureModel(failure)
                .randomSeed(seed)
                .build();
        SimulationReport report = new SimulationRunner().run(config, PlatformProfiles.homogeneousLocal("seed", 2));
        Path run = study.resolve("runs/seed-" + seed);
        ExperimentArtifactWriter.write(report, run, "result");
        return run;
    }

    private static void bumpSeed(Path run) throws Exception {
        Path manifest = run.resolve("result.manifest.json");
        JsonObject doc = JsonParser.parseString(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject configuration = doc.getAsJsonObject("configuration");
        configuration.addProperty("rootSeed", configuration.get("rootSeed").getAsLong() + 1L);
        Files.write(manifest, doc.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void copyDirectory(Path source, Path target) throws Exception {
        Files.walk(source).forEach(path -> {
            try {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static String pointers(RerunReport report) {
        StringBuilder builder = new StringBuilder();
        for (RerunReport.DivergenceEntry entry : report.getCoreDivergences()) {
            builder.append(entry.getPointer()).append(' ');
        }
        return builder.toString();
    }
}
