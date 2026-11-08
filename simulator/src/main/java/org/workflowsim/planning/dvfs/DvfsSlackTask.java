package org.workflowsim.planning.dvfs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One allocated task in the DVFS slack optimizer. */
public final class DvfsSlackTask {
    private final int taskId;
    private final int vmId;
    private final long workMi;
    private final List<Integer> parentTaskIds;

    public DvfsSlackTask(int taskId, int vmId, long workMi, List<Integer> parentTaskIds) {
        if (taskId < 0 || vmId < 0 || workMi <= 0L || parentTaskIds == null) {
            throw new IllegalArgumentException("Invalid DVFS task");
        }
        this.taskId = taskId;
        this.vmId = vmId;
        this.workMi = workMi;
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

    public List<Integer> getParentTaskIds() {
        return parentTaskIds;
    }
}
