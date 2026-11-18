package org.workflowsim.planning.naco;

public final class NacoGene {
    private final int taskId;
    private final int vmId;

    public NacoGene(int taskId, int vmId) {
        if (taskId < 0 || vmId < 0) {
            throw new IllegalArgumentException("NACO gene identifiers must be non-negative");
        }
        this.taskId = taskId;
        this.vmId = vmId;
    }

    public int getTaskId() {
        return taskId;
    }

    public int getVmId() {
        return vmId;
    }
}
