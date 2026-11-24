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

class LinkContentionSemanticsTest {

    private static final double NO_CONTENTION_BASELINE = 190.1;

    private static final double CONTENTION_GOLDEN_MAKESPAN = 284.1;

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

        assertEquals(10, observed);
    }

    @Test
    void contentionModelConfigurationBoundaries() throws Exception {
        String workflow = resourcePath("/dax/heft-paper-example.dax");

        IllegalArgumentException unmapped =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(workflow, 3)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.preExecutionTransferDelayWithContentionV1())
                        .build());
        assertTrue(unmapped.getMessage().contains("INVALID"), unmapped.getMessage());

        IllegalArgumentException shared =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                        .dataMovementModel(DataMovementModel.preExecutionTransferDelayWithContentionV1())
                        .build());
        assertTrue(shared.getMessage().contains("legacyWorkflowsimV1"), shared.getMessage());

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
