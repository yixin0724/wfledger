package org.workflowsim.rl;

/**
 * Record of one successful dispatch: decision time, Job, and target VM. A skipped Job produces no
 * decision record.
 */
public final class RlDecision {

    private final double time;
    private final int jobId;
    private final int vmId;

    RlDecision(double time, int jobId, int vmId) {
        this.time = time;
        this.jobId = jobId;
        this.vmId = vmId;
    }

    /** @return simulation clock at dispatch time, in seconds */
    public double getTime() {
        return time;
    }

    /** @return ID of the dispatched Job */
    public int getJobId() {
        return jobId;
    }

    /** @return ID of the dispatch target VM */
    public int getVmId() {
        return vmId;
    }
}
