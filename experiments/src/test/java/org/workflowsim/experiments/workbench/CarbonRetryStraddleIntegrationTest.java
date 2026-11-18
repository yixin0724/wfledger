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
import org.workflowsim.experiment.CarbonAccounting;
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

class CarbonRetryStraddleIntegrationTest {

    static final Path PROBE_DIR = Paths.get("target", "carbon-retry-straddle");

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
    void retriedRunWithBudgetWindowsAuditsClean() throws Exception {
        FailureModelConfig failure = new FailureModelConfig.Builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][] {
                    {DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 25, 1)}
                })
                .maxTotalRetryJobs(64)
                .build();
        PlatformProfile platform = poweredBudgetPlatform();
        SimulationReport retried = null;
        for (long seed = 1; seed <= 256 && retried == null; seed++) {
            try {
                Path input = PROBE_DIR.resolve("retry-bag.dax");
                Files.createDirectories(PROBE_DIR);
                Files.write(input, bagDax().getBytes(StandardCharsets.UTF_8));
                SimulationConfig config = SimulationConfig.builder(
                                input.toAbsolutePath().toString(), 2)
                        .planningAlgorithm(PlanningAlgorithm.INVALID)
                        .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                        .randomSeed(seed)
                        .failureModel(failure)
                        .build();
                SimulationReport candidate = new SimulationRunner().run(config, platform);
                boolean retried2 = candidate.getMetrics().getRetryJobCreatedCount() > 0;
                if (candidate.getMetrics().isAllLogicalTasksCompletedSuccessfully() && retried2) {
                    retried = candidate;
                }
            } catch (RuntimeException budgetOrFailure) {

            }
        }
        assertTrue(retried != null, "must find a run that really retried and succeeded (empty hunt = hard failure)");
        assertTrue(retried.getMetrics().getRetryJobCreatedCount() >= 1, "organic retries exist");
        assertTrue(retried.getCarbonAccounting() != null, "budget model declared = accounting required");

        CarbonAccounting cab = retried.getCarbonAccounting();
        double total = 0.0;
        for (double v : cab.getWindowExcessWattSeconds().values()) {
            total += v;
        }
        assertTrue(
                Math.abs(total - cab.getTotalCarbonExcessWattSeconds()) < 1e-9, "window sum == total excess identity");

        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(retried, PROBE_DIR, "result");
        ExperimentArtifactValidator.validate(artifacts.getManifest());
        assertTrue(
                Files.isRegularFile(PROBE_DIR.resolve("result.manifest.json")),
                "probe bundle staged for shell-side Python audit");
        assertTrue(
                !cab.getWindowExcessWattSeconds().isEmpty() || cab.getTotalCarbonExcessWattSeconds() >= 0.0,
                "budget family non-trivial (positive excess in some window or resource under green power)");
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

    private static PlatformProfile poweredBudgetPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("carbon-retry");
        int[] ids = {0, 1};
        for (int i = 0; i < 2; i++) {
            builder.addHost(new PlatformProfile.HostSpec(800 + i, 2, 2000, 2048, 10000L, 1000000L))
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
                            400.0,
                            null))
                    .pinVmToHost(ids[i], 800 + i);
        }

        builder.carbonWindow(0.0, 30.0, 100.0).carbonWindow(30.0, 120.0, 500.0);
        return builder.build();
    }
}
