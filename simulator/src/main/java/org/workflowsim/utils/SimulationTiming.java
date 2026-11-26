package org.workflowsim.utils;

/**
 * Kernel-tick-derived timing guards shared by the datacenter runtime, the planners and the controlled lower-bound
 * reference.
 */
public final class SimulationTiming {

    private static final double SAFETY_FRACTION_OF_TICK = 10.0;

    private SimulationTiming() {}

    public static double completionSafetySeconds(double minimumEventIntervalSeconds) {
        if (!isFiniteNonNegative(minimumEventIntervalSeconds)) {
            throw new IllegalArgumentException("CloudSim minimum event interval must be finite and non-negative");
        }
        return minimumEventIntervalSeconds / SAFETY_FRACTION_OF_TICK;
    }

    public static double initialProcessingThresholdSeconds(double minimumEventIntervalSeconds) {
        double safety = completionSafetySeconds(minimumEventIntervalSeconds);
        return minimumEventIntervalSeconds + safety + safety / SAFETY_FRACTION_OF_TICK;
    }

    public static double earliestCloudletCompletionTime(
            double currentTime, double predictedCompletionTime, double minimumEventIntervalSeconds) {
        if (!isFiniteNonNegative(currentTime)
                || !isFiniteNonNegative(predictedCompletionTime)
                || !isFiniteNonNegative(minimumEventIntervalSeconds)) {
            throw new IllegalArgumentException("CloudSim timing values must be finite and non-negative");
        }
        double completion = Math.max(
                predictedCompletionTime,
                currentTime + minimumEventIntervalSeconds + completionSafetySeconds(minimumEventIntervalSeconds));
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
