package org.workflowsim.utils;

/**
 * Exact arithmetic of the CRCH synchronized-checkpoint model.
 *
 * <p>FGCS-R5 (2026-11-24): root cause - checkpointing was free on success. A successful attempt ran
 * the bare work and only a failed attempt's retry paid an ad-hoc {@code ceil(γ·mips)} MI, although
 * the paper charges the checkpoint overhead to every execution ({@code CO = TET × γ/λ}, Setlur et al.,
 * JPDC 2019, arXiv:1810.06361v2 Eq. 10-11). The model is now:
 *
 * <ul>
 *   <li>An attempt that must compute {@code W} MI at {@code mips} has {@code T = W / mips} seconds of
 *       work. A checkpoint is taken after every {@code λ} seconds of compute progress while work
 *       remains, so the attempt takes {@code n = max(0, ceil(T/λ) - 1)} checkpoints (a checkpoint
 *       exactly at completion is not taken) and executes {@code W + ceil(n·γ·mips)} MI.</li>
 *   <li>Checkpoint {@code k} (1-based) completes {@code k·(λ+γ)} seconds after compute start.</li>
 *   <li>A failure {@code e} seconds after compute start has committed
 *       {@code min(n, floor(e / (λ+γ)))} checkpoints; each saves {@code λ·mips} MI.</li>
 * </ul>
 *
 * <p>Rounding uses a {@code 1e-9} tolerance so that exact decimal inputs (for example 3000 MI at
 * 1000 MIPS with {@code λ = 1}) are not perturbed by binary64 representation error.
 */
public final class CrchCheckpointModel {

    static final double TOLERANCE = 1.0e-9;

    private CrchCheckpointModel() {}

    /**
     * Returns the number of checkpoints an attempt of {@code workMi} takes.
     *
     * @param workMi positive work this attempt computes
     * @param mips positive finite execution speed
     * @param intervalSeconds positive finite checkpoint interval {@code λ}
     * @return {@code max(0, ceil(T/λ) - 1)} with {@code T = workMi / mips}
     */
    public static long checkpointCount(long workMi, double mips, double intervalSeconds) {
        requirePositive(workMi, mips);
        if (!(intervalSeconds > 0.0) || !Double.isFinite(intervalSeconds)) {
            throw new IllegalArgumentException("Checkpoint interval must be positive and finite");
        }
        double intervals = (workMi / mips) / intervalSeconds;
        return Math.max(0L, (long) Math.ceil(intervals - TOLERANCE) - 1L);
    }

    /**
     * Returns the MI one checkpointed attempt executes: its work plus the overhead of every checkpoint.
     *
     * @param workMi positive work this attempt computes
     * @param mips positive finite execution speed
     * @param config checkpoint interval and overhead
     * @return {@code workMi + ceil(n·γ·mips)}
     */
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

    /**
     * Returns the checkpoints committed before a failure.
     *
     * @param elapsedComputeSeconds non-negative seconds between compute start and the failure
     * @param plannedCheckpoints checkpoints the attempt would take without a failure
     * @param config checkpoint interval and overhead
     * @return {@code min(plannedCheckpoints, floor(e / (λ+γ)))}
     */
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
