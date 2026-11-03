package org.workflowsim.utils;

/** Shared timing rules required by the bundled CloudSim event kernel. */
public final class SimulationTiming {

    /** Historical completion-event guard of WorkflowDatacenter, in simulation seconds. */
    public static final double CLOUDLET_COMPLETION_SAFETY_SECONDS = 0.01;

    private SimulationTiming() {}

    /**
     * Compute the earliest Cloudlet completion time accepted by WorkflowDatacenter.
     *
     * <p>The value is the larger of the kernel-predicted completion time and
     * "current time + minimum event interval + completion guard", so that zero-delay or
     * too-close events do not violate the CloudSim kernel constraints.</p>
     *
     * @param currentTime current simulation time
     * @param predictedCompletionTime completion time predicted by the kernel
     * @param minimumEventIntervalSeconds minimum event interval allowed by the kernel, in
     *        simulation seconds
     * @return earliest acceptable completion time that is finite and later than the current
     *         instant
     * @throws IllegalArgumentException when a time is invalid, the addition overflows, or the
     *         floating-point clock cannot advance
     */
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
