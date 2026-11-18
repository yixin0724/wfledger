package org.workflowsim.utils;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class EbpsmConfig {
    private final Map<Integer, Double> taskBudgets;
    private final Map<Integer, Set<String>> cachedInputsByVm;
    private final Map<Integer, Set<String>> containerImagesByVm;
    private final Map<Integer, String> containerImageByTask;
    private final Map<Integer, Double> vmCostPerSecondByVm;
    private final double globalReadRateBytesPerSecond;
    private final double globalWriteRateBytesPerSecond;
    private final double containerProvisioningDelaySeconds;

    public EbpsmConfig(
            Map<Integer, Double> taskBudgets,
            Map<Integer, Set<String>> cachedInputsByVm,
            Map<Integer, Set<String>> containerImagesByVm,
            Map<Integer, String> containerImageByTask,
            Map<Integer, Double> vmCostPerSecondByVm,
            double globalReadRateBytesPerSecond,
            double globalWriteRateBytesPerSecond,
            double containerProvisioningDelaySeconds) {
        if (taskBudgets == null
                || taskBudgets.isEmpty()
                || cachedInputsByVm == null
                || containerImagesByVm == null
                || containerImageByTask == null
                || vmCostPerSecondByVm == null
                || !(globalReadRateBytesPerSecond > 0.0)
                || !(globalWriteRateBytesPerSecond > 0.0)
                || containerProvisioningDelaySeconds < 0.0) {
            throw new IllegalArgumentException("Invalid EBPSM configuration");
        }
        this.taskBudgets = Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(taskBudgets));
        this.cachedInputsByVm = Collections.unmodifiableMap(copySets(cachedInputsByVm));
        this.containerImagesByVm = Collections.unmodifiableMap(copySets(containerImagesByVm));
        this.containerImageByTask =
                Collections.unmodifiableMap(new LinkedHashMap<Integer, String>(containerImageByTask));
        this.vmCostPerSecondByVm = Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(vmCostPerSecondByVm));
        this.globalReadRateBytesPerSecond = globalReadRateBytesPerSecond;
        this.globalWriteRateBytesPerSecond = globalWriteRateBytesPerSecond;
        this.containerProvisioningDelaySeconds = containerProvisioningDelaySeconds;
    }

    private static Map<Integer, Set<String>> copySets(Map<Integer, Set<String>> source) {
        Map<Integer, Set<String>> copy = new LinkedHashMap<Integer, Set<String>>();
        for (Map.Entry<Integer, Set<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableSet(new HashSet<String>(entry.getValue())));
        }
        return copy;
    }

    public double getTaskBudget(int taskId) {
        Double value = taskBudgets.get(taskId);
        if (value == null) {
            throw new IllegalArgumentException("No EBPSM budget for task " + taskId);
        }
        return value;
    }

    public Set<String> getCachedInputs(int vmId) {
        return cachedInputsByVm.getOrDefault(vmId, Collections.<String>emptySet());
    }

    public Set<String> getContainerImages(int vmId) {
        return containerImagesByVm.getOrDefault(vmId, Collections.<String>emptySet());
    }

    public String getContainerImage(int taskId) {
        return containerImageByTask.getOrDefault(taskId, "default");
    }

    public double getVmCostPerSecond(int vmId) {
        Double value = vmCostPerSecondByVm.get(vmId);
        if (value == null) {
            throw new IllegalArgumentException("No EBPSM VM cost for VM " + vmId);
        }
        return value;
    }

    public double getGlobalReadRateBytesPerSecond() {
        return globalReadRateBytesPerSecond;
    }

    public double getGlobalWriteRateBytesPerSecond() {
        return globalWriteRateBytesPerSecond;
    }

    public double getContainerProvisioningDelaySeconds() {
        return containerProvisioningDelaySeconds;
    }
}
