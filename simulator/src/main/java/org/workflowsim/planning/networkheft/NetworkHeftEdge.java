package org.workflowsim.planning.networkheft;

public final class NetworkHeftEdge {
    private final int childTaskId;
    private final double dataBytes;
    private final double nominalDurationSeconds;
    private final String bottleneck;

    public NetworkHeftEdge(int childTaskId, double dataBytes, double nominalDurationSeconds, String bottleneck) {
        if (childTaskId < 0
                || dataBytes < 0.0
                || nominalDurationSeconds < 0.0
                || !Double.isFinite(dataBytes)
                || !Double.isFinite(nominalDurationSeconds)
                || bottleneck == null
                || bottleneck.isEmpty()) {
            throw new IllegalArgumentException("Invalid Network-Aware HEFT edge");
        }
        this.childTaskId = childTaskId;
        this.dataBytes = dataBytes;
        this.nominalDurationSeconds = nominalDurationSeconds;
        this.bottleneck = bottleneck;
    }

    public int getChildTaskId() {
        return childTaskId;
    }

    public double getDataBytes() {
        return dataBytes;
    }

    public double getNominalDurationSeconds() {
        return nominalDurationSeconds;
    }

    public String getBottleneck() {
        return bottleneck;
    }
}
