package org.workflowsim.utils;

public final class SimulationConstants {

    public static final long STAGE_IN_JOB_LENGTH_MI = 110L;

    public static final double INITIAL_PROCESSING_THRESHOLD_SECONDS = 0.111;

    public static final long MIN_TASK_LENGTH_MI = 100L;

    private SimulationConstants() {
        throw new AssertionError("SimulationConstants is a constants class and must not be instantiated");
    }
}
