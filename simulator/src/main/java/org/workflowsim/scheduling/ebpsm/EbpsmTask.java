package org.workflowsim.scheduling.ebpsm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** One task in the EBPSM budget-distribution preprocessor. */
public final class EbpsmTask {
    private final int taskId;
    private final List<Integer> parentTaskIds;
    private final Map<Integer, Double> processingSecondsByVmType;

    public EbpsmTask(int taskId, List<Integer> parentTaskIds, Map<Integer, Double> processingSecondsByVmType) {
        if (taskId < 0
                || parentTaskIds == null
                || processingSecondsByVmType == null
                || processingSecondsByVmType.isEmpty()) {
            throw new IllegalArgumentException("Invalid EBPSM task");
        }
        for (Double seconds : processingSecondsByVmType.values()) {
            if (!(seconds > 0.0) || !Double.isFinite(seconds)) {
                throw new IllegalArgumentException("EBPSM processing times must be positive and finite");
            }
        }
        this.taskId = taskId;
        this.parentTaskIds = Collections.unmodifiableList(new ArrayList<Integer>(parentTaskIds));
        this.processingSecondsByVmType = Collections.unmodifiableMap(processingSecondsByVmType);
    }

    public int getTaskId() {
        return taskId;
    }

    public List<Integer> getParentTaskIds() {
        return parentTaskIds;
    }

    public Map<Integer, Double> getProcessingSecondsByVmType() {
        return processingSecondsByVmType;
    }
}
