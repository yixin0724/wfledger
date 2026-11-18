package org.workflowsim.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TaskCostMatrix {

    private final Map<Integer, Map<Integer, Double>> costSecondsByTaskAndVm;

    private TaskCostMatrix(Map<Integer, Map<Integer, Double>> source) {
        Map<Integer, Map<Integer, Double>> deepCopy = new LinkedHashMap<Integer, Map<Integer, Double>>();
        for (Map.Entry<Integer, Map<Integer, Double>> entry : source.entrySet()) {
            deepCopy.put(
                    entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(entry.getValue())));
        }
        this.costSecondsByTaskAndVm = Collections.unmodifiableMap(deepCopy);
    }

    public static Builder builder() {
        return new Builder();
    }

    public double getCostSeconds(int taskId, int vmId) {
        Map<Integer, Double> perVm = costSecondsByTaskAndVm.get(Integer.valueOf(taskId));
        if (perVm == null) {
            throw new IllegalArgumentException("Task cost matrix has no entry for task " + taskId);
        }
        Double seconds = perVm.get(Integer.valueOf(vmId));
        if (seconds == null) {
            throw new IllegalArgumentException("Task cost matrix has no entry for task " + taskId + " on VM " + vmId);
        }
        return seconds.doubleValue();
    }

    public java.util.Set<Integer> getTaskIds() {
        return costSecondsByTaskAndVm.keySet();
    }

    public Map<Integer, Double> getCostsForTask(int taskId) {
        Map<Integer, Double> perVm = costSecondsByTaskAndVm.get(Integer.valueOf(taskId));
        if (perVm == null) {
            throw new IllegalArgumentException("Task cost matrix has no entry for task " + taskId);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(perVm));
    }

    public Map<Integer, Map<Integer, Double>> asMap() {
        Map<Integer, Map<Integer, Double>> copy = new LinkedHashMap<Integer, Map<Integer, Double>>();
        for (Map.Entry<Integer, Map<Integer, Double>> entry : costSecondsByTaskAndVm.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    public boolean contains(int taskId, int vmId) {
        Map<Integer, Double> perVm = costSecondsByTaskAndVm.get(Integer.valueOf(taskId));
        return perVm != null && perVm.containsKey(Integer.valueOf(vmId));
    }

    public boolean covers(Iterable<Integer> taskIds, Iterable<Integer> vmIds) {
        for (Integer taskId : taskIds) {
            Map<Integer, Double> perVm = costSecondsByTaskAndVm.get(taskId);
            if (perVm == null) {
                return false;
            }
            for (Integer vmId : vmIds) {
                if (!perVm.containsKey(vmId)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static final class Builder {

        private final Map<Integer, Map<Integer, Double>> entries = new LinkedHashMap<Integer, Map<Integer, Double>>();

        private Builder() {}

        public Builder put(int taskId, int vmId, double costSeconds) {
            if (!(costSeconds > 0.0) || Double.isNaN(costSeconds) || Double.isInfinite(costSeconds)) {
                throw new IllegalArgumentException("Task cost matrix entry must be positive finite seconds: " + "task "
                        + taskId + " on VM " + vmId + " got " + costSeconds);
            }
            Map<Integer, Double> perVm = entries.get(Integer.valueOf(taskId));
            if (perVm == null) {
                perVm = new LinkedHashMap<Integer, Double>();
                entries.put(Integer.valueOf(taskId), perVm);
            }
            if (perVm.put(Integer.valueOf(vmId), Double.valueOf(costSeconds)) != null) {
                throw new IllegalArgumentException(
                        "Duplicate task cost matrix entry for task " + taskId + " on VM " + vmId);
            }
            return this;
        }

        public TaskCostMatrix build() {
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("Task cost matrix must contain at least one entry");
            }
            return new TaskCostMatrix(entries);
        }
    }
}
