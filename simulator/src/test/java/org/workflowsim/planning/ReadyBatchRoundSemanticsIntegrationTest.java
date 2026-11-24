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
