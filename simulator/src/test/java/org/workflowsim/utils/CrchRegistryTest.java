package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * FGCS-R5 (2026-11-24): hand-derived checkpoint arithmetic of the CRCH synchronized-checkpoint model
 * (Setlur et al., JPDC 2019, Sec. 3.1.3 and Eq. 10). With interval {@code λ} and overhead {@code γ},
 * a checkpoint is taken after every {@code λ} seconds of compute progress while work remains, so an
 * attempt of {@code T} compute seconds takes {@code n = ceil(T/λ) - 1} checkpoints and runs
 * {@code T + n·γ} seconds; checkpoint {@code k} completes {@code k·(λ+γ)} seconds after compute start.
 */
class CrchRegistryTest {

    @AfterEach
    void clear() {
        CrchRegistry.clear();
    }

    /** 3000 MI at 1000 MIPS with λ = 1 s: checkpoints at 1 s and 2 s of progress; 3 s is the end, not a checkpoint. */
    @Test
    void checkpointsAreTakenOnlyWhileWorkRemains() {
        assertEquals(2L, CrchCheckpointModel.checkpointCount(3000L, 1000.0, 1.0));
        assertEquals(2L, CrchCheckpointModel.checkpointCount(2500L, 1000.0, 1.0));
        assertEquals(0L, CrchCheckpointModel.checkpointCount(1000L, 1000.0, 1.0));
        assertEquals(3L, CrchCheckpointModel.checkpointCount(3001L, 1000.0, 1.0));
    }

    /** Two checkpoints of 0.1 s at 1000 MIPS are 200 MI of overhead on top of the 3000 MI of work. */
    @Test
    void everyAttemptPaysItsCheckpointOverhead() {
        assertEquals(3200L, CrchCheckpointModel.attemptLengthMi(3000L, 1000.0, new CrchConfig(1.0, 0.1)));
        assertEquals(1000L, CrchCheckpointModel.attemptLengthMi(1000L, 1000.0, new CrchConfig(1.0, 0.1)));
    }

    /**
     * Checkpoint k completes at k·(λ+γ): with λ = 1, γ = 1 the second completes at 4 s, so 3.5 s of
     * compute commits only the first even though 3.5 s exceed two intervals of progress.
     */
    @Test
    void checkpointOverheadDelaysTheNextCompletedCheckpoint() {
        CrchRegistry.configure(new CrchConfig(1.0, 1.0));

        CrchRecovery recovery = CrchRegistry.recordFailure(1, 3, 0L, 4000L, 3L, 3.5, 1000.0);

        assertEquals(1L, recovery.getCheckpointCount());
        assertEquals(1000L, recovery.getSavedWorkMi());
        assertEquals(3000L, recovery.getRemainingWorkMi());
    }

    /** Committed checkpoints never exceed the attempt's planned checkpoints, so saved work stays below the task. */
    @Test
    void savedWorkNeverReachesTheOriginalTask() {
        CrchRegistry.configure(new CrchConfig(1.0, 0.5));

        CrchRecovery recovery = CrchRegistry.recordFailure(1, 3, 0L, 1000L, 0L, 10.0, 1000.0);

        assertEquals(0L, recovery.getCheckpointCount());
        assertEquals(1000L, recovery.getRemainingWorkMi());
    }

    /**
     * λ = 1, γ = 0.1, 4000 MI planned with 3 checkpoints: failing 3.5 s into compute commits the
     * checkpoints completed at 1.1, 2.2 and 3.3 s, saving 3000 MI on VM 3 and leaving 1000 MI.
     */
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

    /**
     * A resumed attempt keeps the chain on its VM: 2000 MI already credited, 1000 MI planned with no
     * checkpoint, failing after 0.5 s saves nothing new, so the credit stays 2000 MI.
     */
    @Test
    void aResumedAttemptKeepsItsCreditedChain() {
        CrchRegistry.configure(new CrchConfig(1.0, 0.1));

        CrchRecovery recovery = CrchRegistry.recordFailure(1, 3, 2000L, 1000L, 0L, 0.5, 1000.0);

        assertEquals(2000L, recovery.getSavedWorkMi());
        assertEquals(1000L, recovery.getRemainingWorkMi());
        assertEquals(3000L, recovery.getWorkMi());
    }

    /** The paper's checkpoints are VM-local (arXiv:1810.06361v2 Sec. 3.1.3): credit applies only on the failed VM. */
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
