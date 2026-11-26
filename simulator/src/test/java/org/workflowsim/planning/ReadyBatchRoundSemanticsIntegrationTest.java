package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;

class ReadyBatchRoundSemanticsIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void minMinIncludesTheSharedReadTheRuntimeCharges() throws Exception {
        SimulationReport report = runReadFlip(SchedulingAlgorithm.READY_BATCH_MINMIN);
        assertEquals(0.21, start(report, 2), 1.0e-9);
        assertEquals(5.21, start(report, 1), 1.0e-9);
    }

    @Test
    void maxMinIncludesTheSharedReadTheRuntimeCharges() throws Exception {
        SimulationReport report = runReadFlip(SchedulingAlgorithm.READY_BATCH_MAXMIN);
        assertEquals(0.21, start(report, 1), 1.0e-9);
        assertEquals(11.21, start(report, 2), 1.0e-9);
    }

    @Test
    void aRoundSeesEveryJobReleasedAtTheSameInstant() throws Exception {
        Path workflow = PlanningRegressionFixtures.workflow(
                directory,
                "same-instant.dax",
                job("a", 1) + job("b", 1) + job("c", 5) + job("d", 1) + job("e", 1)
                        + "<child ref=\"d\"><parent ref=\"a\"/></child>"
                        + "<child ref=\"e\"><parent ref=\"b\"/></child>");
        SimulationReport report = run(workflow, SchedulingAlgorithm.READY_BATCH_MINMIN, 2);
        assertEquals(0.21, start(report, 1), 1.0e-9);
        assertEquals(0.21, start(report, 2), 1.0e-9);
        assertEquals(1.21, start(report, 4), 1.0e-9);
        assertEquals(1.21, start(report, 5), 1.0e-9);
        assertEquals(2.21, start(report, 3), 1.0e-9);
    }

    @Test
    void minMinUsesMachineReadyTimesOnTheBraunCase() throws Exception {
        SimulationReport report = runReadyTimeCase(SchedulingAlgorithm.READY_BATCH_MINMIN, braunCase());
        assertPlacement(report, 2, 1, 0.21);
        assertPlacement(report, 1, 1, 0.71);
        assertPlacement(report, 3, 1, 2.71);
        assertEquals(6.71, report.getMakespan(), 1.0e-9);
    }

    @Test
    void maxMinUsesTheRunningAttemptsReadyTime() throws Exception {
        SimulationReport report = runReadyTimeCase(SchedulingAlgorithm.READY_BATCH_MAXMIN, braunCase());
        assertPlacement(report, 3, 1, 0.21);
        assertPlacement(report, 2, 0, 0.21);
        assertPlacement(report, 1, 1, 4.21);
        assertEquals(6.21, report.getMakespan(), 1.0e-9);
    }

    @Test
    void mctReservesTheFastVmInsteadOfFallingBackToTheSlowIdleVm() throws Exception {
        SimulationReport report = runReadyTimeCase(SchedulingAlgorithm.READY_BATCH_MCT, job("a", 8) + job("b", 8));
        assertPlacement(report, 1, 1, 0.21);
        assertPlacement(report, 2, 1, 4.21);
        assertEquals(8.21, report.getMakespan(), 1.0e-9);
    }

    @Test
    void slowIdleVmWinsWhenTheFastVmIsBusyLongEnough() throws Exception {
        SimulationReport report = runReadyTimeCase(
                SchedulingAlgorithm.READY_BATCH_MAXMIN,
                job("a", 8) + job("b", 1) + job("c", 1) + "<child ref=\"c\"><parent ref=\"b\"/></child>");
        assertPlacement(report, 1, 1, 0.21);
        assertPlacement(report, 2, 0, 0.21);
        assertPlacement(report, 3, 0, 2.21);
        assertEquals(4.21, report.getMakespan(), 1.0e-9);
    }

    @Test
    void aJobWaitsForTheBusyFastVmWhenThatCompletesEarlier() throws Exception {
        SimulationReport report = runReadyTimeCase(
                SchedulingAlgorithm.READY_BATCH_MAXMIN,
                job("a", 8) + job("b", 1) + job("c", 4) + "<child ref=\"c\"><parent ref=\"b\"/></child>");
        assertPlacement(report, 3, 1, 4.21);
        assertEquals(6.21, report.getMakespan(), 1.0e-9);
    }

    @Test
    void anInFlightSubmissionContributesItsPredictedReadyTime() throws Exception {
        Path workflow = PlanningRegressionFixtures.workflow(
                directory,
                "in-flight.dax",
                job("p", 1) + job("r", 6) + job("k", 1) + job("m", 1) + job("a", 8)
                        + "<child ref=\"a\"><parent ref=\"p\"/></child>");
        org.workflowsim.utils.OverheadModelConfig overhead = org.workflowsim.utils.OverheadModelConfig.builder()
                .queueDelays(java.util.Collections.singletonMap(
                        2,
                        org.workflowsim.utils.DistributionSpec.of(
                                org.workflowsim.utils.DistributionGenerator.DistributionFamily.WEIBULL, 10.0, 100.0)))
                .build();
        SimulationReport report = runReadyTimeCase(SchedulingAlgorithm.READY_BATCH_MAXMIN, workflow, overhead);
        double delay = Double.NaN;
        for (org.workflowsim.experiment.SimulationEvent event : report.getEvents()) {
            if (event.getType() == org.workflowsim.experiment.SimulationEventType.JOB_DISPATCHED
                    && event.getTaskIds() != null
                    && event.getTaskIds().contains(Integer.valueOf(5))) {
                delay = ((Number) event.getAttributes().get("queueDelaySeconds")).doubleValue();
            }
        }
        assertTrue(delay > 1.0, "the submission of a must still be in flight at 4.21: D = " + delay);
        assertPlacement(report, 2, 1, 0.21);
        assertPlacement(report, 1, 0, 0.21);
        assertPlacement(report, 3, 0, 2.21);
        assertPlacement(report, 5, 1, 3.21 + delay);
        assertPlacement(report, 4, 0, 4.21);
        assertEquals(7.21 + delay, report.getMakespan(), 1.0e-9);
    }

    private static String braunCase() {
        return job("a", 4) + job("b", 1) + job("c", 8);
    }

    private SimulationReport runReadyTimeCase(SchedulingAlgorithm algorithm, String jobs) throws Exception {
        return runReadyTimeCase(
                algorithm, PlanningRegressionFixtures.workflow(directory, "ready-time.dax", jobs), null);
    }

    private SimulationReport runReadyTimeCase(
            SchedulingAlgorithm algorithm, Path workflow, org.workflowsim.utils.OverheadModelConfig overhead)
            throws Exception {
        PlatformProfile.Builder platform = PlatformProfile.builder("ready-time-rounds")
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20));
        double[] mips = {500.0, 2000.0};
        for (int id = 0; id < mips.length; id++) {
            platform.addHost(new PlatformProfile.HostSpec(id, 1, mips[id], 1024, 1000L, 1_000_000L))
                    .addVm(new PlatformProfile.VmSpec(
                            id,
                            mips[id],
                            1,
                            512,
                            1000L,
                            10_000L,
                            "Xen",
                            PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                    .pinVmToHost(id, id);
        }
        SimulationConfig.Builder config =
                SimulationConfig.builder(workflow.toString(), mips.length).schedulingAlgorithm(algorithm);
        if (overhead != null) {
            config.overheadModel(overhead);
        }
        return new SimulationRunner().run(config.build(), platform.build());
    }

    private static void assertPlacement(SimulationReport report, int taskId, int vmId, double start) {
        SimulationReport.JobOutcome job = PlanningRegressionFixtures.computeJob(report, taskId);
        assertEquals(vmId, job.getVmId(), "VM of task " + taskId);
        assertEquals(start, job.getStartTime(), 1.0e-9, "start of task " + taskId);
    }

    private SimulationReport runReadFlip(SchedulingAlgorithm algorithm) throws Exception {
        Path workflow = PlanningRegressionFixtures.workflow(
                directory,
                "read-flip.dax",
                "<job id=\"a\" name=\"a\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"200000000\"/></job>"
                        + job("b", 5));
        return run(workflow, algorithm, 1);
    }

    private static String job(String id, int runtime) {
        return "<job id=\"" + id + "\" name=\"" + id + "\" runtime=\"" + runtime + "\"/>";
    }

    private static SimulationReport run(Path workflow, SchedulingAlgorithm algorithm, int vmCount) throws Exception {
        PlatformProfile.Builder platform = PlatformProfile.builder("ready-batch-rounds")
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20));
        for (int id = 0; id < vmCount; id++) {
            platform.addHost(new PlatformProfile.HostSpec(id, 1, 1000.0, 1024, 1000L, 1_000_000L))
                    .addVm(new PlatformProfile.VmSpec(
                            id,
                            1000.0,
                            1,
                            512,
                            1000L,
                            10_000L,
                            "Xen",
                            PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), vmCount)
                .schedulingAlgorithm(algorithm)
                .build();
        return new SimulationRunner().run(config, platform.build());
    }

    private static double start(SimulationReport report, int taskId) {
        return PlanningRegressionFixtures.computeJob(report, taskId).getStartTime();
    }
}
