package org.workflowsim.utils;

/** The checkpoint state left by one failed CRCH attempt. */
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

    public long getWorkMi() {
        return workMi;
    }

    public long getSavedWorkMi() {
        return savedWorkMi;
    }

    public long getRemainingWorkMi() {
        return workMi - savedWorkMi;
    }

    public long getCheckpointCount() {
        return checkpointCount;
    }

    public int getFailedVmId() {
        return failedVmId;
    }

    public long creditOn(int vmId) {
        return vmId == failedVmId ? savedWorkMi : 0L;
    }
}
