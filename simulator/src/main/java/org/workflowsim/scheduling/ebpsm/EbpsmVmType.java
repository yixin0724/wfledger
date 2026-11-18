package org.workflowsim.scheduling.ebpsm;

public final class EbpsmVmType {
    private final int vmTypeId;
    private final double mips;
    private final double costPerSecond;
    private final double provisioningDelaySeconds;

    public EbpsmVmType(int vmTypeId, double mips, double costPerSecond, double provisioningDelaySeconds) {
        if (vmTypeId < 0
                || !(mips > 0.0)
                || costPerSecond < 0.0
                || provisioningDelaySeconds < 0.0
                || !Double.isFinite(mips)
                || !Double.isFinite(costPerSecond)
                || !Double.isFinite(provisioningDelaySeconds)) {
            throw new IllegalArgumentException("Invalid EBPSM VM type");
        }
        this.vmTypeId = vmTypeId;
        this.mips = mips;
        this.costPerSecond = costPerSecond;
        this.provisioningDelaySeconds = provisioningDelaySeconds;
    }

    public int getVmTypeId() {
        return vmTypeId;
    }

    public double getMips() {
        return mips;
    }

    public double getCostPerSecond() {
        return costPerSecond;
    }

    public double getProvisioningDelaySeconds() {
        return provisioningDelaySeconds;
    }
}
