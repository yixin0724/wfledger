package org.workflowsim.utils;

public final class CrchConfig {
    private final double checkpointIntervalSeconds;
    private final double checkpointOverheadSeconds;

    public CrchConfig(double checkpointIntervalSeconds, double checkpointOverheadSeconds) {
        if (!(checkpointIntervalSeconds > 0.0)
                || !Double.isFinite(checkpointIntervalSeconds)
                || checkpointOverheadSeconds < 0.0
                || !Double.isFinite(checkpointOverheadSeconds)) {
            throw new IllegalArgumentException("Invalid CRCH checkpoint interval or overhead");
        }
        this.checkpointIntervalSeconds = checkpointIntervalSeconds;
        this.checkpointOverheadSeconds = checkpointOverheadSeconds;
    }

    public double getCheckpointIntervalSeconds() {
        return checkpointIntervalSeconds;
    }

    public double getCheckpointOverheadSeconds() {
        return checkpointOverheadSeconds;
    }
}
