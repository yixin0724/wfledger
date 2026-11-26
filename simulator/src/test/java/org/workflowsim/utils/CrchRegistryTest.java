package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CrchRegistryTest {

    @AfterEach
    void clear() {
        CrchRegistry.clear();
    }

    @Test
    void checkpointsAreTakenOnlyWhileWorkRemains() {
        assertEquals(2L, CrchCheckpointModel.checkpointCount(3000L, 1000.0, 1.0));
        assertEquals(2L, CrchCheckpointModel.checkpointCount(2500L, 1000.0, 1.0));
        assertEquals(0L, CrchCheckpointModel.checkpointCount(1000L, 1000.0, 1.0));
        assertEquals(3L, CrchCheckpointModel.checkpointCount(3001L, 1000.0, 1.0));
    }

    @Test
    void everyAttemptPaysItsCheckpointOverhead() {
        assertEquals(3200L, CrchCheckpointModel.attemptLengthMi(3000L, 1000.0, new CrchConfig(1.0, 0.1)));
        assertEquals(1000L, CrchCheckpointModel.attemptLengthMi(1000L, 1000.0, new CrchConfig(1.0, 0.1)));
    }

    @Test
    void checkpointOverheadDelaysTheNextCompletedCheckpoint() {
        CrchRegistry.configure(new CrchConfig(1.0, 1.0));

        CrchRecovery recovery = CrchRegistry.recordFailure(1, 3, 0L, 4000L, 3L, 3.5, 1000.0);

        assertEquals(1L, recovery.getCheckpointCount());
        assertEquals(1000L, recovery.getSavedWorkMi());
        assertEquals(3000L, recovery.getRemainingWorkMi());
    }

    @Test
    void savedWorkNeverReachesTheOriginalTask() {
        CrchRegistry.configure(new CrchConfig(1.0, 0.5));

        CrchRecovery recovery = CrchRegistry.recordFailure(1, 3, 0L, 1000L, 0L, 10.0, 1000.0);

        assertEquals(0L, recovery.getCheckpointCount());
        assertEquals(1000L, recovery.getRemainingWorkMi());
    }

    @Test
    void completedCheckpointsReduceRetryWorkInsteadOfRestartingEverything() {
        CrchRegistry.configure(new CrchConfig(1.0, 0.1));

        CrchRecovery recovery = CrchRegistry.recordFailure(1, 3, 0L, 4000L, 3L, 3.5, 1000.0);
        CrchRegistry.registerRetry(9, recovery);

        assertEquals(3L, recovery.getCheckpointCount());
        assertEquals(3000L, recovery.getSavedWorkMi());
        assertEquals(1000L, recovery.getRemainingWorkMi());
        assertEquals(3, recovery.getFailedVmId());
        assertEquals(3L, CrchRegistry.getCheckpointCount(1));
        assertEquals(1000L, CrchRegistry.getRecovery(9).getRemainingWorkMi());
    }

    @Test
    void aResumedAttemptKeepsItsCreditedChain() {
        CrchRegistry.configure(new CrchConfig(1.0, 0.1));

        CrchRecovery recovery = CrchRegistry.recordFailure(1, 3, 2000L, 1000L, 0L, 0.5, 1000.0);

        assertEquals(2000L, recovery.getSavedWorkMi());
        assertEquals(1000L, recovery.getRemainingWorkMi());
        assertEquals(3000L, recovery.getWorkMi());
    }

    @Test
    void savedWorkIsCreditedOnlyOnTheCheckpointingVm() {
        CrchRegistry.configure(new CrchConfig(1.0, 0.1));
        CrchRecovery recovery = CrchRegistry.recordFailure(1, 3, 0L, 4000L, 3L, 3.5, 1000.0);

        assertEquals(3000L, recovery.creditOn(3));
        assertEquals(0L, recovery.creditOn(8));
    }

    @Test
    void rejectsANegativeComputeElapsedTime() {
        CrchRegistry.configure(new CrchConfig(1.0, 0.1));
        assertThrows(
                IllegalArgumentException.class, () -> CrchRegistry.recordFailure(1, 3, 0L, 4000L, 3L, -0.1, 1000.0));
    }
}
