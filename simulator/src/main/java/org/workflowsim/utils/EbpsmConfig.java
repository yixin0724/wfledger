package org.workflowsim.utils;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Declared inputs of the EBPSM policy. */
public final class EbpsmConfig {
    private final Map<Integer, Double> workflowBudgets;
    private final Map<Integer, Set<String>> cachedInputsByVm;
    private final Map<Integer, Set<String>> containerImagesByVm;
    private final Map<Integer, String> containerImageByTask;
    private final Map<Integer, Double> vmCostPerSecondByVm;
    private final double globalReadRateBytesPerSecond;
    private final double globalWriteRateBytesPerSecond;
    private final double containerProvisioningDelaySeconds;

    public static EbpsmConfig forWorkflowBudgets(
            Map<Integer, Double> workflowBudgets,
            Map<Integer, Set<String>> cachedInputsByVm,
            Map<Integer, Set<String>> containerImagesByVm,
            Map<Integer, String> containerImageByTask,
            Map<Integer, Double> vmCostPerSecondByVm,
            double globalReadRateBytesPerSecond,
            double globalWriteRateBytesPerSecond,
            double containerProvisioningDelaySeconds) {
        return new EbpsmConfig(
                workflowBudgets,
                cachedInputsByVm,
                containerImagesByVm,
                containerImageByTask,
                vmCostPerSecondByVm,
                globalReadRateBytesPerSecond,
                globalWriteRateBytesPerSecond,
                containerProvisioningDelaySeconds);
    }

    private EbpsmConfig(
            Map<Integer, Double> workflowBudgets,
            Map<Integer, Set<String>> cachedInputsByVm,
            Map<Integer, Set<String>> containerImagesByVm,
            Map<Integer, String> containerImageByTask,
            Map<Integer, Double> vmCostPerSecondByVm,
            double globalReadRateBytesPerSecond,
            double globalWriteRateBytesPerSecond,
            double containerProvisioningDelaySeconds) {
        if (workflowBudgets == null
                || workflowBudgets.isEmpty()
                || cachedInputsByVm == null
                || containerImagesByVm == null
                || containerImageByTask == null
                || vmCostPerSecondByVm == null
                || !(globalReadRateBytesPerSecond > 0.0)
                || !(globalWriteRateBytesPerSecond > 0.0)
                || containerProvisioningDelaySeconds < 0.0) {
            throw new IllegalArgumentException("Invalid EBPSM configuration");
        }
        for (Map.Entry<Integer, Double> entry : workflowBudgets.entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey() < 0
                    || entry.getValue() == null
                    || !(entry.getValue() > 0.0)
                    || !Double.isFinite(entry.getValue())) {
                throw new IllegalArgumentException("EBPSM workflow budgets need a non-negative workflow index and a"
                        + " positive finite budget: " + entry.getKey() + "=" + entry.getValue());
            }
        }
        for (Map.Entry<Integer, Double> entry : vmCostPerSecondByVm.entrySet()) {
            if (entry.getValue() == null || !(entry.getValue() >= 0.0) || !Double.isFinite(entry.getValue())) {
                throw new IllegalArgumentException("EBPSM VM " + entry.getKey() + " needs a finite non-negative price");
            }
        }
        this.workflowBudgets = Collections.unmodifiableMap(new TreeMap<Integer, Double>(workflowBudgets));
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

    public Map<Integer, Double> getWorkflowBudgets() {
        return workflowBudgets;
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
