package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

/**
 * Reproduction regression of the HEFT paper example (Topcuoglu, Hariri &amp; Wu, IEEE TPDS
 * 2002) on the controlled LOCAL execution model.
 *
 * <p><b>Data-source verification (2026-06)</b>: two mutually exclusive variants exist in
 * the community transcription; this test adopts the variant that is fully self-consistent
 * with the paper rank table (rank_u: 108/77/80/80/69/63.33/42.67/35.67/44.33/14.67) and
 * the paper makespan=80, and that agrees with the open-source reference implementation
 * <a href="https://github.com/mackncheesiest/heft">mackncheesiest/heft</a>
 * (heft.py comment "taken from Topcuoglu 2002 HEFT paper", whose test asserts a schedule
 * makespan of exactly 80.0). Encoding: 3 VMs with mips=1.0 and bandwidth 1 MB/s; edge
 * weight = intermediate file bytes/1e6 (the transfer seconds are bit-identical to the
 * paper edge weights); computation cost = the task-by-VM cost matrix in seconds (the MI
 * conversion is the identity).</p>
 *
 * <p><b>Reproduction verdict (controlled model vs paper ideal model)</b></p>
 * <ul>
 *   <li>The upward rank matches the paper item by item: under uniform bandwidth the
 *       average communication cost c-bar over off-diagonal VM pairs equals the paper edge
 *       weights exactly, so rank_u reproduces the paper table values exactly;</li>
 *   <li>The VM mapping agrees 10/10 with the paper/reference implementation (n1->vm2,
 *       n2->vm0, n3->vm2, n4->vm1, n5->vm2, n6->vm1, n7->vm2, n8->vm0, n9->vm1,
 *       n10->vm1), and every per-task interval is bit-identical to the paper interval plus
 *       the bootstrap offset 110.1 (for example n10 [73-80] -> [183.1-190.1]);</li>
 *   <li>Semantic basis: the preExecutionTransferDelayV1 data-movement model models the
 *       input transfer as a pre-execution network delay (Topcuoglu AST = max{avail[pj],
 *       max_pred(AFT+c)}); the transfer overlaps the VM busy period and the VM is occupied
 *       only by computation; the envelope semantics before the COMM-1 fix (transfer
 *       converted to MI, starting only after the VM becomes idle) would flip the t6 mapping
 *       and inflate the makespan to 129.1;</li>
 *   <li>Planning and runtime agree bit for bit: planned start/finish times == runtime
 *       observations (stable under deterministic re-runs).</li>
 * </ul>
 */
class LocalHeftPaperReproductionTest {

    /**
     * Computation seconds of paper task i (DAX document order -> task ID i) on VM j
     * (paper cost matrix).
     */
    private static final double[][] PAPER_COST_SECONDS = {
        {14, 16, 9}, // t1 = n1
        {13, 19, 18}, // t2 = n2
        {11, 13, 19}, // t3 = n3
        {13, 8, 17}, // t4 = n4
        {12, 13, 10}, // t5 = n5
        {13, 16, 9}, // t6 = n6
        {7, 15, 11}, // t7 = n7
        {5, 11, 14}, // t8 = n8
        {18, 12, 20}, // t9 = n9
        {21, 7, 16}, // t10 = n10
    };

    /** Stage-in bootstrap offset: stage-in Job (110 MI, mips=1.0) completes at 110.0 + kernel gap 0.1. */
    private static final double BOOTSTRAP_OFFSET = 110.1;

    /**
     * Per-task VM mapping of the controlled model (10/10 consistent with the
     * paper/reference implementation).
     */
    private static final int[] CONTROLLED_VM_BY_TASK = {-1, 2, 0, 2, 1, 2, 1, 2, 0, 1, 1};
    /**
     * Runtime start times of the controlled model (absolute seconds), index = task ID;
     * paper interval + bootstrap offset 110.1.
     */
    private static final double[] CONTROLLED_STARTS = {
        0.0, BOOTSTRAP_OFFSET, 137.1, 119.1, 128.1, 138.1, 136.1, 148.1, 167.1, 166.1, 183.1
    };
    /**
     * Runtime finish times of the controlled model (absolute seconds), index = task ID;
     * paper interval + bootstrap offset 110.1.
     */
    private static final double[] CONTROLLED_FINISHES = {
        0.0, 119.1, 150.1, 138.1, 136.1, 148.1, 152.1, 159.1, 172.1, 178.1, 190.1
    };
    /**
     * Actually modeled transfer seconds per task (zero when the replica is already on the
     * target VM; cross-VM = the paper edge weight).
     */
    private static final double[] CONTROLLED_TRANSFER_SECONDS = {
        0.0, 0.0, 18.0, 0.0, 9.0, 0.0, 14.0, 0.0, 42.0, 29.0, 28.0
    };

    @Test
    void heftPaperExampleReproducesControlledScheduleAndMapping() throws Exception {
        SimulationReport report = runPaperExample();
        // Controlled makespan = paper makespan 80 + bootstrap offset 110.1 (the transfer
        // overlaps the VM busy period, so the envelope serialization increment is gone).
        assertEquals(190.1, report.getMakespan(), 1.0e-9);
        int computeCount = 0;
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() != Parameters.ClassType.COMPUTE.value
                    || job.getTaskIds().size() != 1) {
                continue;
            }
            computeCount++;
            int taskId = job.getTaskIds().get(0).intValue();
            assertEquals(CONTROLLED_VM_BY_TASK[taskId], job.getVmId(), "VM mapping of task " + taskId);
            assertEquals(
                    CONTROLLED_STARTS[taskId],
                    job.getStartTime(),
                    1.0e-9,
                    "start time of task " + taskId + " (planning == runtime)");
            assertEquals(
                    CONTROLLED_FINISHES[taskId],
                    job.getFinishTime(),
                    1.0e-9,
                    "finish time of task " + taskId + " (planning == runtime)");
        }
        assertEquals(10, computeCount);
    }

    @Test
    void heftPaperScheduleIsCommunicationAware() throws Exception {
        SimulationReport report = runPaperExample();
        // t3/t5/t6/t7 share a VM with their parent task: replica locality makes the transfer
        // zero; the modeled transfer seconds of cross-VM tasks are bit-identical to the
        // paper edge weights (bytes/1e6/1 MB/s).
        for (int taskId = 1; taskId <= 10; taskId++) {
            SimulationEvent event = transferEventForTask(report, taskId);
            assertEquals(
                    CONTROLLED_TRANSFER_SECONDS[taskId],
                    ((Number) event.getAttributes().get("modeledTransferSeconds")).doubleValue(),
                    1.0e-9,
                    "modeled transfer seconds of task " + taskId);
        }
        assertEquals(140.0, report.getMetrics().getTotalModeledDataTransferSeconds(), 1.0e-9);
        assertEquals(10, report.getMetrics().getDataStageInModelObservationCount());
    }

    @Test
    void heftPaperReproductionIsDeterministic() throws Exception {
        SimulationReport first = runPaperExample();
        SimulationReport second = runPaperExample();
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0);
        for (int taskId = 1; taskId <= 10; taskId++) {
            assertEquals(
                    computeJobForTask(first, taskId).getVmId(),
                    computeJobForTask(second, taskId).getVmId());
            assertEquals(
                    computeJobForTask(first, taskId).getFinishTime(),
                    computeJobForTask(second, taskId).getFinishTime(),
                    0.0);
        }
    }

    @Test
    void localHeftRejectsIncompatibleExecutionModels() throws Exception {
        String workflow = resourcePath("/dax/heft-paper-example.dax");
        // SHARED file system: the communication modeling of LOCAL_HEFT holds only for LOCAL.
        IllegalArgumentException shared =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .taskCostMatrix(paperCostMatrix())
                        .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                        .build());
        assertTrue(shared.getMessage().contains("LOCAL"), shared.getMessage());
        // Data-movement model other than preExecutionTransferDelayV1: the planned AST and
        // the runtime transfer delay would lose alignment.
        IllegalArgumentException movement =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.fixedEndpointNoContention(50.0, 0.01, 25.0))
                        .build());
        assertTrue(movement.getMessage().contains("preExecutionTransferDelayV1"), movement.getMessage());
        // INVALID planning layer + the new data-movement model: at ready time there is no
        // target VM, so the transfer cannot be estimated (STATIC dispatch is already
        // blocked by the existing validation; this verifies that the online dispatch path
        // is rejected as well).
        IllegalArgumentException unmapped =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(workflow, 3)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.MINMIN)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                        .build());
        assertTrue(unmapped.getMessage().contains("INVALID"), unmapped.getMessage());
        // Clustering: LOCAL_HEFT requires NONE clustering (each Job corresponds to one Task).
        IllegalArgumentException clustering =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .clusteringParameters(
                                new ClusteringParameters(0, 0, ClusteringParameters.ClusteringMethod.BALANCED, ""))
                        .build());
        assertTrue(clustering.getMessage().contains("NONE"), clustering.getMessage());
    }

    private static SimulationReport runPaperExample() throws Exception {
        Log.disable();
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();
        return new SimulationRunner().run(config, paperPlatform());
    }

    /**
     * Reproduction platform: 3 VMs, mips=1.0, bandwidth 1 MB/s -- the cost/transfer
     * seconds are bit-identical to the paper values.
     */
    private static PlatformProfile paperPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("heft-paper-platform");
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

    private static SimulationEvent transferEventForTask(SimulationReport report, int taskId) {
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.DATA_STAGE_IN_MODELED
                    && event.getTaskIds().contains(Integer.valueOf(taskId))) {
                return event;
            }
        }
        throw new AssertionError("No DATA_STAGE_IN_MODELED event for task " + taskId);
    }

    private static SimulationReport.JobOutcome computeJobForTask(SimulationReport report, int taskId) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == Parameters.ClassType.COMPUTE.value
                    && job.getTaskIds().contains(Integer.valueOf(taskId))) {
                return job;
            }
        }
        throw new AssertionError("No compute job for task " + taskId);
    }

    private static String resourcePath(String resource) throws Exception {
        java.net.URL url = LocalHeftPaperReproductionTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
