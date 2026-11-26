package org.workflowsim.utils;

/** Exact arithmetic of the CRCH synchronized-checkpoint model. */
public final class CrchCheckpointModel {

    static final double TOLERANCE = 1.0e-9;

    private CrchCheckpointModel() {}

    public static long checkpointCount(long workMi, double mips, double intervalSeconds) {
        requirePositive(workMi, mips);
        if (!(intervalSeconds > 0.0) || !Double.isFinite(intervalSeconds)) {
            throw new IllegalArgumentException("Checkpoint interval must be positive and finite");
        }
        double intervals = (workMi / mips) / intervalSeconds;
        return Math.max(0L, (long) Math.ceil(intervals - TOLERANCE) - 1L);
    }

    public static long attemptLengthMi(long workMi, double mips, CrchConfig config) {
        long checkpoints = checkpointCount(workMi, mips, config.getCheckpointIntervalSeconds());
        return Math.addExact(workMi, overheadMi(checkpoints, mips, config));
    }

    static long overheadMi(long checkpoints, double mips, CrchConfig config) {
        double overhead = checkpoints * config.getCheckpointOverheadSeconds() * mips;
        if (!Double.isFinite(overhead) || overhead >= (double) Long.MAX_VALUE) {
            throw new IllegalArgumentException("Checkpoint overhead is not representable in MI");
        }
        return Math.max(0L, (long) Math.ceil(overhead - TOLERANCE));
    }

    static long committedCheckpoints(double elapsedComputeSeconds, long plannedCheckpoints, CrchConfig config) {
        if (!(elapsedComputeSeconds >= 0.0) || !Double.isFinite(elapsedComputeSeconds) || plannedCheckpoints < 0L) {
            throw new IllegalArgumentException("Failure elapsed time must be finite and non-negative");
        }
        double period = config.getCheckpointIntervalSeconds() + config.getCheckpointOverheadSeconds();
        long completed = (long) Math.floor(elapsedComputeSeconds / period + TOLERANCE);
        return Math.min(plannedCheckpoints, completed);
    }

    private static void requirePositive(long workMi, double mips) {
        if (workMi <= 0L || !(mips > 0.0) || !Double.isFinite(mips)) {
            throw new IllegalArgumentException("CRCH requires positive work and VM speed");
        }
    }
}
