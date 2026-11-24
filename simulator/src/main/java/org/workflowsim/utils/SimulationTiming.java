package org.workflowsim.utils;

/**
 * Kernel-tick-derived timing guards shared by the datacenter runtime, the planners and the
 * controlled lower-bound reference.
 *
 * <p>FGCS-R3 (2026-11-24, A4): root cause — the completion safety margin was a fixed 0.01 s and
 * the initial-processing threshold a hard-coded 0.111 s literal (with an unused duplicate
 * constant), so neither followed the configured {@code cloudSimMinEventIntervalSeconds}; a
 * finer tick still forced every completion at least 0.011 s after submission. Both values are now
 * derived from the configured interval {@code tick}: {@code safety = tick / 10} and
 * {@code threshold = tick + safety + safety / 10}. At the 0.1 s default these evaluate to exactly
 * 0.01 and 0.111 in binary64, so default-configuration results are bit-identical.
 */
public final class SimulationTiming {

    private static final double SAFETY_FRACTION_OF_TICK = 10.0;

    private SimulationTiming() {}

    /**
     * Returns the completion safety margin for a kernel tick.
     *
     * @param minimumEventIntervalSeconds configured kernel tick
     * @return {@code tick / 10}
     */
    public static double completionSafetySeconds(double minimumEventIntervalSeconds) {
        if (!isFiniteNonNegative(minimumEventIntervalSeconds)) {
            throw new IllegalArgumentException("CloudSim minimum event interval must be finite and non-negative");
        }
        return minimumEventIntervalSeconds / SAFETY_FRACTION_OF_TICK;
    }

    /**
     * Returns the initial period during which the datacenter always refreshes VM processing.
     * It lies strictly after the earliest guarded completion {@code tick + safety}, so the first
     * guarded completion (for example the stage-in Job) is still processed unconditionally.
     *
     * @param minimumEventIntervalSeconds configured kernel tick
     * @return {@code tick + safety + safety / 10}
     */
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
