package org.workflowsim.planning.cawosched;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable compute-only task in a fixed CaWoSched mapping and per-VM order. */
public final class CaWoSchedTask {

    private final int taskId;
    private final int vmId;
    private final int orderOnVm;
    private final double durationSeconds;
    private final double idleWatts;
    private final double activeWatts;
    private final List<Integer> parentTaskIds;

    public CaWoSchedTask(
            int taskId,
            int vmId,
            int orderOnVm,
            double durationSeconds,
            double idleWatts,
            double activeWatts,
            List<Integer> parentTaskIds) {
        if (taskId < 0 || vmId < 0 || orderOnVm < 0) {
            throw new IllegalArgumentException("CaWoSched task and VM identifiers must be non-negative");
        }
        if (!(durationSeconds > 0.0) || !Double.isFinite(durationSeconds)) {
            throw new IllegalArgumentException("CaWoSched task duration must be finite and positive");
        }
        if (!(idleWatts >= 0.0)
                || !Double.isFinite(idleWatts)
                || !(activeWatts >= idleWatts)
                || !Double.isFinite(activeWatts)) {
            throw new IllegalArgumentException("CaWoSched power values must satisfy 0 <= idle <= active");
        }
        if (parentTaskIds == null) {
            throw new IllegalArgumentException("CaWoSched parent identifiers cannot be null");
        }
        this.taskId = taskId;
        this.vmId = vmId;
        this.orderOnVm = orderOnVm;
        this.durationSeconds = durationSeconds;
        this.idleWatts = idleWatts;
        this.activeWatts = activeWatts;
        this.parentTaskIds = Collections.unmodifiableList(new ArrayList<Integer>(parentTaskIds));
    }

    public int getTaskId() {
        return taskId;
    }

    public int getVmId() {
        return vmId;
    }

    public int getOrderOnVm() {
        return orderOnVm;
    }

    public double getDurationSeconds() {
        return durationSeconds;
    }

    public double getIdleWatts() {
        return idleWatts;
    }

    public double getActiveWatts() {
        return activeWatts;
    }

    public double getDynamicWatts() {
        return activeWatts - idleWatts;
    }

    public List<Integer> getParentTaskIds() {
        return parentTaskIds;
    }
}
