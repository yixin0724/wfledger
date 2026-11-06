package org.workflowsim.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Historical average task runtimes used by Reshi-M, with explicit missing-value semantics. */
public final class ReshiTaskHistory {

    private final Map<String, Double> averageRuntimeSecondsByTaskType;

    public ReshiTaskHistory(Map<String, Double> averageRuntimeSecondsByTaskType) {
        if (averageRuntimeSecondsByTaskType == null || averageRuntimeSecondsByTaskType.isEmpty()) {
            throw new IllegalArgumentException("Reshi-M requires historical task runtimes");
        }
        for (Map.Entry<String, Double> entry : averageRuntimeSecondsByTaskType.entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey().isEmpty()
                    || entry.getValue() == null
                    || !(entry.getValue() > 0.0)
                    || !Double.isFinite(entry.getValue())) {
                throw new IllegalArgumentException("Invalid Reshi historical runtime");
            }
        }
        this.averageRuntimeSecondsByTaskType =
                Collections.unmodifiableMap(new LinkedHashMap<String, Double>(averageRuntimeSecondsByTaskType));
    }

    public double averageRuntimeFor(String taskType) {
        Double value = averageRuntimeSecondsByTaskType.get(taskType);
        if (value == null) {
            throw new IllegalArgumentException("No Reshi historical runtime for task type " + taskType);
        }
        return value.doubleValue();
    }
}
