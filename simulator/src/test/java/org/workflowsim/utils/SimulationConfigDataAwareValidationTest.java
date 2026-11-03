package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;

/**
 * Regression test (PLAT-7): the combination of the DATA scheduling algorithm and the
 * SHARED file system mode must be rejected at the configuration layer.
 *
 * <p>Background: the DATA algorithm matches replica sites by VM ID for its locality
 * verdict, whereas replicas in SHARED mode are registered by datacenter name, so the two
 * never match -- DATA silently degrades to first-fit-idle and produces misleading
 * experimental results. After the fix, {@link SimulationConfig.Builder#build()} rejects
 * this combination outright.</p>
 */
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
        // SHARED mode does not affect the other online algorithms.
        assertDoesNotThrow(() -> SimulationConfig.builder(Collections.singletonList("workflow.dax"), 2)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .build());
    }
}
