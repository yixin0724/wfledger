package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.workflowsim.CondorVM;
import org.workflowsim.Task;

public final class LocalPeftPlanningAlgorithm extends AbstractLocalCommPlanningAlgorithm {

    private final Map<Task, Map<CondorVM, Double>> octByTaskVm = new HashMap<Task, Map<CondorVM, Double>>();

    public LocalPeftPlanningAlgorithm(PlanningContext context) {
        super("LOCAL_PEFT", context);
    }

    @Override
    void resetPreparedCaches() {
        octByTaskVm.clear();
    }

    @Override
    public void run() {
        List<Task> tasks = prepare();
        for (Task task : tasks) {
            octPerVm(task);
        }
        List<Task> priority = new ArrayList<Task>(tasks);
        Collections.sort(priority, new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                int byOct = Double.compare(priorityOf(second), priorityOf(first));
                return byOct != 0 ? byOct : Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        double stageInFinish = stageInFinishTime();

        List<Task> remaining = new ArrayList<Task>(priority);
        Set<Task> allocated = new HashSet<Task>();
        while (!remaining.isEmpty()) {
            Task next = null;
            for (Task candidate : remaining) {
                if (allocated.containsAll(candidate.getParentList())) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {

                throw new IllegalStateException(
                        label() + " ready list exhausted with " + remaining.size() + " unallocated tasks");
            }
            remaining.remove(next);
            allocated.add(next);
            allocateOptimistic(next, octByTaskVm.get(next), stageInFinish);
        }
    }

    private Map<CondorVM, Double> octPerVm(Task task) {
        Map<CondorVM, Double> cached = octByTaskVm.get(task);
        if (cached != null) {
            return cached;
        }
        List<Task> order = tasksInTopologicalOrder();
        for (int index = order.size() - 1; index >= 0; index--) {
            Task current = order.get(index);
            if (!octByTaskVm.containsKey(current)) {
                computeOctPerVm(current);
            }
        }
        return octByTaskVm.get(task);
    }

    private Map<CondorVM, Double> computeOctPerVm(Task task) {
        Map<CondorVM, Double> perVm = new LinkedHashMap<CondorVM, Double>();

        Map<Task, Double> edgeBytes = new HashMap<Task, Double>();
        for (CondorVM vm : vms()) {
            if (!isCompatible(task, vm)) {
                perVm.put(vm, Double.valueOf(Double.POSITIVE_INFINITY));
                continue;
            }
            double oct;
            if (task.getChildList().isEmpty()) {

                oct = 0.0;
            } else {
                double worstChild = 0.0;
                for (Task child : task.getChildList()) {
                    Map<CondorVM, Double> childOct = octByTaskVm.get(child);
                    double bestPlacement = Double.POSITIVE_INFINITY;
                    for (CondorVM childVm : vms()) {
                        if (!isCompatible(child, childVm)) {
                            continue;
                        }
                        double candidate = childOct.get(childVm).doubleValue()
                                + computeSecondsOn(child, childVm)
                                + communicationSeconds(task, child, vm, childVm, edgeBytes);
                        if (candidate < bestPlacement) {
                            bestPlacement = candidate;
                        }
                    }
                    if (bestPlacement > worstChild) {
                        worstChild = bestPlacement;
                    }
                }
                oct = worstChild;
            }
            perVm.put(vm, Double.valueOf(oct));
        }
        octByTaskVm.put(task, perVm);
        return perVm;
    }

    final double priorityOf(Task task) {
        Map<CondorVM, Double> perVm = octPerVm(task);
        double total = 0.0;
        int count = 0;
        for (Map.Entry<CondorVM, Double> entry : perVm.entrySet()) {
            if (isCompatible(task, entry.getKey())) {
                total += entry.getValue().doubleValue();
                count++;
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException(
                    label() + " cannot rank task " + task.getCloudletId() + "; no compatible VM");
        }
        return total / count;
    }

    final double optimisticCostOf(Task task, CondorVM vm) {
        Map<CondorVM, Double> perVm = octByTaskVm.get(task);
        if (perVm == null) {
            throw new IllegalStateException(label() + " has no OCT before run(); task " + task.getCloudletId());
        }
        Double value = perVm.get(vm);
        if (value == null) {
            throw new IllegalStateException(
                    label() + " has no OCT for task " + task.getCloudletId() + " on VM " + vm.getId());
        }
        return value.doubleValue();
    }
}
