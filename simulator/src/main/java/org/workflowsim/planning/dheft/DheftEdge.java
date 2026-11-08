package org.workflowsim.planning.dheft;

/** One DHEFT parent-child edge with real input bytes. */
public final class DheftEdge {
    private final int childTaskId;
    private final double dataBytes;

    public DheftEdge(int childTaskId, double dataBytes) {
        if (childTaskId < 0 || dataBytes < 0.0 || !Double.isFinite(dataBytes)) {
            throw new IllegalArgumentException("Invalid DHEFT edge");
        }
        this.childTaskId = childTaskId;
        this.dataBytes = dataBytes;
    }

    public int getChildTaskId() {
        return childTaskId;
    }

    public double getDataBytes() {
        return dataBytes;
    }
}
