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

/**
 * Communication-aware PEFT static DAG planner aligned with the controlled LOCAL file-system
 * execution model of WorkflowSim.
 *
 * <p><b>Algorithm</b> (Arabnejad &amp; Barbosa, IEEE TPDS 2014, Eq. (7)):
 * {@code OCT(t,p) = max_child min_p'(OCT(child,p') + w(child,p') + c(t,child,p,p'))},
 * with the exit condition {@code OCT(exit,p) = 0}. OCT costs successors first and excludes the
 * computation amount of the current task itself. Each round picks, among the dependency-ready
 * tasks, the one with the highest average OCT over the compatible VMs (rank_o), breaking ties by
 * the smaller Task ID; that task is assigned to the compatible VM minimizing the insertion-based
 * {@code EFT(t,p) + OCT(t,p)}, breaking ties by the smaller VM ID.</p>
 *
 * <p><b>Primary source</b>: chapter 3 of the author's public PhD thesis records
 * DOI 10.1109/TPDS.2013.57 explicitly, with printed p71 Eq. (7) and the zero exit condition,
 * p73 Eq. (9), and Algorithm 1;
 * <a href="https://repositorio-aberto.up.pt/handle/10216/92290">University of Porto author source</a>.
 * The earlier implementation ("own w + successor OCT, mean at the exit") was not the original
 * PEFT; its historical results cannot serve as evidence for reproducing the paper.</p>
 *
 * <p><b>Communication modelling</b> -- the key difference from
 * {@link SharedStoragePeftPlanningAlgorithm} (shared-storage track, no link model, degenerate
 * PEFT with the communication term c = 0): OCT uses the in-model per-VM-pair LOCAL
 * communication cost {@code c(t, child, p, p') = bytes / (1e6 * min(bw_p, bw_p'))}
 * (zero when p = p'), with the byte count taken as the intersection of the parent OUTPUT and the
 * child INPUT. The original models by average link cost; heterogeneous endpoint bandwidth and
 * replica locality are an explicit adaptation of this platform, not the full network model of the
 * original. Stage-in and timestamped replicas use the same estimate as
 * {@link LocalHeftPlanningAlgorithm} through {@link AbstractLocalCommPlanningAlgorithm}; this
 * class implements only OCT, the priority, and the selection rules.</p>
 *
 * <p><b>Preconditions</b>: LOCAL file system, NONE clustering, no failures, no modelled overhead,
 * the {@code preExecutionTransferDelayV1} data-movement model, and SPACE_SHARED VMs; enforced by
 * {@link PlanningContext#validateLocalStaticDag()}. The per-task VM mapping and planned start time
 * written by the planner are enforced by {@code StaticSchedulePlan} as the runtime per-VM dispatch
 * order.</p>
 *
 * <p><b>Boundary statement</b>: OCT is a pre-scheduling static quantity -- computed from
 * contention-free bandwidth per VM pair, with no replica-locality discount (runtime stage-in can
 * use an already evolved replica, so the actual transfer may be faster than the OCT estimate); it
 * models no link contention, network topology, or concurrent multi-workflow transfer (same
 * boundary as LOCAL_HEFT); the replica estimate does not fully replay the event order, so the
 * completion rule of short computations may still deviate. Descending rank_o does not
 * theoretically guarantee a topological order under heterogeneous costs (minimizing over
 * successor processors can push a parent's OCT below the cross-VM average of its successors), so
 * allocation uses the standard ready-list discipline of the paper: each round takes the task of
 * highest rank_o among those whose predecessors are all allocated (ties by the smaller task ID).
 * When the rank_o order is itself topologically legal (for example the paper example checked here,
 * guarded position by position by the existing unit test), the ready-list selection matches the
 * literal rank_o order item by item and the schedule is unchanged. It is PEFT on an abstract
 * model, not a calibration against a real platform.</p>
 */
public final class LocalPeftPlanningAlgorithm extends AbstractLocalCommPlanningAlgorithm {

    /** Task -> (VM -> OCT); cleared by every {@link #prepare()} and rebuilt on first query. */
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
        // Ready-list discipline: each round takes the task of highest rank_o among those whose
        // predecessors are all allocated. Descending rank_o does not guarantee a topological order
        // under heterogeneous costs (see the class boundary statement), and allocating in the
        // literal order makes the base-class readyTime fail fast; when the rank_o order is itself
        // topologically legal (for example the paper example checked here), the ready-list
        // selection matches the literal rank_o order item by item and the schedule is unchanged
        // position by position.
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
                // The depth check in prepare() already guarantees acyclicity; this branch is a
                // defensive fallback only.
                throw new IllegalStateException(
                        label() + " ready list exhausted with " + remaining.size() + " unallocated tasks");
            }
            remaining.remove(next);
            allocated.add(next);
            allocateOptimistic(next, octByTaskVm.get(next), stageInFinish);
        }
    }

    /**
     * OCT of the task on every VM. The first query fills the table in reverse topological order,
     * so a deep DAG does not use the Java call stack; querying rank_o directly after
     * {@link #prepare()} also does not depend on run() warming the table in a particular task
     * order.
     */
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

    /**
     * Every successor entry is already computed; the VM/child traversal, the arithmetic order,
     * and the PE compatibility domain are preserved.
     */
    private Map<CondorVM, Double> computeOctPerVm(Task task) {
        Map<CondorVM, Double> perVm = new LinkedHashMap<CondorVM, Double>();
        // One parent and one fill only. Preparation and later first-OCT queries remain separate reads.
        Map<Task, Double> edgeBytes = new HashMap<Task, Double>();
        for (CondorVM vm : vms()) {
            if (!isCompatible(task, vm)) {
                perVm.put(vm, Double.valueOf(Double.POSITIVE_INFINITY));
                continue;
            }
            double oct;
            if (task.getChildList().isEmpty()) {
                // Arabnejad/Barbosa Eq. (7): no successor work remains at an exit.
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

    /** Task priority = mean OCT over the compatible VMs (rank_o); available during run(). */
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

    /** OCT of the task on the given VM; throws {@link IllegalStateException} if not computed. */
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
