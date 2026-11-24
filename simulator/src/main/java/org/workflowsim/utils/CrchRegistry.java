package org.workflowsim.utils;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Session state of the CRCH synchronized-checkpoint model.
 *
 * <p>FGCS-R5 (2026-11-24): root cause - a checkpoint committed when {@code interval + overhead} fit
 * after the previous interval, but the overhead was never part of the attempt's executed length, and
 * the saved work was credited on whichever VM the retry used. Checkpoint arithmetic now lives in
 * {@link CrchCheckpointModel}, the overhead is charged to every CRCH attempt by the datacenter, and a
 * recovery keeps the checkpointing VM so that only a resumption on that VM skips the saved work.
 */
public final class CrchRegistry {
    private static CrchConfig config;
    private static final Map<Integer, CrchRecovery> RECOVERIES = new TreeMap<Integer, CrchRecovery>();
    private static final Map<Integer, Long> CHECKPOINTS_BY_TASK = new TreeMap<Integer, Long>();
    private static final Map<Integer, Double> VM_MIPS = new TreeMap<Integer, Double>();

    private CrchRegistry() {}

    public static synchronized void configure(CrchConfig value) {
        if (value == null) {
            throw new IllegalArgumentException("CRCH configuration cannot be null");
        }
        config = value;
        RECOVERIES.clear();
        CHECKPOINTS_BY_TASK.clear();
        VM_MIPS.clear();
    }

    public static synchronized CrchConfig get() {
        if (config == null) {
            throw new IllegalStateException("CRCH requires an explicit checkpoint configuration");
        }
        return config;
    }

    /** @return whether this session runs the CRCH checkpoint model */
    public static synchronized boolean isConfigured() {
        return config != null;
    }

    public static synchronized void registerVmSpeed(int vmId, double mips) {
        if (vmId < 0 || !(mips > 0.0) || !Double.isFinite(mips)) {
            throw new IllegalArgumentException("Invalid CRCH VM speed");
        }
        VM_MIPS.put(vmId, mips);
    }

    public static synchronized double getVmMips(int vmId) {
        Double value = VM_MIPS.get(vmId);
        if (value == null) {
            throw new IllegalArgumentException("No CRCH speed registered for VM " + vmId);
        }
        return value;
    }

    /**
     * Records the checkpoints a failed attempt committed and returns the resulting recovery state.
     *
     * @param taskId logical task of the failed attempt
     * @param failedVmId VM that ran the attempt and holds its checkpoints
     * @param creditedWorkMi saved work the attempt had already skipped on this VM
     * @param attemptWorkMi work the attempt itself had to compute, excluding checkpoint overhead
     * @param plannedCheckpoints checkpoints the attempt would have taken without a failure
     * @param elapsedComputeSeconds seconds between the attempt's compute start and the failure
     * @param vmMips execution speed of the failed VM
     * @return the recovery; its saved work is {@code credited + committed·λ·mips}
     */
    public static synchronized CrchRecovery recordFailure(
            int taskId,
            int failedVmId,
            long creditedWorkMi,
            long attemptWorkMi,
            long plannedCheckpoints,
            double elapsedComputeSeconds,
            double vmMips) {
        CrchConfig active = get();
        if (!(vmMips > 0.0) || !Double.isFinite(vmMips) || attemptWorkMi <= 0L || creditedWorkMi < 0L) {
            throw new IllegalArgumentException("CRCH requires positive work and VM speed");
        }
        long committed = CrchCheckpointModel.committedCheckpoints(elapsedComputeSeconds, plannedCheckpoints, active);
        double saved = committed * active.getCheckpointIntervalSeconds() * vmMips;
        long newlySavedMi = (long) Math.floor(saved + CrchCheckpointModel.TOLERANCE);
        if (newlySavedMi >= attemptWorkMi) {
            throw new IllegalStateException("Committed checkpoints of task " + taskId + " cover its whole attempt ("
                    + newlySavedMi + " of " + attemptWorkMi + " MI): a checkpoint is never taken at completion");
        }
        CrchRecovery recovery = new CrchRecovery(
                Math.addExact(creditedWorkMi, attemptWorkMi),
                Math.addExact(creditedWorkMi, newlySavedMi),
                committed,
                failedVmId);
        CHECKPOINTS_BY_TASK.put(taskId, committed);
        return recovery;
    }

    public static synchronized void registerRetry(int retryJobId, CrchRecovery recovery) {
        if (retryJobId < 0 || recovery == null) {
            throw new IllegalArgumentException("Invalid CRCH retry registration");
        }
        RECOVERIES.put(retryJobId, recovery);
    }

    public static synchronized CrchRecovery getRecovery(int retryJobId) {
        CrchRecovery recovery = RECOVERIES.get(retryJobId);
        if (recovery == null) {
            throw new IllegalArgumentException("No CRCH recovery for retry Job " + retryJobId);
        }
        return recovery;
    }

    public static synchronized boolean hasRecovery(int retryJobId) {
        return RECOVERIES.containsKey(retryJobId);
    }

    public static synchronized long getCheckpointCount(int taskId) {
        return CHECKPOINTS_BY_TASK.getOrDefault(taskId, 0L);
    }

    public static synchronized Map<Integer, CrchRecovery> snapshot() {
        return Collections.unmodifiableMap(new TreeMap<Integer, CrchRecovery>(RECOVERIES));
    }

    public static synchronized void clear() {
        config = null;
        RECOVERIES.clear();
        CHECKPOINTS_BY_TASK.clear();
        VM_MIPS.clear();
    }
}
