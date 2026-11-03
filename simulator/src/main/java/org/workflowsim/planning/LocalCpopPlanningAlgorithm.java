package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * Communication-aware CPOP static DAG planner aligned with the WorkflowSim controlled
 * LOCAL file-system execution model.
 *
 * <p><b>Algorithm</b> (Critical-Path-on-a-Processor, published alongside HEFT by
 * Topcuoglu, Hariri &amp; Wu, IEEE TPDS 2002):</p>
 * <ol>
 *   <li>priority = upward rank + downward rank: {@code r_u(t) = w_bar_t +
 *       max_child(c_bar + r_u)}, {@code r_d(t) = max_parent(r_d(parent) + w_bar_parent +
 *       c_bar_parent,t)} (entry is 0);</li>
 *   <li>critical path: start from the highest-priority entry task and follow edges that
 *       preserve the critical-path length and satisfy the upward-rank recurrence until
 *       an exit; among several equal-length paths take the smaller task ID;</li>
 *   <li>critical-path processor {@code p_CP} = the VM that minimizes the sum of compute
 *       seconds of the critical-path tasks (ties take the smaller VM ID);</li>
 *   <li>ready-queue scheduling: each step takes the ready task with the largest priority
 *       (ties take the smaller task ID); critical-path tasks bind to {@code p_CP} (still
 *       using the insertion-based earliest-start search), while every other task takes
 *       the minimum insertion-based EFT over all VMs (ties take the smaller VM ID).</li>
 * </ol>
 *
 * <p><b>Communication modeling and execution semantics</b> are shared with
 * {@link LocalHeftPlanningAlgorithm} through {@link AbstractLocalCommPlanningAlgorithm}:
 * a transfer is a pre-execution delay that may overlap the VM busy period; replicas are
 * registered at input-hold completion and at output compute completion, and their
 * visibility is filtered when the dependency becomes ready. The critical processor must
 * be compatible with the whole path; other assignments only consider each task's
 * compatible VMs. The estimate does not replay the full runtime event stream; the
 * applicability preconditions and boundary statements match LOCAL_HEFT.</p>
 */
public final class LocalCpopPlanningAlgorithm extends AbstractLocalCommPlanningAlgorithm {

    private static final double RANK_TOLERANCE = 1.0e-9;

    /** Task -> downward rank. */
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

        // Ready-queue scheduling: among unscheduled tasks whose parents are all scheduled,
        // the one with the largest priority goes first.
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

    /** Already computed downward rank; package-private read-only diagnostic, never triggers a computation. */
    double downwardRankOf(Task task) {
        return downwardRanks.get(task).doubleValue();
    }

    /** Already computed CPOP priority; package-private read-only diagnostic. */
    double priorityOf(Task task) {
        return upwardRankOf(task) + downwardRankOf(task);
    }

    /** Immutable task-ID snapshot of the most recently selected critical path. */
    List<Integer> getCriticalPathTaskIds() {
        return criticalPathTaskIds;
    }

    /** Most recently selected critical processor; null while none has been selected yet. */
    Integer getCriticalProcessorVmId() {
        return criticalProcessorVmId;
    }

    /** Downward rank: max over predecessors of downward rank + compute cost + in-edge
     *         communication cost; entry is 0. */
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

    /** Start from the highest-priority entry task and follow real edges on the longest
     *         path; ties take the smaller task ID. */
    private LinkedHashSet<Task> walkCriticalPath(List<Task> tasks) {
        Task entry = highestPriorityEntry(tasks);
        double criticalPathLength = priorityOf(entry);
        LinkedHashSet<Task> criticalPath = new LinkedHashSet<Task>();
        Task current = entry;
        criticalPath.add(current);
        while (!current.getChildList().isEmpty()) {
            Task next = null;
            for (Task child : current.getChildList()) {
                // The two nodes may lie on different equal-length critical paths; equal priority
                // alone does not prove the edge joining them is also on a critical path, so
                // the upward-rank recurrence must hold as well.
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

    /**
     * 2026-10-26 complexity extraction from walkCriticalPath; statements verbatim; order unchanged.
     * Scope: select only the highest-priority task among all entry tasks (ties take the smaller
     * task ID); throw when there is no entry task.
     */
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

    /** Critical-path processor: the VM minimizing the sum of critical-path task compute
     *         seconds; ties take the smaller VM ID. */
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
