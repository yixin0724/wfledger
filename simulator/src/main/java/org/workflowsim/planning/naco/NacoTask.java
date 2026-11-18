package org.workflowsim.planning.naco;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class NacoTask {
    private final int taskId;
    private final double inputBits;
    private final double outputBits;
    private final Map<Integer, Double> computeSecondsByVm;
    private final List<Integer> parentTaskIds;
    private final Map<Integer, Double> edgeBitsByChild;

    public NacoTask(
            int taskId,
            double inputBits,
            double outputBits,
            Map<Integer, Double> computeSecondsByVm,
            List<Integer> parentTaskIds,
            Map<Integer, Double> edgeBitsByChild) {
        if (taskId < 0
                || inputBits < 0
                || outputBits < 0
                || !Double.isFinite(inputBits)
                || !Double.isFinite(outputBits)) {
            throw new IllegalArgumentException("Invalid NACO task input/output identity");
        }
        if (computeSecondsByVm == null
                || computeSecondsByVm.isEmpty()
                || parentTaskIds == null
                || edgeBitsByChild == null) {
            throw new IllegalArgumentException("NACO task requires compute, parent, and edge declarations");
        }
        for (Map.Entry<Integer, Double> entry : computeSecondsByVm.entrySet()) {
            if (!(entry.getValue() > 0.0) || !Double.isFinite(entry.getValue())) {
                throw new IllegalArgumentException("NACO compute times must be positive and finite");
            }
        }
        this.taskId = taskId;
        this.inputBits = inputBits;
        this.outputBits = outputBits;
        this.computeSecondsByVm = Collections.unmodifiableMap(computeSecondsByVm);
        this.parentTaskIds = Collections.unmodifiableList(new ArrayList<Integer>(parentTaskIds));
        this.edgeBitsByChild = Collections.unmodifiableMap(edgeBitsByChild);
    }

    public int getTaskId() {
        return taskId;
    }

    public double getInputBits() {
        return inputBits;
    }

    public double getOutputBits() {
        return outputBits;
    }

    public Map<Integer, Double> getComputeSecondsByVm() {
        return computeSecondsByVm;
    }

    public List<Integer> getParentTaskIds() {
        return parentTaskIds;
    }

    public Map<Integer, Double> getEdgeBitsByChild() {
        return edgeBitsByChild;
    }
}
