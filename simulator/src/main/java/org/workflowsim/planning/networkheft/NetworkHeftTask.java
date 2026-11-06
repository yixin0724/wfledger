package org.workflowsim.planning.networkheft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Immutable Network-Aware HEFT task. */
public final class NetworkHeftTask {
    private final int taskId;
    private final Map<Integer, Double> computeSecondsByVm;
    private final List<Integer> parentTaskIds;
    private final Map<Integer, NetworkHeftEdge> edgesByChild;

    public NetworkHeftTask(
            int taskId,
            Map<Integer, Double> computeSecondsByVm,
            List<Integer> parentTaskIds,
            Map<Integer, NetworkHeftEdge> edgesByChild) {
        if (taskId < 0
                || computeSecondsByVm == null
                || computeSecondsByVm.isEmpty()
                || parentTaskIds == null
                || edgesByChild == null) {
            throw new IllegalArgumentException("Invalid Network-Aware HEFT task");
        }
        for (Double seconds : computeSecondsByVm.values()) {
            if (!(seconds > 0.0) || !Double.isFinite(seconds)) {
                throw new IllegalArgumentException("Compute times must be positive and finite");
            }
        }
        this.taskId = taskId;
        this.computeSecondsByVm = Collections.unmodifiableMap(computeSecondsByVm);
        this.parentTaskIds = Collections.unmodifiableList(new ArrayList<Integer>(parentTaskIds));
        this.edgesByChild = Collections.unmodifiableMap(edgesByChild);
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

    public Map<Integer, NetworkHeftEdge> getEdgesByChild() {
        return edgesByChild;
    }
}
