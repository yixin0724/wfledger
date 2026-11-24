package org.workflowsim.scheduling.ebpsm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Algorithm 1 (Budget Distribution) of Hilman, Rodriguez and Buyya, "Resource-sharing Policy in Multi-tenant
 * Scientific Workflow-as-a-Service Cloud Platform" (arXiv:1903.01113).
 *
 * <p>FGCS-R5 (2026-11-24) root causes fixed here:
 *
 * <ul>
 *   <li>The estimated execution order used the processing time on the lowest-MIPS type and assumed that parents
 *       carry smaller IDs. Levels (Eq. 7) and EFTs (Eq. 8) are now computed in topological order (Kahn, ascending
 *       ID), and the EFT of a task uses its processing time on the VM type Algorithm 1 first chooses for it (its
 *       cheapest type), so the order estimates the initial allocation it ranks.
 *   <li>The first phase charged every task its lowest-MIPS type even when the budget was exhausted and when a
 *       different type was cheaper. It now follows Algorithm 1 lines 12–17 literally: while {@code beta > 0} the
 *       next task of the order receives the Eq. 5 cost of its cheapest compatible type and {@code beta} drops by
 *       that cost; tasks not reached keep the initiated budget 0 (line 5).
 *   <li>The SFTD upgrade only accepted strictly more expensive types. Faster means a shorter processing time; a
 *       faster type that happens to cost less after per-second rounding is an upgrade too. Each task, in order,
 *       moves once to the fastest compatible type whose cost difference fits the remaining budget.
 *   <li>Eq. 5 includes the provisioning delays: the cost of a type is
 *       {@code ceil(prov_vmt + prov_c + PT) * c_vmt} with a one-second billing period.
 * </ul>
 *
 * <p>{@code pred(t)} is restricted to the distributed task set {@code T}, so on a budget update
 * ({@code distributeBudget(beta_u, T_u)}) a task whose parents already ran is a level-0 task of the residual DAG.
 */
public final class EbpsmBudgetDistributor {

    private static final double TOLERANCE = 1.0e-9;

    private final double containerProvisioningDelaySeconds;

    /**
     * Creates a distributor.
     *
     * @param containerProvisioningDelaySeconds {@code prov_c} of Eq. 5
     */
    public EbpsmBudgetDistributor(double containerProvisioningDelaySeconds) {
        if (!(containerProvisioningDelaySeconds >= 0.0) || !Double.isFinite(containerProvisioningDelaySeconds)) {
            throw new IllegalArgumentException("EBPSM container provisioning delay must be finite and non-negative");
        }
        this.containerProvisioningDelaySeconds = containerProvisioningDelaySeconds;
    }

    /**
     * Distributes a budget over a task set (Algorithm 1).
     *
     * @param tasks distributed task set {@code T}; may be empty
     * @param vmTypes VM types of the platform
     * @param budget budget {@code beta}; may be zero or negative after a debt
     * @return sub-budgets in estimated execution order plus the undistributed residual
     */
    public Allocation distribute(List<EbpsmTask> tasks, List<EbpsmVmType> vmTypes, double budget) {
        if (tasks == null || vmTypes == null || vmTypes.isEmpty() || !Double.isFinite(budget)) {
            throw new IllegalArgumentException("EBPSM requires a task set, VM types, and a finite budget");
        }
        Map<Integer, EbpsmVmType> types = new TreeMap<Integer, EbpsmVmType>();
        for (EbpsmVmType type : vmTypes) {
            if (types.put(type.getVmTypeId(), type) != null) {
                throw new IllegalArgumentException("Duplicate EBPSM VM type " + type.getVmTypeId());
            }
        }
        Map<Integer, EbpsmTask> byId = new TreeMap<Integer, EbpsmTask>();
        for (EbpsmTask task : tasks) {
            if (byId.put(task.getTaskId(), task) != null) {
                throw new IllegalArgumentException("Duplicate EBPSM task " + task.getTaskId());
            }
            for (Integer typeId : task.getProcessingSecondsByVmType().keySet()) {
                if (!types.containsKey(typeId)) {
                    throw new IllegalArgumentException(
                            "EBPSM task " + task.getTaskId() + " references unknown VM type " + typeId);
                }
            }
        }
        Map<Integer, Integer> cheapest = new LinkedHashMap<Integer, Integer>();
        for (EbpsmTask task : byId.values()) {
            cheapest.put(task.getTaskId(), cheapestType(task, types));
        }
        List<EbpsmTask> order = order(byId, cheapest);
        Map<Integer, Double> budgets = new LinkedHashMap<Integer, Double>();
        Map<Integer, Integer> chosen = new LinkedHashMap<Integer, Integer>();
        for (EbpsmTask task : order) {
            budgets.put(task.getTaskId(), 0.0);
        }
        double remaining = budget;
        int allocated = 0;
        while (remaining > 0.0 && allocated < order.size()) {
            EbpsmTask task = order.get(allocated++);
            int typeId = cheapest.get(task.getTaskId());
            double cost = cost(task, types.get(typeId));
            budgets.put(task.getTaskId(), cost);
            chosen.put(task.getTaskId(), typeId);
            remaining -= cost;
        }
        if (allocated == order.size() && remaining > 0.0) {
            for (EbpsmTask task : order) {
                int currentId = chosen.get(task.getTaskId());
                double currentSeconds = task.getProcessingSecondsByVmType().get(currentId);
                double currentCost = budgets.get(task.getTaskId());
                Integer bestId = null;
                Map<Integer, Double> processing = new TreeMap<Integer, Double>(task.getProcessingSecondsByVmType());
                for (Map.Entry<Integer, Double> entry : processing.entrySet()) {
                    double seconds = entry.getValue();
                    double cost = cost(task, types.get(entry.getKey()));
                    if (seconds < currentSeconds - TOLERANCE
                            && cost - currentCost <= remaining + TOLERANCE
                            && (bestId == null || faster(task, types, entry.getKey(), bestId))) {
                        bestId = entry.getKey();
                    }
                }
                if (bestId != null) {
                    double cost = cost(task, types.get(bestId));
                    remaining -= cost - currentCost;
                    budgets.put(task.getTaskId(), cost);
                    chosen.put(task.getTaskId(), bestId);
                }
            }
        }
        return new Allocation(budgets, chosen, remaining, allocated == order.size());
    }

    /**
     * Returns the Eq. 5 cost of a task on a VM type.
     *
     * @param task task
     * @param type compatible VM type
     * @return {@code ceil(prov_vmt + prov_c + PT) * c_vmt}
     */
    public double cost(EbpsmTask task, EbpsmVmType type) {
        Double seconds = task.getProcessingSecondsByVmType().get(type.getVmTypeId());
        if (seconds == null) {
            throw new IllegalArgumentException(
                    "EBPSM task " + task.getTaskId() + " is incompatible with VM type " + type.getVmTypeId());
        }
        return billedSeconds(type.getProvisioningDelaySeconds() + containerProvisioningDelaySeconds + seconds)
                * type.getCostPerSecond();
    }

    /**
     * Rounds a duration up to whole one-second billing periods, absorbing floating-point noise below 1e-9 s so
     * that an exactly two-second envelope computed as {@code 2.1 - 0.1} is billed two periods.
     *
     * @param seconds non-negative duration
     * @return whole billed seconds
     */
    public static double billedSeconds(double seconds) {
        if (!(seconds >= 0.0) || !Double.isFinite(seconds)) {
            throw new IllegalArgumentException("EBPSM billed duration must be finite and non-negative: " + seconds);
        }
        return Math.max(0.0, Math.ceil(seconds - TOLERANCE));
    }

    private int cheapestType(EbpsmTask task, Map<Integer, EbpsmVmType> types) {
        Integer best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (Integer typeId : new TreeMap<Integer, Double>(task.getProcessingSecondsByVmType()).keySet()) {
            double cost = cost(task, types.get(typeId));
            if (best == null
                    || cost < bestCost - TOLERANCE
                    || (Math.abs(cost - bestCost) <= TOLERANCE
                            && task.getProcessingSecondsByVmType().get(typeId)
                                    < task.getProcessingSecondsByVmType().get(best) - TOLERANCE)) {
                best = typeId;
                bestCost = cost;
            }
        }
        return best;
    }

    private boolean faster(EbpsmTask task, Map<Integer, EbpsmVmType> types, int candidate, int incumbent) {
        double candidateSeconds = task.getProcessingSecondsByVmType().get(candidate);
        double incumbentSeconds = task.getProcessingSecondsByVmType().get(incumbent);
        if (candidateSeconds < incumbentSeconds - TOLERANCE) {
            return true;
        }
        return Math.abs(candidateSeconds - incumbentSeconds) <= TOLERANCE
                && cost(task, types.get(candidate)) < cost(task, types.get(incumbent)) - TOLERANCE;
    }

    private static List<EbpsmTask> order(Map<Integer, EbpsmTask> byId, Map<Integer, Integer> cheapest) {
        Map<Integer, Integer> pending = new TreeMap<Integer, Integer>();
        Map<Integer, List<Integer>> children = new TreeMap<Integer, List<Integer>>();
        for (EbpsmTask task : byId.values()) {
            int count = 0;
            for (Integer parent : task.getParentTaskIds()) {
                if (byId.containsKey(parent)) {
                    count++;
                    List<Integer> list = children.get(parent);
                    if (list == null) {
                        list = new ArrayList<Integer>();
                        children.put(parent, list);
                    }
                    list.add(task.getTaskId());
                }
            }
            pending.put(task.getTaskId(), count);
        }
        java.util.TreeSet<Integer> ready = new java.util.TreeSet<Integer>();
        for (Map.Entry<Integer, Integer> entry : pending.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
        final Map<Integer, Integer> levels = new TreeMap<Integer, Integer>();
        final Map<Integer, Double> eft = new TreeMap<Integer, Double>();
        while (!ready.isEmpty()) {
            int id = ready.pollFirst();
            EbpsmTask task = byId.get(id);
            double seconds = task.getProcessingSecondsByVmType().get(cheapest.get(id));
            int level = 0;
            double start = 0.0;
            for (Integer parent : task.getParentTaskIds()) {
                if (byId.containsKey(parent)) {
                    level = Math.max(level, levels.get(parent) + 1);
                    start = Math.max(start, eft.get(parent));
                }
            }
            levels.put(id, level);
            eft.put(id, start + seconds);
            List<Integer> next = children.get(id);
            if (next != null) {
                for (Integer child : next) {
                    int left = pending.get(child) - 1;
                    pending.put(child, left);
                    if (left == 0) {
                        ready.add(child);
                    }
                }
            }
        }
        if (levels.size() != byId.size()) {
            throw new IllegalArgumentException("EBPSM task set contains a dependency cycle");
        }
        List<EbpsmTask> ordered = new ArrayList<EbpsmTask>(byId.values());
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

    /** Result of one Algorithm 1 invocation. */
    public static final class Allocation {
        private final Map<Integer, Double> budgets;
        private final Map<Integer, Integer> vmTypes;
        private final double residual;
        private final boolean everyTaskReached;

        Allocation(Map<Integer, Double> budgets, Map<Integer, Integer> vmTypes, double residual, boolean reached) {
            this.budgets = Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(budgets));
            this.vmTypes = Collections.unmodifiableMap(new LinkedHashMap<Integer, Integer>(vmTypes));
            this.residual = residual;
            this.everyTaskReached = reached;
        }

        /** @return sub-budget per task, in the estimated execution order {@code S} */
        public Map<Integer, Double> getBudgets() {
            return budgets;
        }

        /** @return VM type each reached task's sub-budget was sized for */
        public Map<Integer, Integer> getVmTypes() {
            return vmTypes;
        }

        /** @return budget left undistributed; negative when the last allocated task overshot {@code beta} */
        public double getResidual() {
            return residual;
        }

        /** @return whether the {@code while beta > 0} loop reached every task before the budget ran out */
        public boolean isEveryTaskReached() {
            return everyTaskReached;
        }
    }
}
