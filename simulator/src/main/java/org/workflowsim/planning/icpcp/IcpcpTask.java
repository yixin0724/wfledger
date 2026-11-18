package org.workflowsim.planning.icpcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class IcpcpTask {
    private final int taskId;
    private final Map<Integer, Double> computeSecondsByVm;
    private final List<Integer> parentTaskIds;
    private final Map<Integer, Double> communicationSecondsByChild;

    public IcpcpTask(
            int taskId,
            Map<Integer, Double> computeSecondsByVm,
            List<Integer> parentTaskIds,
            Map<Integer, Double> communicationSecondsByChild) {
        if (taskId < 0
                || computeSecondsByVm == null
                || computeSecondsByVm.isEmpty()
                || parentTaskIds == null
                || communicationSecondsByChild == null) {
            throw new IllegalArgumentException("Invalid IC-PCP task declaration");
        }
        for (Double seconds : computeSecondsByVm.values()) {
            if (!(seconds > 0.0) || !Double.isFinite(seconds)) {
                throw new IllegalArgumentException("IC-PCP compute times must be positive and finite");
            }
        }
        for (Double seconds : communicationSecondsByChild.values()) {
            if (seconds < 0.0 || !Double.isFinite(seconds)) {
                throw new IllegalArgumentException("IC-PCP communication times must be finite and non-negative");
            }
        }
        this.taskId = taskId;
        this.computeSecondsByVm = Collections.unmodifiableMap(computeSecondsByVm);
        this.parentTaskIds = Collections.unmodifiableList(new ArrayList<Integer>(parentTaskIds));
        this.communicationSecondsByChild = Collections.unmodifiableMap(communicationSecondsByChild);
    }

    public int getTaskId() {
        return taskId;
    }

    public Map<Integer, Double> getComputeSecondsByVm() {
        return computeSecondsByVm;
    }

    public List<Integer> getParentTaskIds() {
        return parentTaskIds;
    }

    public Map<Integer, Double> getCommunicationSecondsByChild() {
        return communicationSecondsByChild;
    }
}
