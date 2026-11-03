package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cloudbus.cloudsim.Consts;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.WorkflowDagValidator;
import org.workflowsim.utils.SimulationTiming;

/**
 * Shared computation and execution-model alignment for the maintained shared-storage static DAG
 * planners.
 *
 * <p>Every strategy derives a stable input order from the Task/VM IDs, first validates the DAG and
 * the controlled planning context, and then computes the plan from the execution duration on
 * compatible VMs, the effective stage-in duration, and the per-VM reservation set. The times here
 * are in-model estimates, not a production execution trace or a network-calibrated schedule.</p>
 */
final class SharedStorageDagPlanner {

    enum Strategy {
        HEFT,
        CPOP,
        DLS,
        ETF,
        PEFT
    }

    private static final long STAGE_IN_JOB_LENGTH_MI = 110L;
    private static final double RANK_TOLERANCE = 1.0e-9;

    private final PlanningContext context;
    private final Strategy strategy;
    private final List<Task> sourceTasks;
    private final List rawVms;
    /**
     * Validated forward topological order; it changes only the static rank/OCT evaluation order,
     * not the scheduling candidate order.
     */
    private List<Task> topologicalTasks = Collections.emptyList();

    private final Map<Task, Map<CondorVM, Double>> executionTimes = new HashMap<Task, Map<CondorVM, Double>>();
    private final Map<Task, Map<CondorVM, Double>> effectiveStageInTimes = new HashMap<Task, Map<CondorVM, Double>>();
    private final Map<Task, Double> upwardRanks = new HashMap<Task, Double>();
    private final Map<Task, Double> downwardRanks = new HashMap<Task, Double>();
    private final Map<Task, Double> dlsDynamicLevels = new HashMap<Task, Double>();
    private final Map<Task, Integer> dlsSelectionOrders = new HashMap<Task, Integer>();
    private final Map<Task, Double> etfEarliestStarts = new HashMap<Task, Double>();
    private final Map<Task, Integer> etfSelectionOrders = new HashMap<Task, Integer>();
    private final Map<Task, Map<CondorVM, Double>> peftOptimisticCosts = new HashMap<Task, Map<CondorVM, Double>>();
    private final Map<Task, Double> peftRankOct = new HashMap<Task, Double>();
    private final Map<Task, Double> peftSelectionScores = new HashMap<Task, Double>();
    private final Map<Task, Integer> peftSelectionOrders = new HashMap<Task, Integer>();
    private final Map<Task, Double> starts = new HashMap<Task, Double>();
    private final Map<Task, Double> finishes = new HashMap<Task, Double>();
    private final Map<CondorVM, List<Reservation>> reservations = new HashMap<CondorVM, List<Reservation>>();
    /**
     * At most one most-recent query per VM identity; plan() creates a new planner each time, so no
     * cache is kept across plans.
     */
    private final Map<CondorVM, SlotQuery> lastSlotQueries = new IdentityHashMap<CondorVM, SlotQuery>();

    private SharedStorageDagPlanner(List<Task> sourceTasks, List rawVms, PlanningContext context, Strategy strategy) {
        this.sourceTasks = sourceTasks;
        this.rawVms = rawVms;
        this.context = context;
        this.strategy = strategy;
    }

    static SharedStorageDagPlanTrace plan(
            List<Task> sourceTasks, List rawVms, PlanningContext context, Strategy strategy) {
        if (context == null || strategy == null) {
            throw new IllegalArgumentException("Shared-storage DAG planning requires context and strategy");
        }
        return new SharedStorageDagPlanner(sourceTasks, rawVms, context, strategy).run();
    }

    private SharedStorageDagPlanTrace run() {
        context.validateSharedStorageStaticDag();
        List<Task> tasks = sortedTasks();
        List<CondorVM> vms = sortedVms();
        WorkflowDagValidator.validateAndAssignDepths(tasks);
        // Every legal edge strictly increases depth; rank evaluation uses an independent copy and
        // reorders neither tasks nor adjacency lists.
        List<Task> byDepth = new ArrayList<Task>(tasks);
        Collections.sort(byDepth, new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                int byDepth = Integer.compare(first.getDepth(), second.getDepth());
                return byDepth != 0 ? byDepth : Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        topologicalTasks = Collections.unmodifiableList(byDepth);
        populateExecutionTimes(tasks, vms);
        for (CondorVM vm : vms) {
            reservations.put(vm, new ArrayList<Reservation>());
        }
        for (int index = topologicalTasks.size() - 1; index >= 0; index--) {
            upwardRank(topologicalTasks.get(index));
        }

        // The stage-in Job starts at time zero and its finish time follows the same minimum-event
        // and safety-quantum rules as WorkflowDatacenter; after the workflow engine receives the
        // completion event, one further kernel interval releases the root compute Job.
        double stageInFinish = SimulationTiming.earliestCloudletCompletionTime(
                        0.0,
                        STAGE_IN_JOB_LENGTH_MI / vms.get(0).getMips(),
                        context.getCloudSimMinEventIntervalSeconds())
                + context.getCloudSimMinEventIntervalSeconds();
        List<Task> criticalPath = Collections.emptyList();
        CondorVM criticalProcessor = null;
        if (strategy == Strategy.HEFT) {
            scheduleHeft(tasks, vms, stageInFinish);
        } else if (strategy == Strategy.CPOP) {
            CpopSelection selection = scheduleCpop(tasks, vms, stageInFinish);
            criticalPath = selection.criticalPath;
            criticalProcessor = selection.criticalProcessor;
        } else if (strategy == Strategy.DLS) {
            scheduleDls(tasks, vms, stageInFinish);
        } else if (strategy == Strategy.ETF) {
            scheduleEtf(tasks, vms, stageInFinish);
        } else {
            schedulePeft(tasks, vms, stageInFinish);
        }
        return trace(tasks, stageInFinish, criticalPath, criticalProcessor);
    }

    private List<Task> sortedTasks() {
        if (sourceTasks == null || sourceTasks.isEmpty()) {
            throw new IllegalArgumentException(strategy + " requires at least one task");
        }
        List<Task> tasks = new ArrayList<Task>(sourceTasks);
        Collections.sort(tasks, taskIdComparator());
        return tasks;
    }

    private List<CondorVM> sortedVms() {
        if (rawVms == null || rawVms.isEmpty()) {
            throw new IllegalArgumentException(strategy + " requires at least one VM");
        }
        List<CondorVM> vms = new ArrayList<CondorVM>();
        for (Object object : rawVms) {
            if (!(object instanceof CondorVM)) {
                throw new IllegalArgumentException(strategy + " requires CondorVM instances");
            }
            CondorVM vm = (CondorVM) object;
            if (vm.getMips() <= 0.0 || Double.isNaN(vm.getMips()) || Double.isInfinite(vm.getMips())) {
                throw new IllegalArgumentException(strategy + " requires finite positive VM MIPS");
            }
            vms.add(vm);
        }
        Collections.sort(vms, new Comparator<CondorVM>() {
            @Override
            public int compare(CondorVM first, CondorVM second) {
                return Integer.compare(first.getId(), second.getId());
            }
        });
        return vms;
    }

    private void populateExecutionTimes(List<Task> tasks, List<CondorVM> vms) {
        for (Task task : tasks) {
            double transferSeconds = taskTransferSeconds(task);
            // R8 audit fix (P2-4): CondorVM does not override hashCode, so the values() iteration
            // order of a plain HashMap varies with the identity hash (random across JVM startups);
            // averageExecutionTime summing over it yields ulp-level rank differences from the
            // differing floating-point addition order, and near-tie comparisons can flip, breaking
            // cross-process bit-level reproducibility.
            // LinkedHashMap makes the iteration order equal the sortedVms() insertion order,
            // which is deterministic.
            Map<CondorVM, Double> byVm = new LinkedHashMap<CondorVM, Double>();
            Map<CondorVM, Double> stageInByVm = new LinkedHashMap<CondorVM, Double>();
            for (CondorVM vm : vms) {
                if (task.getNumberOfPes() > vm.getNumberOfPes()) {
                    byVm.put(vm, Double.POSITIVE_INFINITY);
                    stageInByVm.put(vm, Double.POSITIVE_INFINITY);
                    continue;
                }
                long transferMi = (long) (vm.getMips() * transferSeconds);
                // CloudSim runs multi-PE tasks in parallel: the actual time is
                // (length + transferMi/mips) / capacity, not the serial
                // (totalLength + transferMi) / mips. Using the single-PE length matches runtime.
                byVm.put(vm, (task.getCloudletLength() + transferMi) / vm.getMips());
                stageInByVm.put(vm, transferMi / vm.getMips());
            }
            executionTimes.put(task, byVm);
            effectiveStageInTimes.put(task, stageInByVm);
        }
    }

    private double taskTransferSeconds(Task task) {
        double seconds = 0.0;
        for (FileItem file : task.getFileList()) {
            if (file.isRealInputFile(task.getFileList())) {
                seconds += file.getSize() / (double) Consts.MILLION / context.getSharedStorageTransferRateMbPerSecond();
            }
        }
        return seconds;
    }

    private void scheduleHeft(List<Task> tasks, List<CondorVM> vms, double stageInFinish) {
        List<Task> ranked = new ArrayList<Task>(tasks);
        Collections.sort(ranked, new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                int byRank = Double.compare(upwardRanks.get(second), upwardRanks.get(first));
                return byRank != 0 ? byRank : Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        for (Task task : ranked) {
            allocate(task, vms, stageInFinish, null);
        }
    }

    private CpopSelection scheduleCpop(List<Task> tasks, List<CondorVM> vms, double stageInFinish) {
        for (Task task : topologicalTasks) {
            downwardRank(task);
        }
        List<Task> criticalPath = identifyCriticalPath(tasks);
        CondorVM criticalProcessor = selectCriticalProcessor(criticalPath, vms);
        Set<Task> scheduled = new HashSet<Task>();
        while (scheduled.size() < tasks.size()) {
            Task task = nextCpopReadyTask(tasks, scheduled);
            if (task == null) {
                throw new IllegalStateException("CPOP could not find a dependency-ready task");
            }
            allocate(task, vms, stageInFinish, criticalPath.contains(task) ? criticalProcessor : null);
            scheduled.add(task);
        }
        return new CpopSelection(criticalPath, criticalProcessor);
    }

    /**
     * DLS adaptation under the controlled model.
     *
     * <p>The dynamic level is the static b-level (this implementation's upward rank) of a
     * dependency-ready Task-VM pair minus its earliest insertion start time. This shared-storage
     * model has no interconnect topology or link reservation, so no communication term is added to
     * that estimate.</p>
     */
    private void scheduleDls(List<Task> tasks, List<CondorVM> vms, double stageInFinish) {
        Set<Task> scheduled = new HashSet<Task>();
        while (scheduled.size() < tasks.size()) {
            DlsCandidate selected = nextDlsCandidate(tasks, vms, scheduled, stageInFinish);
            if (selected == null) {
                throw new IllegalStateException("DLS could not find a dependency-ready Task-VM pair");
            }
            allocate(selected.task, vms, stageInFinish, selected.vm);
            dlsDynamicLevels.put(selected.task, selected.dynamicLevel);
            dlsSelectionOrders.put(selected.task, scheduled.size() + 1);
            scheduled.add(selected.task);
        }
    }

    private DlsCandidate nextDlsCandidate(
            List<Task> tasks, List<CondorVM> vms, Set<Task> scheduled, double stageInFinish) {
        DlsCandidate selected = null;
        for (Task task : tasks) {
            if (scheduled.contains(task) || !scheduled.containsAll(task.getParentList())) {
                continue;
            }
            double readyTime = readyTime(task, stageInFinish);
            for (CondorVM vm : vms) {
                double duration = executionTimes.get(task).get(vm).doubleValue();
                if (Double.isInfinite(duration)) {
                    continue;
                }
                double earliestStart = earliestStartForReadyPair(vm, readyTime, duration);
                DlsCandidate candidate =
                        new DlsCandidate(task, vm, upwardRanks.get(task).doubleValue() - earliestStart);
                if (selected == null || candidate.isBetterThan(selected)) {
                    selected = candidate;
                }
            }
        }
        return selected;
    }

    /**
     * ETF adaptation under the controlled model.
     *
     * <p>ETF selects the dependency-ready Task-VM pair with the smallest earliest insertion start
     * time; equal start times are broken first by the static b-level and then by the stable
     * Task/VM ID.</p>
     */
    private void scheduleEtf(List<Task> tasks, List<CondorVM> vms, double stageInFinish) {
        Set<Task> scheduled = new HashSet<Task>();
        while (scheduled.size() < tasks.size()) {
            EtfCandidate selected = nextEtfCandidate(tasks, vms, scheduled, stageInFinish);
            if (selected == null) {
                throw new IllegalStateException("ETF could not find a dependency-ready Task-VM pair");
            }
            allocate(selected.task, vms, stageInFinish, selected.vm);
            etfEarliestStarts.put(selected.task, selected.earliestStart);
            etfSelectionOrders.put(selected.task, scheduled.size() + 1);
            scheduled.add(selected.task);
        }
    }

    private EtfCandidate nextEtfCandidate(
            List<Task> tasks, List<CondorVM> vms, Set<Task> scheduled, double stageInFinish) {
        EtfCandidate selected = null;
        for (Task task : tasks) {
            if (scheduled.contains(task) || !scheduled.containsAll(task.getParentList())) {
                continue;
            }
            double readyTime = readyTime(task, stageInFinish);
            for (CondorVM vm : vms) {
                double duration = executionTimes.get(task).get(vm).doubleValue();
                if (Double.isInfinite(duration)) {
                    continue;
                }
                double earliestStart = earliestStartForReadyPair(vm, readyTime, duration);
                EtfCandidate candidate = new EtfCandidate(
                        task, vm, earliestStart, upwardRanks.get(task).doubleValue());
                if (selected == null || candidate.isBetterThan(selected)) {
                    selected = candidate;
                }
            }
        }
        return selected;
    }

    /**
     * PEFT adaptation under the controlled model.
     *
     * <p>OCT records the optimistic cost from a Task's successors to an exit Task. The maintained
     * shared-storage model has no link model, so the communication term of the original algorithm
     * is zero.</p>
     */
    private void schedulePeft(List<Task> tasks, List<CondorVM> vms, double stageInFinish) {
        // Successor OCTs are evaluated before the current task; the independent entry per
        // compatible VM and the original averaging summation order are preserved.
        for (int index = topologicalTasks.size() - 1; index >= 0; index--) {
            Task task = topologicalTasks.get(index);
            for (CondorVM vm : vms) {
                if (!Double.isInfinite(executionTimes.get(task).get(vm).doubleValue())) {
                    optimisticCost(task, vm, vms);
                }
            }
        }
        Set<Task> scheduled = new HashSet<Task>();
        while (scheduled.size() < tasks.size()) {
            Task selected = nextPeftReadyTask(tasks, vms, scheduled);
            if (selected == null) {
                throw new IllegalStateException("PEFT could not find a dependency-ready task");
            }
            allocatePeft(selected, vms, stageInFinish);
            peftSelectionOrders.put(selected, scheduled.size() + 1);
            scheduled.add(selected);
        }
    }

    private Task nextPeftReadyTask(List<Task> tasks, List<CondorVM> vms, Set<Task> scheduled) {
        Task selected = null;
        for (Task task : tasks) {
            if (scheduled.contains(task) || !scheduled.containsAll(task.getParentList())) {
                continue;
            }
            // Compute the rank even when only one Task is ready, so the trace keeps the complete
            // audit evidence.
            double taskRank = peftRank(task, vms);
            if (selected == null
                    || taskRank > peftRank(selected, vms)
                    || (Double.compare(taskRank, peftRank(selected, vms)) == 0
                            && task.getCloudletId() < selected.getCloudletId())) {
                selected = task;
            }
        }
        return selected;
    }

    private void allocatePeft(Task task, List<CondorVM> vms, double stageInFinish) {
        double readyTime = readyTime(task, stageInFinish);
        CondorVM selectedVm = null;
        double selectedStart = 0.0;
        double selectedFinish = Double.POSITIVE_INFINITY;
        double selectedScore = Double.POSITIVE_INFINITY;
        for (CondorVM vm : vms) {
            double duration = executionTimes.get(task).get(vm).doubleValue();
            if (Double.isInfinite(duration)) {
                continue;
            }
            double start = earliestStart(reservations.get(vm), readyTime, duration);
            double finish = start + duration;
            double score = finish + optimisticCost(task, vm, vms);
            if (score < selectedScore
                    || (Double.compare(score, selectedScore) == 0
                            && (selectedVm == null || vm.getId() < selectedVm.getId()))) {
                selectedVm = vm;
                selectedStart = start;
                selectedFinish = finish;
                selectedScore = score;
            }
        }
        if (selectedVm == null) {
            throw new IllegalArgumentException("PEFT cannot map task " + task.getCloudletId() + "; no compatible VM");
        }
        reserve(task, selectedVm, selectedStart, selectedFinish);
        peftSelectionScores.put(task, selectedScore);
    }

    /**
     * {@code OCT(task, vm) = max_child min_successorVm(duration(child, successorVm)
     * + OCT(child, successorVm))}; the communication term omitted by this topology-free
     * shared-storage model is zero.
     */
    private double optimisticCost(Task task, CondorVM vm, List<CondorVM> vms) {
        Map<CondorVM, Double> byVm = peftOptimisticCosts.get(task);
        if (byVm == null) {
            byVm = new HashMap<CondorVM, Double>();
            peftOptimisticCosts.put(task, byVm);
        }
        Double cached = byVm.get(vm);
        if (cached != null) {
            return cached.doubleValue();
        }
        double successorMaximum = 0.0;
        for (Task child : task.getChildList()) {
            double childMinimum = Double.POSITIVE_INFINITY;
            for (CondorVM childVm : vms) {
                double duration = executionTimes.get(child).get(childVm).doubleValue();
                if (Double.isInfinite(duration)) {
                    continue;
                }
                childMinimum = Math.min(
                        childMinimum,
                        duration + peftOptimisticCosts.get(child).get(childVm).doubleValue());
            }
            if (Double.isInfinite(childMinimum)) {
                throw new IllegalArgumentException(
                        "PEFT cannot map successor task " + child.getCloudletId() + "; no compatible VM");
            }
            successorMaximum = Math.max(successorMaximum, childMinimum);
        }
        byVm.put(vm, successorMaximum);
        return successorMaximum;
    }

    private double peftRank(Task task, List<CondorVM> vms) {
        Double cached = peftRankOct.get(task);
        if (cached != null) {
            return cached.doubleValue();
        }
        double total = 0.0;
        int count = 0;
        for (CondorVM vm : vms) {
            if (!Double.isInfinite(executionTimes.get(task).get(vm).doubleValue())) {
                total += optimisticCost(task, vm, vms);
                count++;
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException("PEFT cannot rank task " + task.getCloudletId() + "; no compatible VM");
        }
        double result = total / count;
        peftRankOct.put(task, result);
        return result;
    }

    private double upwardRank(Task task) {
        Double cached = upwardRanks.get(task);
        if (cached != null) {
            return cached.doubleValue();
        }
        double childMaximum = 0.0;
        for (Task child : task.getChildList()) {
            childMaximum = Math.max(childMaximum, upwardRanks.get(child).doubleValue());
        }
        double result = averageExecutionTime(task) + childMaximum;
        upwardRanks.put(task, result);
        return result;
    }

    private double downwardRank(Task task) {
        Double cached = downwardRanks.get(task);
        if (cached != null) {
            return cached.doubleValue();
        }
        double parentMaximum = 0.0;
        for (Task parent : task.getParentList()) {
            parentMaximum =
                    Math.max(parentMaximum, downwardRanks.get(parent).doubleValue() + averageExecutionTime(parent));
        }
        downwardRanks.put(task, parentMaximum);
        return parentMaximum;
    }

    private double averageExecutionTime(Task task) {
        double total = 0.0;
        int count = 0;
        for (Double duration : executionTimes.get(task).values()) {
            if (!Double.isInfinite(duration.doubleValue())) {
                total += duration.doubleValue();
                count++;
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException(
                    strategy + " cannot map task " + task.getCloudletId() + "; no compatible VM");
        }
        return total / count;
    }

    private List<Task> identifyCriticalPath(List<Task> tasks) {
        Task current = highestPriorityEntryTask(tasks);
        double target = cpopPriority(current);
        List<Task> path = new ArrayList<Task>();
        while (true) {
            path.add(current);
            Task next = null;
            for (Task child : current.getChildList()) {
                if (!sameRank(cpopPriority(child), target)) {
                    continue;
                }
                if (next == null
                        || upwardRanks.get(child).doubleValue()
                                > upwardRanks.get(next).doubleValue()
                        || (Double.compare(upwardRanks.get(child), upwardRanks.get(next)) == 0
                                && child.getCloudletId() < next.getCloudletId())) {
                    next = child;
                }
            }
            if (next == null) {
                if (!current.getChildList().isEmpty()) {
                    throw new IllegalStateException("CPOP could not continue its critical path");
                }
                return path;
            }
            current = next;
        }
    }

    /**
     * 2026-10-26 complexity extraction from identifyCriticalPath; statements verbatim; order unchanged.
     * Scope: select only the entry task with the highest CPOP priority (ties take the smaller task
     * ID); throw when no entry task exists.
     */
    private Task highestPriorityEntryTask(List<Task> tasks) {
        Task current = null;
        double target = Double.NEGATIVE_INFINITY;
        for (Task task : tasks) {
            if (!task.getParentList().isEmpty()) {
                continue;
            }
            double priority = cpopPriority(task);
            if (priority > target
                    || (Double.compare(priority, target) == 0
                            && current != null
                            && task.getCloudletId() < current.getCloudletId())) {
                current = task;
                target = priority;
            }
        }
        if (current == null) {
            throw new IllegalStateException("CPOP requires a DAG entry task");
        }
        return current;
    }

    private CondorVM selectCriticalProcessor(List<Task> criticalPath, List<CondorVM> vms) {
        CondorVM selected = null;
        double selectedCost = Double.POSITIVE_INFINITY;
        for (CondorVM vm : vms) {
            double total = 0.0;
            for (Task task : criticalPath) {
                double duration = executionTimes.get(task).get(vm).doubleValue();
                if (Double.isInfinite(duration)) {
                    total = Double.POSITIVE_INFINITY;
                    break;
                }
                total += duration;
            }
            if (total < selectedCost
                    || (Double.compare(total, selectedCost) == 0
                            && selected != null
                            && vm.getId() < selected.getId())) {
                selected = vm;
                selectedCost = total;
            }
        }
        if (selected == null || Double.isInfinite(selectedCost)) {
            throw new IllegalArgumentException("CPOP cannot assign the critical path to one compatible VM");
        }
        return selected;
    }

    private Task nextCpopReadyTask(List<Task> tasks, Set<Task> scheduled) {
        Task selected = null;
        for (Task task : tasks) {
            if (scheduled.contains(task) || !scheduled.containsAll(task.getParentList())) {
                continue;
            }
            if (selected == null
                    || cpopPriority(task) > cpopPriority(selected)
                    || (Double.compare(cpopPriority(task), cpopPriority(selected)) == 0
                            && task.getCloudletId() < selected.getCloudletId())) {
                selected = task;
            }
        }
        return selected;
    }

    private double cpopPriority(Task task) {
        return upwardRanks.get(task).doubleValue() + downwardRanks.get(task).doubleValue();
    }

    private static boolean sameRank(double first, double second) {
        return Math.abs(first - second) <= RANK_TOLERANCE * Math.max(1.0, Math.abs(second));
    }

    private void allocate(Task task, List<CondorVM> vms, double stageInFinish, CondorVM forcedVm) {
        double readyTime = readyTime(task, stageInFinish);

        CondorVM selectedVm = null;
        double selectedStart = 0.0;
        double selectedFinish = Double.POSITIVE_INFINITY;
        for (CondorVM vm : vms) {
            if (forcedVm != null && vm != forcedVm) {
                continue;
            }
            double duration = executionTimes.get(task).get(vm).doubleValue();
            if (Double.isInfinite(duration)) {
                continue;
            }
            double start = strategy == Strategy.DLS || strategy == Strategy.ETF
                    ? earliestStartForReadyPair(vm, readyTime, duration)
                    : earliestStart(reservations.get(vm), readyTime, duration);
            double finish = start + duration;
            if (finish < selectedFinish
                    || (Double.compare(finish, selectedFinish) == 0
                            && selectedVm != null
                            && vm.getId() < selectedVm.getId())) {
                selectedVm = vm;
                selectedStart = start;
                selectedFinish = finish;
            }
        }
        if (selectedVm == null) {
            throw new IllegalArgumentException(
                    strategy + " cannot map task " + task.getCloudletId() + "; no compatible VM");
        }
        reserve(task, selectedVm, selectedStart, selectedFinish);
    }

    private void reserve(Task task, CondorVM vm, double start, double finish) {
        if (strategy == Strategy.DLS || strategy == Strategy.ETF) {
            // Any insertion starts a new occupancy generation, so query results from the VM's old
            // calendar must not be reused.
            lastSlotQueries.remove(vm);
        }
        reservations.get(vm).add(new Reservation(start, finish));
        Collections.sort(reservations.get(vm), new Comparator<Reservation>() {
            @Override
            public int compare(Reservation first, Reservation second) {
                int byStart = Double.compare(first.start, second.start);
                return byStart != 0 ? byStart : Double.compare(first.finish, second.finish);
            }
        });
        starts.put(task, start);
        finishes.put(task, finish);
        task.setVmId(vm.getId());
        task.setStaticScheduleStartTime(start);
    }

    private double readyTime(Task task, double stageInFinish) {
        double readyTime = task.getParentList().isEmpty() ? stageInFinish : 0.0;
        for (Task parent : task.getParentList()) {
            Double parentFinish = finishes.get(parent);
            if (parentFinish == null) {
                throw new IllegalStateException(
                        strategy + " priority order did not schedule parent " + parent.getCloudletId());
            }
            readyTime = Math.max(readyTime, parentFinish.doubleValue());
        }
        return readyTime;
    }

    /**
     * Only DLS/ETF reuse the most recent identical slot query on the same VM.
     *
     * <p>Each VM keeps at most one record, so the total space is O(V); reserve invalidates that
     * VM's occupancy generation. A different query only overwrites the old record, and consecutive
     * distinct ready/duration pairs do not promise fewer scan steps. A miss still runs the original
     * loop: intervals are not merged and time comparisons are not approximated.</p>
     */
    private double earliestStartForReadyPair(CondorVM vm, double readyTime, double duration) {
        long readyBits = Double.doubleToRawLongBits(readyTime);
        long durationBits = Double.doubleToRawLongBits(duration);
        SlotQuery cached = lastSlotQueries.get(vm);
        if (cached != null && cached.readyBits == readyBits && cached.durationBits == durationBits) {
            return cached.start;
        }
        double start = earliestStart(reservations.get(vm), readyTime, duration);
        if (cached == null) {
            lastSlotQueries.put(vm, new SlotQuery(readyBits, durationBits, start));
        } else {
            // Reuse the record object to avoid allocating a cache key/record per pair for
            // all-distinct queries.
            cached.readyBits = readyBits;
            cached.durationBits = durationBits;
            cached.start = start;
        }
        return start;
    }

    private static double earliestStart(List<Reservation> schedule, double readyTime, double duration) {
        double start = readyTime;
        for (Reservation reservation : schedule) {
            if (start + duration <= reservation.start) {
                return start;
            }
            start = Math.max(start, reservation.finish);
        }
        return start;
    }

    private static Comparator<Task> taskIdComparator() {
        return new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                return Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        };
    }

    private SharedStorageDagPlanTrace trace(
            List<Task> tasks, double stageInFinish, List<Task> criticalPath, CondorVM criticalProcessor) {
        Map<Integer, SharedStorageDagPlanTrace.TaskPlan> plans =
                new java.util.LinkedHashMap<Integer, SharedStorageDagPlanTrace.TaskPlan>();
        for (Task task : tasks) {
            Double downward = strategy == Strategy.CPOP ? downwardRanks.get(task) : null;
            Double priority = strategy == Strategy.CPOP ? cpopPriority(task) : null;
            Double dynamicLevel = strategy == Strategy.DLS ? dlsDynamicLevels.get(task) : null;
            Integer selectionOrder = strategy == Strategy.DLS ? dlsSelectionOrders.get(task) : null;
            Double etfEarliestStart = strategy == Strategy.ETF ? etfEarliestStarts.get(task) : null;
            Integer etfSelectionOrder = strategy == Strategy.ETF ? etfSelectionOrders.get(task) : null;
            Double peftRank = strategy == Strategy.PEFT ? peftRankOct.get(task) : null;
            Double peftOct =
                    strategy == Strategy.PEFT ? peftOptimisticCosts.get(task).get(vmById(task.getVmId())) : null;
            Double peftScore = strategy == Strategy.PEFT ? peftSelectionScores.get(task) : null;
            Integer peftSelectionOrder = strategy == Strategy.PEFT ? peftSelectionOrders.get(task) : null;
            plans.put(
                    task.getCloudletId(),
                    new SharedStorageDagPlanTrace.TaskPlan(
                            task.getCloudletId(),
                            task.getVmId(),
                            upwardRanks.get(task),
                            downward,
                            priority,
                            dynamicLevel,
                            selectionOrder,
                            etfEarliestStart,
                            etfSelectionOrder,
                            peftRank,
                            peftOct,
                            peftScore,
                            peftSelectionOrder,
                            starts.get(task),
                            finishes.get(task),
                            effectiveStageInTimes.get(task).get(vmById(task.getVmId()))));
        }
        List<Integer> criticalTaskIds = new ArrayList<Integer>();
        for (Task task : criticalPath) {
            criticalTaskIds.add(task.getCloudletId());
        }
        return new SharedStorageDagPlanTrace(
                strategy.name(),
                stageInFinish,
                plans,
                criticalTaskIds,
                criticalProcessor == null ? null : criticalProcessor.getId());
    }

    private CondorVM vmById(int vmId) {
        for (CondorVM vm : reservations.keySet()) {
            if (vm.getId() == vmId) {
                return vm;
            }
        }
        throw new IllegalStateException("Static DAG plan has no VM " + vmId);
    }

    private static final class CpopSelection {
        private final List<Task> criticalPath;
        private final CondorVM criticalProcessor;

        private CpopSelection(List<Task> criticalPath, CondorVM criticalProcessor) {
            this.criticalPath = criticalPath;
            this.criticalProcessor = criticalProcessor;
        }
    }

    private static final class DlsCandidate {
        private final Task task;
        private final CondorVM vm;
        private final double dynamicLevel;

        private DlsCandidate(Task task, CondorVM vm, double dynamicLevel) {
            this.task = task;
            this.vm = vm;
            this.dynamicLevel = dynamicLevel;
        }

        private boolean isBetterThan(DlsCandidate other) {
            int byDynamicLevel = Double.compare(dynamicLevel, other.dynamicLevel);
            if (byDynamicLevel != 0) {
                return byDynamicLevel > 0;
            }
            int byTaskId = Integer.compare(task.getCloudletId(), other.task.getCloudletId());
            return byTaskId != 0 ? byTaskId < 0 : vm.getId() < other.vm.getId();
        }
    }

    private static final class EtfCandidate {
        private final Task task;
        private final CondorVM vm;
        private final double earliestStart;
        private final double staticBLevel;

        private EtfCandidate(Task task, CondorVM vm, double earliestStart, double staticBLevel) {
            this.task = task;
            this.vm = vm;
            this.earliestStart = earliestStart;
            this.staticBLevel = staticBLevel;
        }

        private boolean isBetterThan(EtfCandidate other) {
            int byEarliestStart = Double.compare(earliestStart, other.earliestStart);
            if (byEarliestStart != 0) {
                return byEarliestStart < 0;
            }
            int byStaticBLevel = Double.compare(staticBLevel, other.staticBLevel);
            if (byStaticBLevel != 0) {
                return byStaticBLevel > 0;
            }
            int byTaskId = Integer.compare(task.getCloudletId(), other.task.getCloudletId());
            return byTaskId != 0 ? byTaskId < 0 : vm.getId() < other.vm.getId();
        }
    }

    private static final class SlotQuery {
        private long readyBits;
        private long durationBits;
        private double start;

        private SlotQuery(long readyBits, long durationBits, double start) {
            this.readyBits = readyBits;
            this.durationBits = durationBits;
            this.start = start;
        }
    }

    private static final class Reservation {
        private final double start;
        private final double finish;

        private Reservation(double start, double finish) {
            this.start = start;
            this.finish = finish;
        }
    }
}
