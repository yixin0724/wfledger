package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

/**
 * Planner compatibility contract test for the Fat-tree x scheduling joint experiment.
 *
 * <p>The research question "how network contention changes the relative ranking of
 * scheduling algorithms" requires the same DAG to be comparable across multiple planners x
 * multiple data movement models (no contention V1 / R2 endpoint contention / R6 Fat-tree
 * link contention). This test establishes empirically which combinations are legal and
 * end-to-end healthy and which are rejected by the configuration contract -- the probe
 * results directly define the campaign comparison matrix (see
 * experiments/studies/fattree-scheduling-campaign/RETENTION.md).</p>
 *
 * <p>The fixture is identical to {@link FatTreeContentionIntegrationTest}: the HEFT paper
 * example x 3 hosts (default round-robin placement) x a full k=4 Fat-tree with 1 MB/s
 * links. RANDOM/PSO are communication-oblivious planners and serve as
 * contention-insensitive baselines; LOCAL_HEFT/LOCAL_CPOP are communication-aware
 * planners. Online schedulers (FCFS/DATA) require an INVALID planning layer and are
 * mutually exclusive with every preExecution-family model, which is a known platform
 * boundary.</p>
 */
class FatTreePlannerCompatibilityIntegrationTest {

    /** Paper cost matrix (same as FatTreeContentionIntegrationTest / LocalHeftPaperReproductionTest). */
    private static final double[][] PAPER_COST_SECONDS = {
        {14, 16, 9}, {13, 19, 18}, {11, 13, 19}, {13, 8, 17}, {12, 13, 10},
        {13, 16, 9}, {7, 15, 11}, {5, 11, 14}, {18, 12, 20}, {21, 7, 16}
    };

    /**
     * Planner set of the campaign comparison matrix: two communication-aware planners plus
     * two communication-oblivious baselines.
     */
    private static final List<Parameters.PlanningAlgorithm> CAMPAIGN_PLANNERS = Arrays.asList(
            Parameters.PlanningAlgorithm.LOCAL_HEFT,
            Parameters.PlanningAlgorithm.LOCAL_CPOP,
            Parameters.PlanningAlgorithm.RANDOM,
            Parameters.PlanningAlgorithm.PSO);

    /**
     * Data movement models of the comparison matrix: the no-contention baseline, R2 endpoint
     * contention, and R6 Fat-tree link contention.
     */
    private static final List<DataMovementModel> CAMPAIGN_MODELS = Arrays.asList(
            DataMovementModel.preExecutionTransferDelayV1(),
            DataMovementModel.preExecutionTransferDelayWithContentionV1(),
            DataMovementModel.fatTreeContentionV1());

    /**
     * Every campaign planner x model combination must be end-to-end healthy -- the legality
     * of the comparison matrix.
     */
    @Test
    void everyCampaignPlannerRunsHealthyUnderEveryMovementModel() throws Exception {
        Log.disable();
        StringBuilder table = new StringBuilder();
        for (Parameters.PlanningAlgorithm planner : CAMPAIGN_PLANNERS) {
            for (DataMovementModel movement : CAMPAIGN_MODELS) {
                boolean fatTree = movement.isFatTreeContentionV1();
                String label = planner + " × " + movement.getKind();
                SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                        .planningAlgorithm(planner)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .taskCostMatrix(paperCostMatrix())
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(movement)
                        .randomSeed(91L)
                        .build();
                double makespan;
                try {
                    SimulationReport report = new SimulationRunner()
                            .run(config, paperPlatform(fatTree ? NetworkTopologySpec.fatTree(4, 1.0) : null));
                    assertEquals("COMPLETED_SUCCESSFULLY", report.getMetrics().getLogicalTaskCompletionStatus(), label);
                    assertEquals(report.getTotalJobs(), report.getSuccessfulJobs(), label);
                    assertTrue(report.getMakespan() > 0.0 && Double.isFinite(report.getMakespan()), label);
                    makespan = report.getMakespan();
                } catch (Exception exception) {
                    table.append(label)
                            .append(" -> FAILED: ")
                            .append(exception.getMessage())
                            .append('\n');
                    System.out.println("[compatibility-probe]\n" + table);
                    throw new AssertionError(label + " failed to run: " + exception, exception);
                }
                table.append(label).append(" -> makespan ").append(makespan).append('\n');
            }
        }
        System.out.println("[compatibility-probe]\n" + table);
    }

    /**
     * Weak-monotonicity invariant of the contention semantics: as the constraint domain
     * expands step by step (endpoint -> endpoint plus path links), the makespan of the same
     * planner under the same mapping must not decrease.
     */
    @Test
    void contentionModelsNeverReduceMakespanForAnyCampaignPlanner() throws Exception {
        Log.disable();
        for (Parameters.PlanningAlgorithm planner : CAMPAIGN_PLANNERS) {
            double[] makespans = new double[CAMPAIGN_MODELS.size()];
            for (int i = 0; i < CAMPAIGN_MODELS.size(); i++) {
                DataMovementModel movement = CAMPAIGN_MODELS.get(i);
                SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                        .planningAlgorithm(planner)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .taskCostMatrix(paperCostMatrix())
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(movement)
                        .randomSeed(91L)
                        .build();
                SimulationReport report = new SimulationRunner()
                        .run(
                                config,
                                paperPlatform(
                                        movement.isFatTreeContentionV1() ? NetworkTopologySpec.fatTree(4, 1.0) : null));
                makespans[i] = report.getMakespan();
            }
            assertTrue(
                    makespans[1] >= makespans[0] - 1.0e-9,
                    planner + ": R2 endpoint contention makespan " + makespans[1] + " must not be below "
                            + makespans[0]);
            assertTrue(
                    makespans[2] >= makespans[1] - 1.0e-9,
                    planner + ": Fat-tree link contention makespan " + makespans[2]
                            + " must not be below R2 endpoint contention " + makespans[1]);
        }
    }

    /**
     * Platform boundary: the shared-storage family requires the legacy model; online
     * schedulers require an INVALID planning layer.
     */
    @Test
    void knownIncompatibleCombinationsAreRejectedUpFront() throws Exception {
        String workflow = resourcePath("/dax/heft-paper-example.dax");
        // SHARED_STORAGE family + Fat-tree model: the planning-side transfer estimate is
        // aligned with the legacy model only.
        IllegalArgumentException shared =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                        .build());
        assertNotNull(shared.getMessage());
        // Online schedulers (FCFS/DATA) require an INVALID planning layer and are mutually
        // exclusive with the preExecution family.
        for (Parameters.SchedulingAlgorithm online :
                Arrays.asList(Parameters.SchedulingAlgorithm.FCFS, Parameters.SchedulingAlgorithm.DATA)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SimulationConfig.builder(workflow, 3)
                            .schedulingAlgorithm(online)
                            .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                            .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                            .build(),
                    "online scheduler " + online + " must be rejected under the Fat-tree model"
                            + " because of the INVALID planning layer");
        }
    }

    private static String resourcePath(String name) throws Exception {
        java.net.URL url = FatTreePlannerCompatibilityIntegrationTest.class.getResource(name);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + name);
        }
        return Paths.get(url.toURI()).toString();
    }

    /**
     * Platform identical to the paper example: 3 hosts and 3 VMs (mips=1.0, bandwidth
     * 1 MB/s), with an optional topology declaration.
     */
    private static PlatformProfile paperPlatform(NetworkTopologySpec topology) {
        PlatformProfile.Builder builder = PlatformProfile.builder("compat-paper-platform");
        for (int id = 0; id < 3; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    id, 1.0, 1, 512, 1L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        if (topology != null) {
            builder.networkTopology(topology);
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
}
