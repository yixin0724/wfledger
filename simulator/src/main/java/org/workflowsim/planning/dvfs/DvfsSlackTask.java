package org.workflowsim.planning.dvfs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One allocated task in the DVFS slack optimizer. */
public final class DvfsSlackTask {
    private final int taskId;
    private final int vmId;
    private final long workMi;
    private final int peCount;
    private final double stageInSeconds;
    private final double earliestStartSeconds;
    private final List<Integer> parentTaskIds;

    public DvfsSlackTask(int taskId, int vmId, long workMi, List<Integer> parentTaskIds) {
        this(taskId, vmId, workMi, 1, 0.0, 0.0, parentTaskIds);
    }

    public DvfsSlackTask(
            int taskId,
            int vmId,
            long workMi,
            int peCount,
            double stageInSeconds,
            double earliestStartSeconds,
            List<Integer> parentTaskIds) {
        if (taskId < 0
                || vmId < 0
                || workMi <= 0L
                || peCount <= 0
                || stageInSeconds < 0.0
                || earliestStartSeconds < 0.0
                || parentTaskIds == null) {
            throw new IllegalArgumentException("Invalid DVFS task");
        }
        this.taskId = taskId;
        this.vmId = vmId;
        this.workMi = workMi;
        this.peCount = peCount;
        this.stageInSeconds = stageInSeconds;
        this.earliestStartSeconds = earliestStartSeconds;
        this.parentTaskIds = Collections.unmodifiableList(new ArrayList<Integer>(parentTaskIds));
    }

    public int getTaskId() {
        return taskId;
    }

    public int getVmId() {
        return vmId;
    }

    public long getWorkMi() {
        return workMi;
    }

    public int getPeCount() {
        return peCount;
    }

    public double getStageInSeconds() {
        return stageInSeconds;
    }

    public double getEarliestStartSeconds() {
        return earliestStartSeconds;
    }

    public List<Integer> getParentTaskIds() {
        return parentTaskIds;
    }
}
