package org.workflowsim.utils;

public final class SimulationTiming {

    public static final double CLOUDLET_COMPLETION_SAFETY_SECONDS = 0.01;

    private SimulationTiming() {}

    public static double earliestCloudletCompletionTime(
            double currentTime, double predictedCompletionTime, double minimumEventIntervalSeconds) {
        if (!isFiniteNonNegative(currentTime)
                || !isFiniteNonNegative(predictedCompletionTime)
                || !isFiniteNonNegative(minimumEventIntervalSeconds)) {
            throw new IllegalArgumentException("CloudSim timing values must be finite and non-negative");
        }
        double completion = Math.max(
                predictedCompletionTime,
                currentTime + minimumEventIntervalSeconds + CLOUDLET_COMPLETION_SAFETY_SECONDS);
        if (!Double.isFinite(completion) || completion <= currentTime) {
            throw new IllegalArgumentException("Cloudlet completion cannot advance the finite simulation "
                    + "clock at its current precision: " + currentTime);
        }
        return completion;
    }

    private static boolean isFiniteNonNegative(double value) {
        return value >= 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
