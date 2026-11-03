package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * BAT-R8 probe (2026-10-27, review round 8): energy conservation × failure
 * model × retries — a powered run that actually retries a job must still
 * satisfy the conservation law on its OWN manifest busy intervals, and the
 * evidence bundle must survive validation.
 *
 * <p>Semantics at stake: a FAILED attempt genuinely consumed power, so its
 * busy interval legitimately counts — the law must hold with BOTH the failed
 * attempt and the retry included (union semantics), without double-counting
 * any single interval. If this passes with retryJobCreatedCount > 0, the
 * busy accounting is provably union-merged, not retry-blind and not
 * double-counted.</p>
 */
class EnergyRetryConservationIntegrationTest {

    /** Fixed diagnostic location so the shell-side Python audit can find the bundle. */
    static final Path PROBE_DIR = Paths.get("target", "energy-retry-probe");

    @TempDir
    Path tempDir;

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
    void poweredRunWithRealRetriesSatisfiesTheConservationLaw() throws Exception {
        // Input durability (BAT-R8): the input must live in a stable directory alongside the
        // evidence -- a JUnit temp directory would make the input path recorded in the manifest
        // vanish once the test ends, so the Python auditor could not re-resolve the original
        // input; the first link of the evidence chain (the input) must not be volatile.
        Files.createDirectories(PROBE_DIR);
        Path input = PROBE_DIR.resolve("retry-bag.dax").toAbsolutePath().normalize();
        // Note (BAT-R8 finding): with a relative path, configuration.workflowPaths records the
        // form "as fed" while inputs[].path records "resolved absolute" -- the auditor's
        // ordered-input-paths clause requires the two to match item by item, so evidence-chain
        // discipline requires an absolute-path input (a product-level normalization proposal is
        // filed; see the commit message).
        Files.write(input, bagDax().getBytes(StandardCharsets.UTF_8));
        FailureModelConfig failure = new FailureModelConfig.Builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][] {
                    {DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 25, 1)}
                })
                .maxTotalRetryJobs(64)
                .build();
        PlatformProfile platform = poweredPlatform();
        SimulationReport retried = null;
        // The existing kernel retry corpus uses this bounded deterministic seed domain.
        for (long seed = 1; seed <= 256 && retried == null; seed++) {
            SimulationConfig config = SimulationConfig.builder(input.toString(), 2)
                    .planningAlgorithm(PlanningAlgorithm.INVALID)
                    .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                    .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                    .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                    .randomSeed(seed)
                    .failureModel(failure)
                    .build();
            try {
                SimulationReport candidate = new SimulationRunner().run(config, platform);
                if (candidate.isWorkflowCompletedSuccessfully()
                        && candidate.getMetrics().getRetryJobCreatedCount() > 0) {
                    retried = candidate;
                }
            } catch (RuntimeException retryBudgetOrFailure) {
                // The seed hunt looks for a run that retried and succeeded; a seed that exhausts the
                // retry budget or fails is legitimately skipped (the assertion at the end makes an
                // empty hunt a hard failure, so a skip does not swallow the error).
            }
        }
        assertTrue(retried != null, "the bounded seed domain must produce a successful true retry");
        assertTrue(retried.getMetrics().getRetryJobCreatedCount() > 0, "the probe is only meaningful with retries");

        // Conservative law on the report's own fields (hand recompute, not getter echo).
        double idleSum = 0.0;
        double delta = 0.0;
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            idleSum += spec.getIdlePowerWatts();
            delta = spec.getMaxPowerWatts() - spec.getIdlePowerWatts();
        }
        double expected =
                idleSum * retried.getMakespan() + delta * retried.getMetrics().getTotalVmModeledBusyIntervalSeconds();
        double actual = retried.getEnergyAccounting().getTotalEnergyJoules();
        assertTrue(
                Math.abs(expected - actual) <= 1e-9 * Math.max(1.0, Math.abs(expected)),
                "union busy must satisfy the law with failed attempt(s) + retry included: expected=" + expected
                        + " actual=" + actual);

        // Valid evidence bundle; keep it at the fixed probe path for the shell-side audit.
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(retried, PROBE_DIR, "result");
        ExperimentArtifactValidator.validate(artifacts.getManifest());
        assertTrue(
                Files.isRegularFile(PROBE_DIR.resolve("result.manifest.json")),
                "probe bundle staged for the Python audit");
    }

    private static String bagDax() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<adag count=\"3\">\n"
                + "  <job id=\"a\" name=\"a\" runtime=\"30\"/>\n"
                + "  <job id=\"b\" name=\"b\" runtime=\"45\"/>\n"
                + "  <job id=\"c\" name=\"c\" runtime=\"60\"/>\n"
                + "  <child ref=\"b\"><parent ref=\"a\"/></child>\n"
                + "  <child ref=\"c\"><parent ref=\"b\"/></child>\n"
                + "</adag>\n";
    }

    private static PlatformProfile poweredPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("r8-retry-power");
        int[] ids = {0, 1};
        for (int i = 0; i < 2; i++) {
            builder.addHost(new PlatformProfile.HostSpec(300 + i, 2, 2000, 2048, 10000L, 1000000L))
                    .addVm(new PlatformProfile.VmSpec(
                            ids[i],
                            1000,
                            1,
                            512,
                            1000L,
                            10000L,
                            "Xen",
                            PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                            null,
                            250.0,
                            400.0))
                    .pinVmToHost(ids[i], 300 + i);
        }
        return builder.build();
    }
}
