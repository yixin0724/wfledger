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

/** FGCS-R3 (2026-11-24): hand-derived cost and batch semantics of the online ready-batch policies. */
class ReadyBatchRoundSemanticsIntegrationTest {

    @TempDir
    Path directory;

    /**
     * B1: task 1 has 1000 MI (1 s) plus a 200 MB SHARED read at 20 MB/s (10 s), hence 11 s; task 2
     * has 5000 MI and no input, hence 5 s. On one VM Min-Min must run task 2 first. The former
     * length/MIPS estimate (1 s versus 5 s) ran task 1 first.
     */
    @Test
    void minMinIncludesTheSharedReadTheRuntimeCharges() throws Exception {
        SimulationReport report = runReadFlip(SchedulingAlgorithm.READY_BATCH_MINMIN);
        assertEquals(0.21, start(report, 2), 1.0e-9);
        assertEquals(5.21, start(report, 1), 1.0e-9);
    }

    /** B1: with the durations above Max-Min must run the 11 s task 1 first. */
    @Test
    void maxMinIncludesTheSharedReadTheRuntimeCharges() throws Exception {
        SimulationReport report = runReadFlip(SchedulingAlgorithm.READY_BATCH_MAXMIN);
        assertEquals(0.21, start(report, 1), 1.0e-9);
        assertEquals(11.21, start(report, 2), 1.0e-9);
    }

    /**
     * B2: at 0.21 s Min-Min starts the 1 s parents 1 and 2 on the two VMs; the 5 s task 3 stays
     * ready. Both parents return at 1.21 s and release the 1 s children 4 and 5 at that instant.
     * The batch at 1.21 s is {3, 4, 5}, so Min-Min starts 4 and 5; task 3 starts at 2.21 s. The
     * former return-triggered round saw only task 3 and started it at 1.21 s.
     */
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

    /**
     * FGCS-R6 (2026-11-25): Braun et al. (2001) / Maheswaran et al. (1999) Min-Min with machine
     * ready times. VM 0 has 500 MIPS and VM 1 2000 MIPS; roots 1, 2, 3 have 4000, 1000 and 8000 MI
     * (runtime 4, 1, 8 s at the 1000-MIPS reference) and no files. The 110-MI stage-in Job runs on
     * VM 1 (0.055 s, raised by the completion guard to 0.1 + 0.11 = 0.21 s after the 0.1 s VM ACK),
     * so the round sees {1, 2, 3} at 0.21 s with both VMs idle. ETC on (VM 0, VM 1): task 1 (8, 2),
     * task 2 (2, 0.5), task 3 (16, 4). Min-Min: 2 to VM 1 (CT 0.5, r1 = 0.5), then 1 to VM 1
     * (CT 2.5 versus 8, r1 = 2.5), then 3 to VM 1 (CT 6.5 versus 16). Only task 2 is the first plan
     * on an idle VM, so it alone starts at 0.21; at 0.71 the re-plan starts 1 on VM 1 (to 2.71) and at
     * 2.71 task 3 on VM 1 (4 s versus 16 s), ending at 6.71. The idle-VM-only V2 rule put task 1 on
     * VM 0 at 0.21 and ended at 8.21.
     */
    @Test
    void minMinUsesMachineReadyTimesOnTheBraunCase() throws Exception {
        SimulationReport report = runReadyTimeCase(SchedulingAlgorithm.READY_BATCH_MINMIN, braunCase());
        assertPlacement(report, 2, 1, 0.21);
        assertPlacement(report, 1, 1, 0.71);
        assertPlacement(report, 3, 1, 2.71);
        assertEquals(6.71, report.getMakespan(), 1.0e-9);
    }

    /**
     * FGCS-R6 (2026-11-25): Max-Min on the same case. Minima are 2, 0.5 and 4 (all VM 1); the maximum
     * assigns 3 to VM 1 (r1 = 4). Then task 1: min(8, 6) = 6 on VM 1, task 2: min(2, 4.5) = 2 on VM 0;
     * the maximum assigns 1 to VM 1 (r1 = 6) and finally 2 to VM 0 (2 versus 6.5). Tasks 3 (VM 1) and
     * 2 (VM 0) are first plans on idle VMs and start at 0.21; task 1 waits. At 2.21 task 2 returns:
     * VM 1 still runs task 3, whose guarded completion is 0.21 + 4 = 4.21, so r1 = 2 and task 1 picks
     * VM 1 (2 + 2 = 4 versus 8 on VM 0) and stays queued. At 4.21 it starts on VM 1 and ends at 6.21.
     */
    @Test
    void maxMinUsesTheRunningAttemptsReadyTime() throws Exception {
        SimulationReport report = runReadyTimeCase(SchedulingAlgorithm.READY_BATCH_MAXMIN, braunCase());
        assertPlacement(report, 3, 1, 0.21);
        assertPlacement(report, 2, 0, 0.21);
        assertPlacement(report, 1, 1, 4.21);
        assertEquals(6.21, report.getMakespan(), 1.0e-9);
    }

    /**
     * FGCS-R6 (2026-11-25): Maheswaran et al. (1999) immediate-mode MCT. Roots 1 and 2 have 8000 MI
     * each (16 s on VM 0, 4 s on VM 1). In queue order task 1 picks VM 1 (CT 4, r1 = 4), task 2 picks
     * VM 1 (4 + 4 = 8 versus 16) and is not dispatched because VM 1 is already planned. Task 1 runs
     * [0.21, 4.21]; at 4.21 task 2 picks VM 1 again (4 versus 16) and ends at 8.21. The idle-VM-only V2
     * rule started task 2 on VM 0 at 0.21 and ended at 16.21.
     */
    @Test
    void mctReservesTheFastVmInsteadOfFallingBackToTheSlowIdleVm() throws Exception {
        SimulationReport report = runReadyTimeCase(SchedulingAlgorithm.READY_BATCH_MCT, job("a", 8) + job("b", 8));
        assertPlacement(report, 1, 1, 0.21);
        assertPlacement(report, 2, 1, 4.21);
        assertEquals(8.21, report.getMakespan(), 1.0e-9);
    }

    /**
     * FGCS-R6 (2026-11-25): a slow idle VM is used when the fast VM's ready time is large. Max-Min at
     * 0.21 maps root 1 (8000 MI) to VM 1 [0.21, 4.21] and root 2 (1000 MI, 2 s on VM 0 versus
     * 4 + 0.5 on VM 1) to VM 0 [0.21, 2.21]. At 2.21 the child 3 of 2 (1000 MI) sees r1 = 4.21 - 2.21
     * = 2: VM 0 gives CT 2, VM 1 gives 2 + 0.5 = 2.5, so 3 starts on the slow idle VM 0 at 2.21.
     */
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

    /**
     * FGCS-R6 (2026-11-25): as above but the child 3 has 4000 MI. At 2.21 VM 0 gives CT 8 and VM 1
     * gives 2 + 2 = 4, so 3 waits for the busy fast VM (giving up work conservation, as the literature
     * heuristic does) and runs [4.21, 6.21] on VM 1 while VM 0 stays idle.
     */
    @Test
    void aJobWaitsForTheBusyFastVmWhenThatCompletesEarlier() throws Exception {
        SimulationReport report = runReadyTimeCase(
                SchedulingAlgorithm.READY_BATCH_MAXMIN,
                job("a", 8) + job("b", 1) + job("c", 4) + "<child ref=\"c\"><parent ref=\"b\"/></child>");
        assertPlacement(report, 3, 1, 4.21);
        assertEquals(6.21, report.getMakespan(), 1.0e-9);
    }

    /**
     * FGCS-R6 (2026-11-25): ready time of a VM whose submission is still in flight. Only depth-2 Jobs
     * carry a queue delay {@code D} (Weibull, scale 10, shape 100, so {@code D} lies near 10 s; it is a
     * sampled input read back from the JOB_DISPATCHED event). Roots p, r, k, m have 1000, 6000, 1000 and
     * 1000 MI (ETC on (VM 0, VM 1): p, k, m (2, 0.5), r (12, 3)); a (8000 MI, ETC (16, 4)) is the
     * depth-2 child of p. Max-Min at 0.21: minima 0.5, 3, 0.5, 0.5, so r to VM 1 (r1 = 3); then p, k, m
     * all give min(2, 3.5) = 2 on VM 0 and the lower Job ID p is assigned (r0 = 2); k then gives
     * min(4, 3.5) on VM 1 (r1 = 3.5) and m min(4, 4) ties to VM 0. Dispatched: r on VM 1, p on VM 0.
     * At 2.21 p returns (r1 = 1): a gives min(16, 5) = 5 and is assigned first (r1 = 5), then k
     * (lower ID than m) gives min(2, 5.5) on VM 0, then m min(4, 5.5) on VM 0; k starts on VM 0. At
     * 3.21 r returns (r0 = 1): a gives min(17, 4) = 4 on VM 1 and is dispatched with delay {@code D};
     * m gives min(3, 4.5) on the busy VM 0. At 4.21 k returns while a has not reached the datacenter
     * ({@code 3.21 + D > 4.21}): the ready time of VM 1 is the in-flight estimate
     * {@code (3.21 + D + 4) - 4.21 = D + 3}, so m compares 2 on VM 0 with {@code D + 3.5} and starts
     * on VM 0 at 4.21 (ending 6.21). Treating VM 1 as ready now would compare 2 with 0.5 and keep m
     * queued until a returns. a starts at {@code 3.21 + D} and the makespan is {@code 7.21 + D}.
     */
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
