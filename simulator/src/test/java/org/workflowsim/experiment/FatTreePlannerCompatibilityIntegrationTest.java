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

class FatTreePlannerCompatibilityIntegrationTest {

    private static final double[][] PAPER_COST_SECONDS = {
        {14, 16, 9}, {13, 19, 18}, {11, 13, 19}, {13, 8, 17}, {12, 13, 10},
        {13, 16, 9}, {7, 15, 11}, {5, 11, 14}, {18, 12, 20}, {21, 7, 16}
    };

    private static final List<Parameters.PlanningAlgorithm> CAMPAIGN_PLANNERS = Arrays.asList(
            Parameters.PlanningAlgorithm.LOCAL_HEFT,
            Parameters.PlanningAlgorithm.LOCAL_CPOP,
            Parameters.PlanningAlgorithm.RANDOM,
            Parameters.PlanningAlgorithm.PSO);

    private static final List<DataMovementModel> CAMPAIGN_MODELS = Arrays.asList(
            DataMovementModel.preExecutionTransferDelayV1(),
            DataMovementModel.preExecutionTransferDelayWithContentionV1(),
            DataMovementModel.fatTreeContentionV1());

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

    @Test
    void knownIncompatibleCombinationsAreRejectedUpFront() throws Exception {
        String workflow = resourcePath("/dax/heft-paper-example.dax");

        IllegalArgumentException shared =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                        .build());
        assertNotNull(shared.getMessage());

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
