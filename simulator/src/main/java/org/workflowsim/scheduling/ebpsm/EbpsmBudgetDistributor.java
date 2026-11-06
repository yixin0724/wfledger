package org.workflowsim.scheduling.ebpsm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Slowest-first task-based budget distribution (SFTD) used by EBPSM. */
public final class EbpsmBudgetDistributor {

    public Map<Integer, Double> distribute(List<EbpsmTask> tasks, List<EbpsmVmType> vmTypes, double budget) {
        if (tasks == null || tasks.isEmpty() || vmTypes == null || vmTypes.isEmpty() || !(budget > 0.0)) {
            throw new IllegalArgumentException("EBPSM requires tasks, VM types, and a positive budget");
        }
        List<EbpsmVmType> types = new ArrayList<EbpsmVmType>(vmTypes);
        types.sort(Comparator.comparingDouble(EbpsmVmType::getMips));
        List<EbpsmTask> ordered = order(tasks, types.get(0));
        Map<Integer, Integer> chosenType = new HashMap<Integer, Integer>();
        Map<Integer, Double> budgets = new LinkedHashMap<Integer, Double>();
        double remaining = budget;
        for (EbpsmTask task : ordered) {
            EbpsmVmType cheapest = types.get(0);
            double cost = cost(task, cheapest);
            budgets.put(task.getTaskId(), cost);
            chosenType.put(task.getTaskId(), cheapest.getVmTypeId());
            remaining -= cost;
        }
        for (EbpsmTask task : ordered) {
            int currentType = chosenType.get(task.getTaskId());
            for (EbpsmVmType candidate : types) {
                if (candidate.getVmTypeId() == currentType) {
                    continue;
                }
                double oldCost = cost(task, findType(types, currentType));
                double newCost = cost(task, candidate);
                if (newCost > oldCost && newCost - oldCost <= remaining + 1.0e-9) {
                    budgets.put(task.getTaskId(), newCost);
                    chosenType.put(task.getTaskId(), candidate.getVmTypeId());
                    currentType = candidate.getVmTypeId();
                    remaining -= newCost - oldCost;
                }
            }
        }
        return budgets;
    }

    public double updateRemainingBudget(double allocatedBudget, double actualCost, double spareBudget) {
        if (allocatedBudget < 0.0
                || actualCost < 0.0
                || !Double.isFinite(allocatedBudget)
                || !Double.isFinite(actualCost)
                || !Double.isFinite(spareBudget)) {
            throw new IllegalArgumentException("Invalid EBPSM budget update");
        }
        if (actualCost <= allocatedBudget + spareBudget) {
            return allocatedBudget + spareBudget - actualCost;
        }
        return -(actualCost - allocatedBudget - spareBudget);
    }

    private static List<EbpsmTask> order(List<EbpsmTask> tasks, EbpsmVmType reference) {
        Map<Integer, Integer> levels = new HashMap<Integer, Integer>();
        Map<Integer, Double> eft = new HashMap<Integer, Double>();
        Map<Integer, EbpsmTask> byId = new HashMap<Integer, EbpsmTask>();
        for (EbpsmTask task : tasks) {
            byId.put(task.getTaskId(), task);
        }
        List<EbpsmTask> ordered = new ArrayList<EbpsmTask>(tasks);
        ordered.sort(Comparator.comparingInt(EbpsmTask::getTaskId));
        for (EbpsmTask task : ordered) {
            int level = 0;
            double finish = task.getProcessingSecondsByVmType().get(reference.getVmTypeId());
            for (Integer parent : task.getParentTaskIds()) {
                EbpsmTask parentTask = byId.get(parent);
                if (parentTask == null) {
                    throw new IllegalArgumentException("Unknown EBPSM parent " + parent);
                }
                level = Math.max(level, levels.get(parent) + 1);
                finish = Math.max(
                        finish,
                        eft.get(parent) + task.getProcessingSecondsByVmType().get(reference.getVmTypeId()));
            }
            levels.put(task.getTaskId(), level);
            eft.put(task.getTaskId(), finish);
        }
        ordered.sort((first, second) -> {
            int byLevel = Integer.compare(levels.get(first.getTaskId()), levels.get(second.getTaskId()));
            if (byLevel != 0) {
                return byLevel;
            }
            int byFinish = Double.compare(eft.get(first.getTaskId()), eft.get(second.getTaskId()));
            return byFinish != 0 ? byFinish : Integer.compare(first.getTaskId(), second.getTaskId());
        });
        return ordered;
    }

    private static double cost(EbpsmTask task, EbpsmVmType type) {
        double seconds = type.getProvisioningDelaySeconds()
                + task.getProcessingSecondsByVmType().get(type.getVmTypeId());
        return Math.ceil(seconds) * type.getCostPerSecond();
    }

    private static EbpsmVmType findType(List<EbpsmVmType> types, int id) {
        for (EbpsmVmType type : types) {
            if (type.getVmTypeId() == id) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown EBPSM VM type " + id);
    }
}
