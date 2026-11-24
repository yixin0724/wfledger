package org.workflowsim.utils;

/**
 * The checkpoint state left by one failed CRCH attempt.
 *
 * <p>FGCS-R5 (2026-11-24): checkpoints are VM-local. The paper dumps each synchronized checkpoint to
 * "a nonvolatile stable storage associated with each VM"; a task "scheduled on the same VM ... can
 * resume from the latest checkpoint, if not it pays the overhead of re-executing already completed
 * checkpoints" (arXiv:1810.06361v2 Sec. 3.1.3; Eq. 20 versus Eq. 21). {@link #creditOn(int)} therefore
 * returns the saved work only for the VM that took the checkpoints.
 */
public final class CrchRecovery {
    private final long workMi;
    private final long savedWorkMi;
    private final long checkpointCount;
    private final int failedVmId;

    CrchRecovery(long workMi, long savedWorkMi, long checkpointCount, int failedVmId) {
        if (workMi <= 0L || savedWorkMi < 0L || savedWorkMi >= workMi || checkpointCount < 0L) {
            throw new IllegalArgumentException("Saved CRCH work must lie in [0, work)");
        }
        this.workMi = workMi;
        this.savedWorkMi = savedWorkMi;
        this.checkpointCount = checkpointCount;
        this.failedVmId = failedVmId;
    }

    /** @return total task work on the failed VM, in that VM's MI */
    public long getWorkMi() {
        return workMi;
    }

    /** @return work saved by the checkpoint chain on the failed VM */
    public long getSavedWorkMi() {
        return savedWorkMi;
    }

    /** @return work a resumption on the failed VM still has to compute */
    public long getRemainingWorkMi() {
        return workMi - savedWorkMi;
    }

    /** @return checkpoints committed by the failed attempt itself */
    public long getCheckpointCount() {
        return checkpointCount;
    }

    public int getFailedVmId() {
        return failedVmId;
    }

    /**
     * Returns the saved work a retry on {@code vmId} may skip.
     *
     * @param vmId candidate VM
     * @return the saved work on the checkpointing VM, zero elsewhere
     */
    public long creditOn(int vmId) {
        return vmId == failedVmId ? savedWorkMi : 0L;
    }
}
