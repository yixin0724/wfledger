package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

class FatTreeContentionIntegrationTest {

    private static final double NO_CONTENTION_BASELINE = 190.1;

    private static final double R2_ENDPOINT_GOLDEN = 284.1;

    private static final double FAT_TREE_GOLDEN_MAKESPAN = 284.1;

    private static final double SLOW_LINK_BANDWIDTH_MB = 0.25;

    private static final double SLOW_LINK_GOLDEN_MAKESPAN = 588.1;

    private static final double[][] PAPER_COST_SECONDS = {
        {14, 16, 9}, {13, 19, 18}, {11, 13, 19}, {13, 8, 17}, {12, 13, 10},
        {13, 16, 9}, {7, 15, 11}, {5, 11, 14}, {18, 12, 20}, {21, 7, 16}
    };

    @Test
    void fatTreeMakespanExceedsNoContentionBaselineAndR2EndpointGolden() throws Exception {
        SimulationReport report = runFatTree(1.0);
        assertTrue(
                report.getMakespan() > NO_CONTENTION_BASELINE,
                "Fat-tree makespan " + report.getMakespan()
                        + " must be strictly greater than the no-contention baseline " + NO_CONTENTION_BASELINE);
        assertTrue(
                report.getMakespan() >= R2_ENDPOINT_GOLDEN,
                "Fat-tree makespan " + report.getMakespan()
                        + " must be at least the R2 endpoint contention golden value " + R2_ENDPOINT_GOLDEN
                        + " (superset constraint: the minimum of endpoint and path link)");
    }

    @Test
    void fatTreeGoldenMakespanIsLocked() throws Exception {
        SimulationReport report = runFatTree(1.0);
        assertEquals(
                FAT_TREE_GOLDEN_MAKESPAN,
                report.getMakespan(),
                0.0,
                "Fat-tree golden value drifted: the "
                        + "fixture, topology, unit contract, or contention semantics changed and require"
                        + " explicit review");

        assertEquals(
                R2_ENDPOINT_GOLDEN,
                report.getMakespan(),
                0.0,
                "with link bandwidth equal to endpoint"
                        + " bandwidth, R6 must be bit-identical to R2 endpoint contention");
    }

    @Test
    void slowLinksSlowTransfersBeyondEndpointContention() throws Exception {
        SimulationReport report = runFatTree(SLOW_LINK_BANDWIDTH_MB);
        assertTrue(
                report.getMakespan() > R2_ENDPOINT_GOLDEN,
                "slow-link makespan " + report.getMakespan() + " must be strictly greater than R2 endpoint"
                        + " contention " + R2_ENDPOINT_GOLDEN + " (the link is the real bottleneck)");
        assertEquals(
                SLOW_LINK_GOLDEN_MAKESPAN,
                report.getMakespan(),
                0.0,
                "slow-link golden value drifted:"
                        + " the topology, unit contract, or contention semantics changed and require explicit review");
    }

    @Test
    void fatTreeScheduleIsDeterministic() throws Exception {
        SimulationReport first = runFatTree(1.0);
        SimulationReport second = runFatTree(1.0);
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0);
        for (int taskId = 1; taskId <= 10; taskId++) {
            assertEquals(
                    computeFinish(first, taskId),
                    computeFinish(second, taskId),
                    0.0,
                    "task " + taskId + " finish time must be bit-identical across reruns");
        }
    }

    @Test
    void fatTreeEvidenceIsRecordedOnStageInEvents() throws Exception {
        SimulationReport report = runFatTree(1.0);
        int observed = 0;
        int totalPathLinks = 0;
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() != SimulationEventType.DATA_STAGE_IN_MODELED) {
                continue;
            }
            observed++;
            assertEquals(
                    "PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1",
                    event.getAttributes().get("dataMovementModel"));
            Object linkCount = event.getAttributes().get("fatTreePathLinkCount");
            assertNotNull(linkCount, "Fat-tree evidence must carry the path link count");
            totalPathLinks += ((Number) linkCount).intValue();
        }
        assertEquals(10, observed);
        assertTrue(
                totalPathLinks > 0,
                "VM->VM transfer groups must be routed over topology links, so the"
                        + " total link count must be greater than 0");
    }

    @Test
    void fatTreeRunIsHealthy() throws Exception {
        SimulationReport report = runFatTree(1.0);
        assertEquals("COMPLETED_SUCCESSFULLY", report.getMetrics().getLogicalTaskCompletionStatus());
        assertTrue(report.getMakespan() > 0.0 && Double.isFinite(report.getMakespan()));
        assertEquals(report.getTotalJobs(), report.getSuccessfulJobs());
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully());
    }

    @Test
    void configurationContractRejectsInvalidCombinations() throws Exception {
        String workflow = resourcePath("/dax/heft-paper-example.dax");

        IllegalArgumentException shared =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .taskCostMatrix(paperCostMatrix())
                        .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                        .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                        .build());
        assertTrue(shared.getMessage().contains("LOCAL"), shared.getMessage());

        IllegalArgumentException unmapped =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(workflow, 3)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                        .build());
        assertTrue(unmapped.getMessage().contains("planning algorithm"), unmapped.getMessage());

        Map<Integer, DistributionSpec[]> failureRows = new LinkedHashMap<Integer, DistributionSpec[]>();
        for (int vmId = 0; vmId < 3; vmId++) {
            failureRows.put(Integer.valueOf(vmId), new DistributionSpec[] {
                DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 1.0e9, 1.0)
            });
        }
        FailureModelConfig enabledFailure = FailureModelConfig.builder()
                .generatorMode(FailureParameters.FTCFailure.FAILURE_VM)
                .generatorSpecsByVmId(failureRows)
                .maxTotalRetryJobs(1)
                .build();
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .taskCostMatrix(paperCostMatrix())
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                        .failureModel(enabledFailure)
                        .build());
        assertTrue(failure.getMessage().contains("failure model to be disabled"), failure.getMessage());

        IllegalArgumentException overhead = assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(
                        workflow, 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                .overheadModel(OverheadModelConfig.builder().bandwidth(1.0).build())
                .build());
        assertTrue(overhead.getMessage().contains("OverheadModelConfig.none()"), overhead.getMessage());

        assertNotNull(SimulationConfig.builder(workflow, 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                .build());
    }

    @Test
    void runnerEnforcesBidirectionalTopologyContract() throws Exception {
        String workflow = resourcePath("/dax/heft-paper-example.dax");
        SimulationConfig fatTreeConfig = SimulationConfig.builder(workflow, 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                .build();

        SimulationConfigurationException missing =
                assertThrows(SimulationConfigurationException.class, () -> new SimulationRunner()
                        .run(fatTreeConfig, paperPlatform(null)));
        assertTrue(missing.getMessage().contains("networkTopology"), missing.getMessage());

        SimulationConfig legacyConfig = SimulationConfig.builder(workflow, 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();
        SimulationConfigurationException unused =
                assertThrows(SimulationConfigurationException.class, () -> new SimulationRunner()
                        .run(legacyConfig, paperPlatform(NetworkTopologySpec.fatTree(4, 1.0))));
        assertTrue(unused.getMessage().contains("does not use it"), unused.getMessage());
    }

    private static SimulationReport runFatTree(double linkBandwidthMb) throws Exception {
        Log.disable();
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                .build();
        return new SimulationRunner().run(config, paperPlatform(NetworkTopologySpec.fatTree(4, linkBandwidthMb)));
    }

    private static PlatformProfile paperPlatform(NetworkTopologySpec topology) {
        PlatformProfile.Builder builder = PlatformProfile.builder("fat-tree-paper-platform");
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

    private static double computeFinish(SimulationReport report, int taskId) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == Parameters.ClassType.COMPUTE.value
                    && job.getTaskIds().contains(Integer.valueOf(taskId))) {
                return job.getFinishTime();
            }
        }
        throw new AssertionError("No compute job for task " + taskId);
    }

    private static String resourcePath(String resource) throws Exception {
        java.net.URL url = FatTreeContentionIntegrationTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
