package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationRandom;
import org.workflowsim.utils.TaskCostMatrix;

/**
 * Planner directionality contract (R8 audit, algorithm channel P2-1).
 *
 * <p>Every earlier planner test pinned only golden values or bitwise determinism, and no
 * test locked the direction of a reproduced algorithm relative to an information-free
 * baseline: if the HEFT/CPOP implementation were broken until it did worse than RANDOM,
 * the golden-value tests would fail, but the failure message could not point at the root
 * cause "the algorithm lost its heuristic advantage". This test locks that direction
 * directly on the HEFT paper-example fixture (exactly the same rig as
 * {@link LocalHeftPaperReproductionTest}):</p>
 * <ul>
 *   <li>LOCAL_HEFT makespan &lt;= RANDOM makespan (same seed);</li>
 *   <li>LOCAL_CPOP makespan &lt;= RANDOM makespan (same seed);</li>
 *   <li>the LOCAL_HEFT golden value 190.1 (paper 80 + guided offset 110.1) stays bitwise
 *   unchanged.</li>
 * </ul>
 *
 * <p>The directionality assertion is a weak order (&lt;=) rather than a strict one
 * (&lt;): RANDOM has a small probability of landing on the same mapping as the heuristic;
 * under a fixed seed the assertion is still a deterministic measured fact (the RANDOM
 * golden value is recorded in the failure message as well, for drift triage).</p>
 */
class PlannerDirectionalityIntegrationTest {

    /** LOCAL_HEFT paper-example golden makespan (V1 model, including the guided offset). */
    private static final double HEFT_GOLDEN_MAKESPAN = 190.1;

    /** Paper cost matrix (same as LocalHeftPaperReproductionTest). */
    private static final double[][] PAPER_COST_SECONDS = {
        {14, 16, 9}, {13, 19, 18}, {11, 13, 19}, {13, 8, 17}, {12, 13, 10},
        {13, 16, 9}, {7, 15, 11}, {5, 11, 14}, {18, 12, 20}, {21, 7, 16}
    };

    @Test
    void heftAndCpopAreNeverWorseThanSameSeedRandom() throws Exception {
        double randomMakespan = runWithPlanner(Parameters.PlanningAlgorithm.RANDOM);
        double heftMakespan = runWithPlanner(Parameters.PlanningAlgorithm.LOCAL_HEFT);
        double cpopMakespan = runWithPlanner(Parameters.PlanningAlgorithm.LOCAL_CPOP);

        assertEquals(
                HEFT_GOLDEN_MAKESPAN,
                heftMakespan,
                0.0,
                "LOCAL_HEFT golden value drift: the precondition rig of the directionality test has changed");
        assertTrue(
                heftMakespan <= randomMakespan,
                "LOCAL_HEFT makespan " + heftMakespan + " must not be worse than same-seed RANDOM " + randomMakespan
                        + " (heuristic lost its advantage = directionality regression)");
        assertTrue(
                cpopMakespan <= randomMakespan,
                "LOCAL_CPOP makespan " + cpopMakespan + " must not be worse than same-seed RANDOM " + randomMakespan
                        + " (heuristic lost its advantage = directionality regression)");
    }

    private static double runWithPlanner(Parameters.PlanningAlgorithm planner) throws Exception {
        Log.disable();
        SimulationRandom.reset(91L);
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                .planningAlgorithm(planner)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();
        SimulationReport report = new SimulationRunner().run(config, paperPlatform());
        int computeJobs = 0;
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == Parameters.ClassType.COMPUTE.value) {
                computeJobs++;
            }
        }
        assertEquals(10, computeJobs, planner + ": the paper example must execute all 10 COMPUTE Jobs");
        return report.getMakespan();
    }

    private static PlatformProfile paperPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("directionality-platform");
        for (int id = 0; id < 3; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    id, 1.0, 1, 512, 1L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        return builder.build();
    }

    private static TaskCostMatrix paperCostMatrix() {
        TaskCostMatrix.Builder builder = TaskCostMatrix.builder();
        for (int taskId = 1; taskId <= PAPER_COST_SECONDS.length; taskId++) {
            for (int vmId = 0; vmId < 3; vmId++) {
                builder.put(taskId, vmId, PAPER_COST_SECONDS[taskId - 1][vmId]);
            }
        }
        return builder.build();
    }

    private static String resourcePath(String resource) throws Exception {
        java.net.URL url = PlannerDirectionalityIntegrationTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
