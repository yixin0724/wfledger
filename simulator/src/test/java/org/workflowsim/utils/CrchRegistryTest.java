package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Hand-derived CRCH checkpoint progress and recovery checks. */
class CrchRegistryTest {

    @AfterEach
    void clear() {
        CrchRegistry.clear();
    }

    @Test
    void completedCheckpointsReduceRetryWorkInsteadOfRestartingEverything() {
        CrchRegistry.configure(new CrchConfig(1.0, 0.1));

        CrchRecovery recovery = CrchRegistry.recordFailure(1, 3, 0.0, 3.5, 4000L, 1000.0);
        CrchRegistry.registerRetry(9, recovery);

        assertEquals(3L, recovery.getCheckpointCount());
        assertEquals(1000L, recovery.getRemainingWorkMi());
        assertEquals(3, recovery.getFailedVmId());
        assertEquals(3L, CrchRegistry.getCheckpointCount(1));
        assertEquals(1000L, CrchRegistry.getRecovery(9).getRemainingWorkMi());
    }
}
