package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.workflowsim.CondorVM;
import org.workflowsim.Task;

public final class LocalCpopPlanningAlgorithm extends AbstractLocalCommPlanningAlgorithm {

    private static final double RANK_TOLERANCE = 1.0e-9;

    private final Map<Task, Double> downwardRanks = new HashMap<Task, Double>();

    private List<Integer> criticalPathTaskIds = Collections.emptyList();
    private Integer criticalProcessorVmId;

    public LocalCpopPlanningAlgorithm(PlanningContext context) {
        super("LOCAL_CPOP", context);
    }

    @Override
    public void run() {
        downwardRanks.clear();
        criticalPathTaskIds = Collections.emptyList();
        criticalProcessorVmId = null;
        List<Task> tasks = prepare();
        for (Task task : tasksInTopologicalOrder()) {
            downwardRank(task);
        }

        LinkedHashSet<Task> criticalPath = walkCriticalPath(tasks);
        CondorVM cpVm = criticalPathVm(criticalPath);
        List<Integer> pathIds = new ArrayList<Integer>();
        for (Task task : criticalPath) {
            pathIds.add(Integer.valueOf(task.getCloudletId()));
        }
        criticalPathTaskIds = Collections.unmodifiableList(pathIds);
        criticalProcessorVmId = Integer.valueOf(cpVm.getId());

        double stageInFinish = stageInFinishTime();
        List<Task> pending = new ArrayList<Task>(tasks);
        int scheduled = 0;
        while (scheduled < tasks.size()) {
            Task next = null;
            for (Task task : pending) {
                if (task == null || !isReady(task)) {
                    continue;
                }
                if (next == null
                        || priorityOf(task) > priorityOf(next)
                        || (Double.compare(priorityOf(task), priorityOf(next)) == 0
                                && task.getCloudletId() < next.getCloudletId())) {
                    next = task;
                }
            }
            if (next == null) {
                throw new IllegalStateException(
                        "LOCAL_CPOP found no ready task; the DAG validator should have " + "rejected cyclic workflows");
            }
            allocate(next, criticalPath.contains(next) ? cpVm : null, stageInFinish);
            pending.set(pending.indexOf(next), null);
            scheduled++;
        }
    }

    private boolean isReady(Task task) {
        if (plannedFinishOf(task) != null) {
            return false;
        }
        for (Task parent : task.getParentList()) {
            if (plannedFinishOf(parent) == null) {
                return false;
            }
        }
        return true;
    }

    double downwardRankOf(Task task) {
        return downwardRanks.get(task).doubleValue();
    }

    double priorityOf(Task task) {
        return upwardRankOf(task) + downwardRankOf(task);
    }

    List<Integer> getCriticalPathTaskIds() {
        return criticalPathTaskIds;
    }

    Integer getCriticalProcessorVmId() {
        return criticalProcessorVmId;
    }

    private double downwardRank(Task task) {
        Double cached = downwardRanks.get(task);
        if (cached != null) {
            return cached.doubleValue();
        }
        double rank = 0.0;
        for (Task parent : task.getParentList()) {
            double via = downwardRanks.get(parent).doubleValue()
                    + meanComputeSeconds(parent)
                    + meanCommunicationSeconds(parent, task);
            if (via > rank) {
                rank = via;
            }
        }
        downwardRanks.put(task, Double.valueOf(rank));
        return rank;
    }

    private LinkedHashSet<Task> walkCriticalPath(List<Task> tasks) {
        Task entry = highestPriorityEntry(tasks);
        double criticalPathLength = priorityOf(entry);
        LinkedHashSet<Task> criticalPath = new LinkedHashSet<Task>();
        Task current = entry;
        criticalPath.add(current);
        while (!current.getChildList().isEmpty()) {
            Task next = null;
            for (Task child : current.getChildList()) {

                double via =
                        meanComputeSeconds(current) + meanCommunicationSeconds(current, child) + upwardRankOf(child);
                if (!sameRank(priorityOf(child), criticalPathLength) || !sameRank(upwardRankOf(current), via)) {
                    continue;
                }
                if (next == null || child.getCloudletId() < next.getCloudletId()) {
                    next = child;
                }
            }
            if (next == null) {
                throw new IllegalStateException(
                        "LOCAL_CPOP could not continue its critical path " + "from task " + current.getCloudletId());
            }
            if (!criticalPath.add(next)) {
                throw new IllegalStateException("LOCAL_CPOP critical path revisited task " + next.getCloudletId()
                        + "; the DAG validator should have rejected cycles");
            }
            current = next;
        }
        return criticalPath;
    }

    private static boolean sameRank(double first, double second) {
        double scale = Math.max(1.0, Math.max(Math.abs(first), Math.abs(second)));
        return Math.abs(first - second) <= RANK_TOLERANCE * scale;
    }

    private Task highestPriorityEntry(List<Task> tasks) {
        Task entry = null;
        for (Task task : tasks) {
            if (!task.getParentList().isEmpty()) {
                continue;
            }
            if (entry == null
                    || priorityOf(task) > priorityOf(entry)
                    || (Double.compare(priorityOf(task), priorityOf(entry)) == 0
                            && task.getCloudletId() < entry.getCloudletId())) {
                entry = task;
            }
        }
        if (entry == null) {
            throw new IllegalStateException("LOCAL_CPOP requires at least one entry task; "
                    + "the DAG validator should have rejected cyclic workflows");
        }
        return entry;
    }

    private CondorVM criticalPathVm(LinkedHashSet<Task> criticalPath) {
        CondorVM best = null;
        double bestTotal = Double.POSITIVE_INFINITY;
        for (CondorVM vm : vms()) {
            double total = 0.0;
            for (Task task : criticalPath) {
                if (!isCompatible(task, vm)) {
                    total = Double.POSITIVE_INFINITY;
                    break;
                }
                total += computeSecondsOn(task, vm);
            }
            if (total < bestTotal) {
                best = vm;
                bestTotal = total;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException(
                    "LOCAL_CPOP cannot select one compatible VM " + "for every task on its critical path");
        }
        return best;
    }
}
