package org.workflowsim.utils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class CrchRegistry {
    private static CrchConfig config;
    private static final Map<Integer, CrchRecovery> RECOVERIES = new HashMap<Integer, CrchRecovery>();
    private static final Map<Integer, Long> CHECKPOINTS_BY_TASK = new HashMap<Integer, Long>();
    private static final Map<Integer, Double> VM_MIPS = new HashMap<Integer, Double>();

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

    public static synchronized CrchRecovery recordFailure(
            int taskId,
            int failedVmId,
            double executionStartSeconds,
            double failureTimeSeconds,
            long workMi,
            double vmMips) {
        CrchConfig active = get();
        if (!(vmMips > 0.0) || workMi <= 0L) {
            throw new IllegalArgumentException("CRCH requires positive work and VM speed");
        }
        double elapsed = Math.max(0.0, failureTimeSeconds - executionStartSeconds);
        long checkpoints = 0L;
        double checkpointedWorkSeconds = 0.0;
        while (true) {
            double candidate = checkpointedWorkSeconds
                    + active.getCheckpointIntervalSeconds()
                    + active.getCheckpointOverheadSeconds();
            if (candidate > elapsed + 1.0e-9) {
                break;
            }
            checkpointedWorkSeconds += active.getCheckpointIntervalSeconds();
            checkpoints++;
        }
        long savedMi = Math.min(workMi, (long) Math.floor(checkpointedWorkSeconds * vmMips + 1.0e-9));
        long remaining = Math.max(0L, workMi - savedMi);
        CrchRecovery recovery = new CrchRecovery(remaining, checkpoints, failedVmId);
        CHECKPOINTS_BY_TASK.put(taskId, checkpoints);
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
        return Collections.unmodifiableMap(new HashMap<Integer, CrchRecovery>(RECOVERIES));
    }

    public static synchronized void clear() {
        config = null;
        RECOVERIES.clear();
        CHECKPOINTS_BY_TASK.clear();
        VM_MIPS.clear();
    }
}
