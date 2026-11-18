package org.workflowsim.rl;

public final class RlDecision {

    private final double time;
    private final int jobId;
    private final int vmId;

    RlDecision(double time, int jobId, int vmId) {
        this.time = time;
        this.jobId = jobId;
        this.vmId = vmId;
    }

    public double getTime() {
        return time;
    }

    public int getJobId() {
        return jobId;
    }

    public int getVmId() {
        return vmId;
    }
}
