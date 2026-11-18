package org.workflowsim.utils;

public final class CrchRecovery {
    private final long remainingWorkMi;
    private final long checkpointCount;
    private final int failedVmId;

    CrchRecovery(long remainingWorkMi, long checkpointCount, int failedVmId) {
        this.remainingWorkMi = remainingWorkMi;
        this.checkpointCount = checkpointCount;
        this.failedVmId = failedVmId;
    }

    public long getRemainingWorkMi() {
        return remainingWorkMi;
    }

    public long getCheckpointCount() {
        return checkpointCount;
    }

    public int getFailedVmId() {
        return failedVmId;
    }
}
