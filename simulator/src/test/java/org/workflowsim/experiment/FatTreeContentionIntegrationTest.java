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
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

/**
 * End-to-end semantic acceptance of the Fat-tree topology-aware link contention model (HEFT paper
 * example fixture).
 *
 * <p>This test uses the same fixture and the same platform as the R2 endpoint contention model
 * ({@link LinkContentionSemanticsTest}, golden value 284.1); only the data movement model and the
 * platform topology declaration differ: a k=4 Fat-tree, whose default round-robin placement spreads
 * the 3 hosts over (pod0,edge0), (pod0,edge1), and (pod1,edge0). Two link bandwidth scenarios:</p>
 * <ul>
 *   <li><b>Symmetric scenario (link = endpoint = 1 MB/s)</b>: after the R8 audit fix (F1 unit
 *       contract), link capacity is interpreted in the declared MB/s, on the same scale as the VM
 *       endpoints -- a single flow is never slowed by the link, so the Fat-tree makespan is
 *       bit-identical to R2 (284.1). That equality is the end-to-end regression lock on the unit
 *       contract: before the fix the same declared value made links 1/8 of the endpoint (the
 *       division-by-8 unit bug) and pushed the makespan to 1032.1.</li>
 *   <li><b>Slow-link scenario (link 0.25 MB/s = 1/4 of the endpoint)</b>: the link becomes the real
 *       bottleneck and concurrent flows sharing a link slow down further, so the makespan is
 *       strictly greater than R2 -- locking in that "link contention semantics really take effect"
 *       and preventing the model from degenerating into pure endpoint contention.</li>
 * </ul>
 *
 * <p>Assertion structure:</p>
 * <ul>
 *   <li>The rate constraint is a superset of the R2 endpoint constraint (the minimum of endpoint and
 *       path link), so the Fat-tree makespan is >= the R2 golden value 284.1 and strictly greater
 *       than the no-contention baseline 190.1;</li>
 *   <li>Both scenario golden values are locked and deterministic reruns are bit-identical;</li>
 *   <li>Evidence chain: DATA_STAGE_IN_MODELED carries the new model kind and fatTreePathLinkCount;</li>
 *   <li>Health invariants: all logical tasks complete successfully and the Job count is conserved;</li>
 *   <li>Configuration contract: SHARED fs / INVALID planning / non-NONE clustering / enabled failure
 *       / non-zero overhead are rejected; LOCAL_HEFT is allowed; the runner contract is
 *       bidirectional -- a model without a topology declaration is rejected, and a topology
 *       declaration without a model is rejected.</li>
 * </ul>
 */
class FatTreeContentionIntegrationTest {

    /** No-contention baseline makespan (paper 80 + bootstrap offset 110.1). */
    private static final double NO_CONTENTION_BASELINE = 190.1;
    /** R2 endpoint contention golden value (LinkContentionSemanticsTest): the lower bound for Fat-tree. */
    private static final double R2_ENDPOINT_GOLDEN = 284.1;
    /**
     * Symmetric scenario golden value (re-recorded on 2026-09-16 after the R8 audit F1 fix, reruns are
     * bit-stable): link 1 MB/s = endpoint 1 MB/s, the link never binds a single flow, and R6 equals R2
     * bit for bit. That equality is the end-to-end lock on the unit contract (before the fix links were
     * divided by 8 and the same scenario produced 1032.1).
     */
    private static final double FAT_TREE_GOLDEN_MAKESPAN = 284.1;
    /** Slow-link scenario link bandwidth (MB/s): 1/4 of the 1 MB/s endpoint. */
    private static final double SLOW_LINK_BANDWIDTH_MB = 0.25;
    /**
     * Slow-link scenario golden value: locked from measurements on this fixture (re-recorded in the R8
     * audit on 2026-09-16) and bit-stable across reruns. When links are slower than endpoints,
     * concurrent flows sharing a link are throttled and the makespan is strictly greater than R2.
     */
    private static final double SLOW_LINK_GOLDEN_MAKESPAN = 588.1;

    /** Paper cost matrix (the same as in LocalHeftPaperReproductionTest). */
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
        // Unit contract: with symmetric supply R6 equals R2 bit for bit (the link binds no single flow).
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
        // SHARED file system: VM->VM communication is modeled only under LOCAL.
        IllegalArgumentException shared =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .taskCostMatrix(paperCostMatrix())
                        .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                        .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                        .build());
        assertTrue(shared.getMessage().contains("LOCAL"), shared.getMessage());
        // INVALID planning layer: no target VM is known at readiness time, so the path is unknown.
        IllegalArgumentException unmapped =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(workflow, 3)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.MINMIN)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                        .build());
        assertTrue(unmapped.getMessage().contains("planning algorithm"), unmapped.getMessage());
        // Non-NONE clustering: Jobs no longer correspond one-to-one with logical tasks.
        IllegalArgumentException clustered =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .taskCostMatrix(paperCostMatrix())
                        .clusteringParameters(
                                new ClusteringParameters(2, 0, ClusteringParameters.ClusteringMethod.HORIZONTAL, null))
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                        .build());
        assertTrue(clustered.getMessage().contains("clustering method NONE"), clustered.getMessage());
        // Failure model enabled: fat-tree paths require deterministic failure-free semantics.
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
        // Non-zero overhead: the same precondition as the LOCAL static DAG track.
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
        // LOCAL_HEFT + Fat-tree contention: allowed (no contention-aware AST on the planning side, link
        // contention at runtime).
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
        // Model declared but the platform has no topology -> rejected.
        SimulationConfigurationException missing =
                assertThrows(SimulationConfigurationException.class, () -> new SimulationRunner()
                        .run(fatTreeConfig, paperPlatform(null)));
        assertTrue(missing.getMessage().contains("networkTopology"), missing.getMessage());
        // Platform declares a topology but the model does not use it -> rejected (a declaration must not be
        // silently ignored).
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

    /**
     * Platform matching the paper example: 3 hosts and 3 VMs (mips=1.0, bandwidth 1 MB/s), with an
     * optional topology declaration.
     */
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
