package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.CondorVM;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.CrchConfig;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/**
 * CRCH end-to-end recovery with deterministic failure arrivals.
 *
 * <p>FGCS-R5 (2026-11-24): the former fixtures drew a VM 3 arrival every ~1 ms and relied on the
 * failure being flagged only at the attempt's completion. With mid-execution failures such a stream
 * ends every VM 3 attempt within a millisecond, before any checkpoint commits, so the arrivals are now
 * a Weibull stream of shape 1e300: its inverse CDF {@code scale·(-ln(1-u))^(1e-300)} rounds to exactly
 * {@code scale}, so the i-th arrival is {@code i * scale}. Timing:
 * SHARED, no input, roots start at 0.21 s; λ = 1 s, γ = 0.1 s, so checkpoint k completes k·1.1 s after
 * compute start; CRCH ties go to the lower VM ID.
 */
class CrchIntegrationTest {

    @TempDir
    Path directory;

    /**
     * Parent 2000 MI takes one checkpoint (2100 MI, 2.1 s) on VM 3 over [0.21, 2.31); the arrival 2.2
     * ends it after 1.99 s, which commits floor(1.99 / 1.1) = 1 checkpoint and saves 1000 MI. The retry
     * resumes on VM 3 (1000 MI, 1.0 s, versus 2.1 s on VM 8) over [2.2, 3.2). The child is released by the
     * successful retry and runs on VM 3 over [3.2, 4.2); the next arrival 4.4 lies outside it.
     */
    @Test
    void failedParentDoesNotReleaseChildBeforeSuccessfulRetry() throws Exception {
        Path workflow = PlanningRegressionFixtures.workflow(
                directory,
                "crch-chain.dax",
                "<job id=\"a\" name=\"a\" runtime=\"1000\"/><job id=\"b\" name=\"b\" runtime=\"2000\"/>"
                        + "<child ref=\"a\"><parent ref=\"b\"/></child>");
        java.util.List<CondorVM> vms = PlanningRegressionFixtures.vms(1000.0, 1, 1000.0, 1);
        PlatformProfile platform = PlanningRegressionFixtures.platform(vms);
        java.util.Map<Integer, DistributionSpec[]> specs = new java.util.LinkedHashMap<Integer, DistributionSpec[]>();
        specs.put(3, new DistributionSpec[] {arrivalsEvery(2.2)});
        specs.put(8, new DistributionSpec[] {arrivalsEvery(1.0e9)});
        FailureModelConfig failure = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_CRCH)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_ALL)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_VM)
                .distributionFamily(DistributionGenerator.DistributionFamily.WEIBULL)
                .generatorSpecsByVmId(specs)
                .maxTotalRetryJobs(5)
                .build();
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.CRCH)
                .failureModel(failure)
                .crchConfig(new CrchConfig(1.0, 0.1))
                .runtimeReferenceMips(1.0)
                .randomSeed(7L)
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform);
        SimulationReport.TaskOutcome parent = successfulTask(report, 2);
        SimulationReport.TaskOutcome child = successfulTask(report, 1);

        assertEquals(1, report.getFailedJobs());
        assertEquals(3, parent.getVmId());
        assertEquals(1000L, parent.getEffectiveExecutionLengthMi());
        assertEquals(3.2, parent.getFinishTime(), 1.0e-6);
        assertEquals(3.2, child.getStartTime(), 1.0e-6);
        assertEquals(4.2, report.getMakespan(), 1.0e-6);
    }

    private static SimulationReport.TaskOutcome successfulTask(SimulationReport report, int taskId) {
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            if (task.getTaskId() == taskId && task.getTaskStatus() == org.cloudbus.cloudsim.Cloudlet.SUCCESS) {
                return task;
            }
        }
        throw new AssertionError("No successful task " + taskId);
    }

    /**
     * A 1000 MI task takes no checkpoint and computes [0.21, 1.21) on VM 3; the arrival 1.1 ends it
     * before the first checkpoint, so nothing is saved and the retry costs 1.0 s on both VMs. The tie
     * goes to VM 3, [1.1, 2.1), and the next arrival 2.2 lies outside it.
     */
    @Test
    void failureCreatesARecoveryRetryAndCompletesTheWorkflow() throws Exception {
        Path workflow = PlanningRegressionFixtures.workflow(
                directory, "crch.dax", "<job id=\"a\" name=\"a\" runtime=\"1000\"/>");
        java.util.List<CondorVM> vms = PlanningRegressionFixtures.vms(1000.0, 1, 1000.0, 1);
        PlatformProfile platform = PlanningRegressionFixtures.platform(vms);
        java.util.Map<Integer, DistributionSpec[]> specs = new java.util.LinkedHashMap<Integer, DistributionSpec[]>();
        specs.put(3, new DistributionSpec[] {arrivalsEvery(1.1)});
        specs.put(8, new DistributionSpec[] {arrivalsEvery(1.0e9)});
        FailureModelConfig failure = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_CRCH)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_ALL)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_VM)
                .distributionFamily(DistributionGenerator.DistributionFamily.WEIBULL)
                .generatorSpecsByVmId(specs)
                .maxTotalRetryJobs(1)
                .build();
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.CRCH)
                .failureModel(failure)
                .crchConfig(new CrchConfig(1.0, 0.1))
                .runtimeReferenceMips(1.0)
                .randomSeed(7L)
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform);

        assertEquals(1, report.getFailedJobs());
        SimulationReport.TaskOutcome retry = successfulTask(report, 1);
        assertEquals(3, retry.getVmId());
        assertEquals(1000L, retry.getEffectiveExecutionLengthMi());
        assertEquals(1.1, retry.getStartTime(), 1.0e-6);
        assertEquals(2.1, report.getMakespan(), 1.0e-6);
    }

    private static DistributionSpec arrivalsEvery(double seconds) {
        return DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, seconds, 1.0e300);
    }
}
