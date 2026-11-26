package org.workflowsim.utils;

import org.cloudbus.cloudsim.Consts;
import org.workflowsim.Task;

public final class TaskExecutionModel {

    public static final String EXECUTION_SEMANTICS = "WORK_CONSERVING_TASK_EXECUTION_V3";

    public static final String HISTORICAL_EXECUTION_SEMANTICS_V2 = "WORK_CONSERVING_TASK_EXECUTION_V2";

    public static final long MAX_TOTAL_LENGTH_MI = Long.MAX_VALUE / Consts.MILLION;

    private TaskExecutionModel() {}

    public static long executionLengthMi(Task task, int vmId, double mips) {
        if (task == null || !Double.isFinite(mips) || mips <= 0.0) {
            throw new IllegalArgumentException("Task and positive finite VM MIPS are required");
        }
        if (!task.hasVmExecutionCostSeconds()) {
            return requireRepresentableLength(task.getCloudletLength(), task.getNumberOfPes());
        }
        Double seconds = task.getVmExecutionCostSeconds(vmId);
        if (seconds == null) {
            throw new IllegalArgumentException(
                    "Task " + task.getCloudletId() + " cost matrix has no entry for VM " + vmId);
        }
        double converted = seconds.doubleValue() * mips;
        if (!Double.isFinite(converted) || converted >= (double) Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Task " + task.getCloudletId() + " cost matrix overflows compute MI on VM " + vmId);
        }
        return requireRepresentableLength(Math.round(converted), task.getNumberOfPes());
    }

    public static double executionSeconds(Task task, int vmId, double mips) {
        double seconds = executionLengthMi(task, vmId, mips) / mips;
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            throw new IllegalArgumentException("Task execution seconds must be finite and positive");
        }
        return seconds;
    }

    public static long requireRepresentableLength(long perPeMi, int pes) {
        if (perPeMi <= 0L) {
            throw new IllegalArgumentException("Task work converts to non-positive MI (" + perPeMi + ")");
        }
        if (pes <= 0 || perPeMi > MAX_TOTAL_LENGTH_MI / pes) {
            throw new IllegalArgumentException("Compute work must be positive and representable "
                    + "as signed-long instructions: " + perPeMi + " MI per PE, " + pes + " PEs");
        }
        return perPeMi;
    }
}
