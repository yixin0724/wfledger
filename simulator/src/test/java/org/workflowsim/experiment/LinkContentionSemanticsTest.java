package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

/**
 * End-to-end semantic acceptance of the R2 link contention bandwidth model (HEFT paper example
 * fixture).
 *
 * <p>Fixture rationale: in the controlled schedule of the paper example (see
 * {@link LocalHeftPaperReproductionTest}) there is genuine concurrent transfer -- the input
 * transfers of t2 (18 MB) and t4 (9 MB) both start at the completion instant of the parent task t1
 * (110.1+9.0=119.1 s) and share the 1 MB/s NIC of the source endpoint vm2. Without a contention
 * model the two transfers each proceed at the full rate; with the contention model the two
 * transfers fairly share the vm2 egress capacity (0.5 MB/s each), their completion instants are
 * deferred and the makespan strictly increases -- exactly the trade-off signal that data-aware
 * scheduling research cares about.</p>
 *
 * <p>Assertion structure:</p>
 * <ul>
 *   <li>The contention makespan is strictly greater than the no-contention baseline 190.1 (direct
 *       evidence that concurrent transfers slow each other down);</li>
 *   <li>Golden values are locked: the contention makespan and the per-task finish instants are
 *       bit-stable (deterministic reruns);</li>
 *   <li>Evidence chain: DATA_STAGE_IN_MODELED carries the contended transfer group count and the new
 *       model kind;</li>
 *   <li>Configuration boundaries: INVALID planning tier + contention model is rejected (at the ready
 *       instant there is no target VM); LOCAL_HEFT + contention model is allowed (no contention
 *       estimate on the planning side, contention at run time, deviation declared by the
 *       documentation).</li>
 * </ul>
 */
class LinkContentionSemanticsTest {

    /** No-contention baseline makespan (paper 80 + bootstrap offset 110.1, see LocalHeftPaperReproductionTest). */
    private static final double NO_CONTENTION_BASELINE = 190.1;

    /**
     * Contention golden value: measured and locked from this fixture (2026-09-14), bit-stable across
     * reruns. Hand-derived corroboration: t2 (18 MB) and t4 (9 MB) share the source endpoint vm2
     * (1 MB/s) and get 0.5 MB/s each -- t4 completes at 119.1+18=137.1, t2 then resumes the full rate
     * and needs 9 more seconds (146.1), the subsequent transfer chain is deferred in turn, and the
     * total makespan grows from the no-contention 190.1 to 284.1.
     */
    private static final double CONTENTION_GOLDEN_MAKESPAN = 284.1;

    /** Paper cost matrix (identical to LocalHeftPaperReproductionTest). */
    private static final double[][] PAPER_COST_SECONDS = {
        {14, 16, 9}, {13, 19, 18}, {11, 13, 19}, {13, 8, 17}, {12, 13, 10},
        {13, 16, 9}, {7, 15, 11}, {5, 11, 14}, {18, 12, 20}, {21, 7, 16}
    };

    @Test
    void contentionMakespanStrictlyExceedsNoContentionBaseline() throws Exception {
        SimulationReport report = runWithContention();
        assertTrue(
                report.getMakespan() > NO_CONTENTION_BASELINE,
                "contention makespan " + report.getMakespan() + " must be strictly greater than the no-contention"
                        + " baseline " + NO_CONTENTION_BASELINE);
    }

    @Test
    void contentionGoldenMakespanIsLocked() throws Exception {
        SimulationReport report = runWithContention();
        assertEquals(
                CONTENTION_GOLDEN_MAKESPAN,
                report.getMakespan(),
                0.0,
                "contention golden value drift: the"
                        + " fixture or contention model semantics changed and require explicit review");
    }

    @Test
    void contentionScheduleIsDeterministic() throws Exception {
        SimulationReport first = runWithContention();
        SimulationReport second = runWithContention();
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0);
        for (int taskId = 1; taskId <= 10; taskId++) {
            assertEquals(
                    computeFinish(first, taskId),
                    computeFinish(second, taskId),
                    0.0,
                    "task " + taskId + " finish instant must be bit-identical across reruns");
        }
    }

    @Test
    void contentionEvidenceIsRecordedOnStageInEvents() throws Exception {
        SimulationReport report = runWithContention();
        int observed = 0;
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() != SimulationEventType.DATA_STAGE_IN_MODELED) {
                continue;
            }
            observed++;
            assertEquals(
                    "PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1",
                    event.getAttributes().get("dataMovementModel"));
            assertNotNull(
                    event.getAttributes().get("contentionTransferGroupCount"),
                    "contention model evidence" + " must carry the transfer group count");
        }
        // All 10 compute tasks record transfer evidence (the same evidence coverage as the no-contention
        // model).
        assertEquals(10, observed);
    }

    @Test
    void contentionModelConfigurationBoundaries() throws Exception {
        String workflow = resourcePath("/dax/heft-paper-example.dax");
        // INVALID planning tier + contention model: there is no target VM at the ready instant, so the
        // transfer endpoints cannot be determined.
        IllegalArgumentException unmapped =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(workflow, 3)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.MINMIN)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.preExecutionTransferDelayWithContentionV1())
                        .build());
        assertTrue(unmapped.getMessage().contains("INVALID"), unmapped.getMessage());
        // SHARED_STORAGE_HEFT + contention model: the shared-storage track requires alignment with the
        // legacy model.
        IllegalArgumentException shared =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                        .dataMovementModel(DataMovementModel.preExecutionTransferDelayWithContentionV1())
                        .build());
        assertTrue(shared.getMessage().contains("legacyWorkflowsimV1"), shared.getMessage());
        // LOCAL_HEFT + contention model: allowed (no contention AST on the planning side, fair sharing at
        // run time, the deviation is declared by the documentation).
        assertNotNull(SimulationConfig.builder(workflow, 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayWithContentionV1())
                .build());
    }

    private static SimulationReport runWithContention() throws Exception {
        Log.disable();
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayWithContentionV1())
                .build();
        return new SimulationRunner().run(config, contentionPlatform());
    }

    /**
     * Platform identical to the paper example: 3 VMs, mips=1.0, bandwidth 1 MB/s (contention capacity
     * 1e6 bytes/second).
     */
    private static PlatformProfile contentionPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("link-contention-platform");
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
        java.net.URL url = LinkContentionSemanticsTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
