package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static String braunCase() {
        return job("a", 4) + job("b", 1) + job("c", 8);
    }

    private SimulationReport runReadyTimeCase(SchedulingAlgorithm algorithm, String jobs) throws Exception {
        Path workflow = PlanningRegressionFixtures.workflow(directory, "ready-time.dax", jobs);
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
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), mips.length)
                .schedulingAlgorithm(algorithm)
                .build();
        return new SimulationRunner().run(config, platform.build());
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
