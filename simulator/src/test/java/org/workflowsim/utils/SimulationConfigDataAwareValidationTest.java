package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;

class SimulationConfigDataAwareValidationTest {

    @Test
    void rejectsDataAwareWithSharedFileSystem() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(
                                Collections.singletonList("workflow.dax"), 2)
                        .schedulingAlgorithm(SchedulingAlgorithm.DATA)
                        .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                        .build());
        assertTrue(
                exception.getMessage().contains("SchedulingAlgorithm.DATA"),
                "Rejection message must name the DATA scheduling algorithm: " + exception.getMessage());
        assertTrue(
                exception.getMessage().contains("LOCAL"),
                "Rejection message must state the LOCAL requirement: " + exception.getMessage());
    }

    @Test
    void acceptsDataAwareWithLocalFileSystem() {
        assertDoesNotThrow(() -> SimulationConfig.builder(Collections.singletonList("workflow.dax"), 2)
                .schedulingAlgorithm(SchedulingAlgorithm.DATA)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .build());
    }

    @Test
    void acceptsSharedFileSystemWithOtherAlgorithms() {

        assertDoesNotThrow(() -> SimulationConfig.builder(Collections.singletonList("workflow.dax"), 2)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .build());
    }
}
