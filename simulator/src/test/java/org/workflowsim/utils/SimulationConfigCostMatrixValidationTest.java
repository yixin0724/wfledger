package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;

class SimulationConfigCostMatrixValidationTest {

    private static final PlanningAlgorithm[] SHARED_STORAGE_PLANNERS = {
        PlanningAlgorithm.SHARED_STORAGE_HEFT,
        PlanningAlgorithm.SHARED_STORAGE_CPOP,
        PlanningAlgorithm.SHARED_STORAGE_DLS,
        PlanningAlgorithm.SHARED_STORAGE_ETF,
        PlanningAlgorithm.SHARED_STORAGE_PEFT,
    };

    @Test
    void rejectsCostMatrixWithEverySharedStoragePlanner() {
        for (PlanningAlgorithm planner : SHARED_STORAGE_PLANNERS) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> SimulationConfig.builder(Collections.singletonList("workflow.dax"), 2)
                            .planningAlgorithm(planner)
                            .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                            .taskCostMatrix(matrix())
                            .build(),
                    planner + " + task cost matrix must be rejected");
            assertTrue(failure.getMessage().contains(planner.toString()), failure.getMessage());
            assertTrue(failure.getMessage().contains("task cost matrix"), failure.getMessage());
        }
    }

    @Test
    void rejectsCostMatrixWithRawLengthDheftPlanner() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(
                        Collections.singletonList("workflow.dax"), 2)
                .planningAlgorithm(PlanningAlgorithm.DHEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(org.workflowsim.data.DataMovementModel.preExecutionTransferDelayV1())
                .taskCostMatrix(matrix())
                .build());
        assertTrue(failure.getMessage().contains(PlanningAlgorithm.DHEFT.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("task cost matrix"), failure.getMessage());
    }

    @Test
    void acceptsCostMatrixWithLocalHeftPlanner() {
        assertDoesNotThrow(() -> SimulationConfig.builder(Collections.singletonList("workflow.dax"), 2)
                .planningAlgorithm(PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(org.workflowsim.data.DataMovementModel.preExecutionTransferDelayV1())
                .taskCostMatrix(matrix())
                .build());
    }

    private static TaskCostMatrix matrix() {
        return TaskCostMatrix.builder().put(1, 0, 10.0).build();
    }
}
