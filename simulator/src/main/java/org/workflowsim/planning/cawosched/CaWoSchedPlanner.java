package org.workflowsim.planning.cawosched;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

import org.workflowsim.platform.PlatformProfile;

/**
 * Compute-only CaWoSched core for a fixed Task-to-VM mapping and per-VM order.
 *
 * <p>The implementation follows Schweisgut et al. (ICPP 2025): ASAP uses earliest starts;
 * slack/pressure scheduling evaluates tasks by the initial EST/LST scores, chooses the
 * highest-green-budget interval start inside each current [EST,LST] range, breaks equal budgets
 * by earliest start, and otherwise falls back to EST. No task mapping, ordering, preemption, or
 * communication-processor model is changed. This is a post-planning component, not a claim that
 * WorkflowSim's ordinary STATIC dispatcher already enforces absolute start times.</p>
 */
public final class CaWoSchedPlanner {

    private static final double EPSILON = 1.0e-9;

    public enum Score {
        SLACK,
        PRESSURE
    }

    private final List<CaWoSchedTask> tasks;
    private final Map<Integer, CaWoSchedTask> tasksById;
    private final Map<Integer, List<Integer>> parents;
    private final Map<Integer, List<Integer>> children;
    private final List<Integer> topologicalOrder;
    private final List<PlatformProfile.CarbonWindow> windows;
    private final double deadlineSeconds;
    private final double idleWattsSum;

    public CaWoSchedPlanner(
            List<CaWoSchedTask> sourceTasks, List<PlatformProfile.CarbonWindow> sourceWindows, double deadlineSeconds) {
        this(sourceTasks, sourceWindows, deadlineSeconds, Collections.<Integer, Double>emptyMap());
    }

    /**
     * @param declaredIdleWattsByVm optional complete fleet idle-power declaration, including
     *     task-less VMs; without it, only task-bearing VMs contribute to the idle baseline
     */
    public CaWoSchedPlanner(
            List<CaWoSchedTask> sourceTasks,
            List<PlatformProfile.CarbonWindow> sourceWindows,
            double deadlineSeconds,
            Map<Integer, Double> declaredIdleWattsByVm) {
        if (sourceTasks == null || sourceTasks.isEmpty()) {
            throw new IllegalArgumentException("CaWoSched requires at least one compute task");
        }
        if (!(deadlineSeconds > 0.0) || !Double.isFinite(deadlineSeconds)) {
            throw new IllegalArgumentException("CaWoSched deadline must be finite and positive");
        }
        this.deadlineSeconds = deadlineSeconds;
        this.windows = validatedWindows(sourceWindows, deadlineSeconds);
        this.tasks = sortedTasks(sourceTasks);
        this.tasksById = indexTasks(tasks);
        List<Map<Integer, List<Integer>>> dependencyModel = dependencyModel(tasks, tasksById);
        this.parents = dependencyModel.get(0);
        this.children = dependencyModel.get(1);
        this.topologicalOrder = Collections.unmodifiableList(topologicalOrder(tasksById, parents, children));
        this.idleWattsSum = idleWattsSum(tasks, declaredIdleWattsByVm);
    }

    /** Returns the same fixed mapping/order with every task placed at its earliest start. */
    public CaWoSchedPlan planAsap() {
        return schedule(null, false, false, false);
    }

    /** Returns the slack- or pressure-scored greedy schedule. */
    public CaWoSchedPlan plan(Score score) {
        if (score == null) {
            throw new IllegalArgumentException("CaWoSched score is required");
        }
        return schedule(score, false, false, false);
    }

    /** Returns a power-weighted, refined, and optionally locally improved schedule. */
    public CaWoSchedPlan plan(Score score, boolean weighted, boolean refined, boolean localSearch) {
        if (score == null) {
            throw new IllegalArgumentException("CaWoSched score is required");
        }
        return schedule(score, weighted, refined, localSearch);
    }

    private CaWoSchedPlan schedule(Score score, boolean weighted, boolean refined, boolean localSearch) {
        Map<Integer, Double> starts = new LinkedHashMap<Integer, Double>();
        Map<Integer, Double> earliest = new LinkedHashMap<Integer, Double>();
        Map<Integer, Double> latest = new LinkedHashMap<Integer, Double>();
        recomputeBounds(starts, earliest, latest);
        requireFeasibleBounds(earliest, latest);

        List<Integer> decisionOrder = decisionOrder(score, weighted, earliest, latest);
        List<Interval> intervals = refined ? refinedIntervals() : baseIntervals();
        if (score == null) {
            starts.putAll(earliest);
        } else {
            for (Integer taskId : decisionOrder) {
                double selected = refined
                        ? selectStartFromIntervals(earliest.get(taskId), latest.get(taskId), intervals)
                        : selectStart(taskId, earliest.get(taskId), latest.get(taskId));
                starts.put(taskId, Double.valueOf(selected));
                earliest.put(taskId, Double.valueOf(selected));
                latest.put(taskId, Double.valueOf(selected));
                if (refined) {
                    consumePower(taskId, selected, intervals);
                }
                recomputeBounds(starts, earliest, latest);
            }
        }
        if (localSearch) {
            improveLocalSearch(starts, intervals);
        }
        validateSchedule(starts);
        return buildPlan(starts, decisionOrder);
    }

    private CaWoSchedPlan buildPlan(Map<Integer, Double> starts, List<Integer> decisionOrder) {
        Map<Integer, CaWoSchedPlan.TaskPlan> plans = new LinkedHashMap<Integer, CaWoSchedPlan.TaskPlan>();
        double makespan = 0.0;
        for (CaWoSchedTask task : tasks) {
            double start = starts.get(task.getTaskId()).doubleValue();
            plans.put(
                    task.getTaskId(),
                    new CaWoSchedPlan.TaskPlan(
                            task.getTaskId(),
                            task.getVmId(),
                            start,
                            task.getDurationSeconds(),
                            task.getIdleWatts(),
                            task.getActiveWatts()));
            makespan = Math.max(makespan, start + task.getDurationSeconds());
        }
        return new CaWoSchedPlan(plans, decisionOrder, deadlineSeconds, makespan, carbonExcessWattSeconds(plans));
    }

    private List<Integer> decisionOrder(
            Score score, boolean weighted, Map<Integer, Double> earliest, Map<Integer, Double> latest) {
        if (score == null) {
            return topologicalOrder;
        }
        List<Integer> result = new ArrayList<Integer>();
        final Map<Integer, Double> values = new LinkedHashMap<Integer, Double>();
        double maxActive = 0.0;
        for (CaWoSchedTask task : tasks) {
            maxActive = Math.max(maxActive, task.getActiveWatts());
        }
        for (CaWoSchedTask task : tasks) {
            int id = task.getTaskId();
            double slack = latest.get(id).doubleValue() - earliest.get(id).doubleValue();
            double pressure =
                    task.getDurationSeconds() / (latest.get(id) + task.getDurationSeconds() - earliest.get(id));
            double value;
            if (score == Score.SLACK) {
                value = weighted && maxActive > 0.0 ? slack * maxActive / task.getActiveWatts() : slack;
            } else {
                value = weighted && maxActive > 0.0 ? pressure * task.getActiveWatts() / maxActive : pressure;
            }
            values.put(id, Double.valueOf(value));
        }
        for (CaWoSchedTask task : tasks) {
            result.add(task.getTaskId());
        }
        final Score selectedScore = score;
        Collections.sort(result, new Comparator<Integer>() {
            @Override
            public int compare(Integer first, Integer second) {
                int byScore = Double.compare(values.get(first), values.get(second));
                if (selectedScore == Score.PRESSURE) {
                    byScore = -byScore;
                }
                return byScore != 0 ? byScore : Integer.compare(first, second);
            }
        });
        return result;
    }

    private double selectStart(int taskId, double earliest, double latest) {
        double selected = earliest;
        double bestGreen = Double.NEGATIVE_INFINITY;
        for (PlatformProfile.CarbonWindow window : windows) {
            double start = window.getStartSeconds();
            if (start + EPSILON < earliest || start > latest + EPSILON) {
                continue;
            }
            if (window.getGreenWatts() > bestGreen + EPSILON
                    || (Math.abs(window.getGreenWatts() - bestGreen) <= EPSILON && start < selected)) {
                selected = start;
                bestGreen = window.getGreenWatts();
            }
        }
        return selected;
    }

    private List<Interval> baseIntervals() {
        List<Interval> result = new ArrayList<Interval>();
        for (PlatformProfile.CarbonWindow window : windows) {
            result.add(new Interval(window.getStartSeconds(), window.getEndSeconds(), window.getGreenWatts()));
        }
        return result;
    }

    private List<Interval> refinedIntervals() {
        Set<Double> candidateTimes = new java.util.TreeSet<Double>();
        Map<Integer, List<CaWoSchedTask>> byVm = new LinkedHashMap<Integer, List<CaWoSchedTask>>();
        for (CaWoSchedTask task : tasks) {
            byVm.computeIfAbsent(task.getVmId(), key -> new ArrayList<CaWoSchedTask>())
                    .add(task);
        }
        for (List<CaWoSchedTask> vmTasks : byVm.values()) {
            Collections.sort(vmTasks, Comparator.comparingInt(CaWoSchedTask::getOrderOnVm));
            for (int begin = 0; begin < vmTasks.size(); begin++) {
                for (int end = begin; end < vmTasks.size() && end < begin + 3; end++) {
                    List<CaWoSchedTask> block = vmTasks.subList(begin, end + 1);
                    double duration = 0.0;
                    for (CaWoSchedTask task : block) {
                        duration += task.getDurationSeconds();
                    }
                    for (PlatformProfile.CarbonWindow window : windows) {
                        addBlockStarts(candidateTimes, block, window.getStartSeconds(), duration);
                        addBlockStarts(candidateTimes, block, window.getEndSeconds(), duration);
                    }
                }
            }
        }
        for (PlatformProfile.CarbonWindow window : windows) {
            candidateTimes.add(window.getStartSeconds());
            candidateTimes.add(window.getEndSeconds());
        }
        List<Double> ordered = new ArrayList<Double>(candidateTimes);
        List<Interval> result = new ArrayList<Interval>();
        for (int index = 0; index + 1 < ordered.size(); index++) {
            double start = ordered.get(index);
            result.add(new Interval(start, ordered.get(index + 1), greenWattsAt(start + EPSILON)));
        }
        return result;
    }

    private void addBlockStarts(Set<Double> candidates, List<CaWoSchedTask> block, double point, double duration) {
        if (point + duration <= deadlineSeconds + EPSILON) {
            double cursor = point;
            for (CaWoSchedTask task : block) {
                candidates.add(cursor);
                cursor += task.getDurationSeconds();
            }
        }
        if (point - duration >= -EPSILON) {
            double cursor = point;
            for (int index = block.size() - 1; index >= 0; index--) {
                cursor -= block.get(index).getDurationSeconds();
                candidates.add(cursor);
            }
        }
    }

    private double selectStartFromIntervals(double earliest, double latest, List<Interval> intervals) {
        double selected = earliest;
        double bestGreen = Double.NEGATIVE_INFINITY;
        for (Interval interval : intervals) {
            double start = interval.start;
            if (start + EPSILON < earliest || start > latest + EPSILON) {
                continue;
            }
            if (interval.budgetWatts > bestGreen + EPSILON
                    || (Math.abs(interval.budgetWatts - bestGreen) <= EPSILON && start < selected)) {
                selected = start;
                bestGreen = interval.budgetWatts;
            }
        }
        return selected;
    }

    private void consumePower(int taskId, double start, List<Interval> intervals) {
        CaWoSchedTask task = tasksById.get(taskId);
        for (Interval interval : intervals) {
            if (start + EPSILON >= interval.start && start < interval.end + EPSILON) {
                interval.budgetWatts -= task.getActiveWatts();
            }
        }
    }

    private void improveLocalSearch(Map<Integer, Double> starts, List<Interval> intervals) {
        List<Integer> decisionOrder = new ArrayList<Integer>(tasksById.keySet());
        for (int pass = 0; pass < 10; pass++) {
            boolean improved = false;
            for (CaWoSchedTask task : tasks) {
                int taskId = task.getTaskId();
                double currentCost = buildPlan(starts, decisionOrder).getCarbonExcessWattSeconds();
                double bestCost = currentCost;
                double bestStart = starts.get(taskId);
                for (Interval interval : intervals) {
                    double candidate = interval.start;
                    if (candidate + EPSILON < 0.0) {
                        continue;
                    }
                    Map<Integer, Double> trial = new LinkedHashMap<Integer, Double>(starts);
                    trial.put(taskId, candidate);
                    try {
                        validateSchedule(trial);
                    } catch (IllegalArgumentException invalid) {
                        continue;
                    }
                    double candidateCost = buildPlan(trial, decisionOrder).getCarbonExcessWattSeconds();
                    if (candidateCost < bestCost - EPSILON
                            || (Math.abs(candidateCost - bestCost) <= EPSILON && candidate < bestStart)) {
                        bestCost = candidateCost;
                        bestStart = candidate;
                    }
                }
                if (bestCost < currentCost - EPSILON) {
                    starts.put(taskId, bestStart);
                    improved = true;
                }
            }
            if (!improved) {
                break;
            }
        }
    }

    private static final class Interval {
        private final double start;
        private final double end;
        private double budgetWatts;

        private Interval(double start, double end, double budgetWatts) {
            this.start = start;
            this.end = end;
            this.budgetWatts = budgetWatts;
        }
    }

    private void recomputeBounds(
            Map<Integer, Double> starts, Map<Integer, Double> earliest, Map<Integer, Double> latest) {
        for (Integer taskId : topologicalOrder) {
            if (starts.containsKey(taskId)) {
                continue;
            }
            double value = 0.0;
            for (Integer parent : parents.get(taskId)) {
                double parentStart = starts.containsKey(parent) ? starts.get(parent) : earliest.get(parent);
                value = Math.max(value, parentStart + tasksById.get(parent).getDurationSeconds());
            }
            earliest.put(taskId, Double.valueOf(value));
        }
        for (int index = topologicalOrder.size() - 1; index >= 0; index--) {
            Integer taskId = topologicalOrder.get(index);
            if (starts.containsKey(taskId)) {
                continue;
            }
            double value = deadlineSeconds;
            for (Integer child : children.get(taskId)) {
                value = Math.min(value, latest.get(child).doubleValue());
            }
            latest.put(taskId, Double.valueOf(value - tasksById.get(taskId).getDurationSeconds()));
        }
    }

    private void requireFeasibleBounds(Map<Integer, Double> earliest, Map<Integer, Double> latest) {
        for (CaWoSchedTask task : tasks) {
            int id = task.getTaskId();
            if (earliest.get(id).doubleValue() > latest.get(id).doubleValue() + EPSILON) {
                throw new IllegalArgumentException("CaWoSched deadline is infeasible for task " + id);
            }
        }
    }

    private void validateSchedule(Map<Integer, Double> starts) {
        for (CaWoSchedTask task : tasks) {
            int id = task.getTaskId();
            double start = starts.get(id).doubleValue();
            if (!(start >= 0.0) || start + task.getDurationSeconds() > deadlineSeconds + EPSILON) {
                throw new IllegalArgumentException("CaWoSched task misses its deadline: " + id);
            }
            for (Integer parent : parents.get(id)) {
                double parentFinish = starts.get(parent) + tasksById.get(parent).getDurationSeconds();
                if (parentFinish > start + EPSILON) {
                    throw new IllegalArgumentException("CaWoSched precedence violation: " + parent + " -> " + id);
                }
            }
        }
        Map<Integer, List<CaWoSchedTask>> byVm = new LinkedHashMap<Integer, List<CaWoSchedTask>>();
        for (CaWoSchedTask task : tasks) {
            byVm.computeIfAbsent(task.getVmId(), key -> new ArrayList<CaWoSchedTask>())
                    .add(task);
        }
        for (List<CaWoSchedTask> vmTasks : byVm.values()) {
            Collections.sort(vmTasks, Comparator.comparingInt(CaWoSchedTask::getOrderOnVm));
            for (int index = 1; index < vmTasks.size(); index++) {
                CaWoSchedTask previous = vmTasks.get(index - 1);
                CaWoSchedTask current = vmTasks.get(index);
                if (starts.get(previous.getTaskId()) + previous.getDurationSeconds()
                        > starts.get(current.getTaskId()) + EPSILON) {
                    throw new IllegalArgumentException(
                            "CaWoSched per-VM order/overlap violation on VM " + current.getVmId());
                }
            }
        }
    }

    private double carbonExcessWattSeconds(Map<Integer, CaWoSchedPlan.TaskPlan> plans) {
        Map<Double, Double> events = new TreeMap<Double, Double>();
        for (PlatformProfile.CarbonWindow window : windows) {
            events.put(window.getStartSeconds(), events.getOrDefault(window.getStartSeconds(), 0.0));
            events.put(window.getEndSeconds(), events.getOrDefault(window.getEndSeconds(), 0.0));
        }
        for (CaWoSchedPlan.TaskPlan plan : plans.values()) {
            bump(events, plan.getStartSeconds(), plan.getDynamicWatts());
            bump(events, plan.getFinishSeconds(), -plan.getDynamicWatts());
        }
        double total = 0.0;
        double active = 0.0;
        double previous = 0.0;
        for (Map.Entry<Double, Double> event : events.entrySet()) {
            double time = event.getKey().doubleValue();
            total += excess(previous, time, idleWattsSum + active);
            active += event.getValue().doubleValue();
            previous = time;
        }
        total += excess(previous, deadlineSeconds, idleWattsSum + active);
        return total;
    }

    private double excess(double start, double end, double watts) {
        if (end <= start + EPSILON) {
            return 0.0;
        }
        double green = greenWattsAt((start + end) / 2.0);
        return Math.max(0.0, watts - green) * (end - start);
    }

    private double greenWattsAt(double time) {
        for (PlatformProfile.CarbonWindow window : windows) {
            if (time + EPSILON >= window.getStartSeconds() && time < window.getEndSeconds() + EPSILON) {
                return window.getGreenWatts();
            }
        }
        throw new IllegalStateException("CaWoSched horizon has no green-power declaration at " + time);
    }

    private static void bump(Map<Double, Double> events, double time, double watts) {
        events.put(time, events.getOrDefault(time, 0.0) + watts);
    }

    private static List<CaWoSchedTask> sortedTasks(List<CaWoSchedTask> source) {
        List<CaWoSchedTask> result = new ArrayList<CaWoSchedTask>(source);
        Collections.sort(result, Comparator.comparingInt(CaWoSchedTask::getTaskId));
        return result;
    }

    private static Map<Integer, CaWoSchedTask> indexTasks(List<CaWoSchedTask> tasks) {
        Map<Integer, CaWoSchedTask> result = new LinkedHashMap<Integer, CaWoSchedTask>();
        for (CaWoSchedTask task : tasks) {
            if (result.put(task.getTaskId(), task) != null) {
                throw new IllegalArgumentException("Duplicate CaWoSched task ID " + task.getTaskId());
            }
        }
        return result;
    }

    private static List<Map<Integer, List<Integer>>> dependencyModel(
            List<CaWoSchedTask> tasks, Map<Integer, CaWoSchedTask> byId) {
        Map<Integer, Set<Integer>> parentSets = new LinkedHashMap<Integer, Set<Integer>>();
        Map<Integer, List<CaWoSchedTask>> byVm = new LinkedHashMap<Integer, List<CaWoSchedTask>>();
        Map<Integer, Set<Integer>> orderKeys = new LinkedHashMap<Integer, Set<Integer>>();
        for (CaWoSchedTask task : tasks) {
            Set<Integer> declared = new LinkedHashSet<Integer>(task.getParentTaskIds());
            if (declared.contains(task.getTaskId())) {
                throw new IllegalArgumentException("CaWoSched task cannot depend on itself: " + task.getTaskId());
            }
            for (Integer parent : declared) {
                if (!byId.containsKey(parent)) {
                    throw new IllegalArgumentException("Unknown parent task " + parent + " for " + task.getTaskId());
                }
            }
            parentSets.put(task.getTaskId(), declared);
            byVm.computeIfAbsent(task.getVmId(), key -> new ArrayList<CaWoSchedTask>())
                    .add(task);
            if (!orderKeys
                    .computeIfAbsent(task.getVmId(), key -> new LinkedHashSet<Integer>())
                    .add(task.getOrderOnVm())) {
                throw new IllegalArgumentException(
                        "Duplicate per-VM order " + task.getOrderOnVm() + " on VM " + task.getVmId());
            }
        }
        for (List<CaWoSchedTask> vmTasks : byVm.values()) {
            Collections.sort(vmTasks, Comparator.comparingInt(CaWoSchedTask::getOrderOnVm));
            for (int index = 1; index < vmTasks.size(); index++) {
                parentSets
                        .get(vmTasks.get(index).getTaskId())
                        .add(vmTasks.get(index - 1).getTaskId());
            }
        }

        Map<Integer, List<Integer>> parents = new LinkedHashMap<Integer, List<Integer>>();
        Map<Integer, List<Integer>> children = new LinkedHashMap<Integer, List<Integer>>();
        for (CaWoSchedTask task : tasks) {
            children.put(task.getTaskId(), new ArrayList<Integer>());
        }
        for (CaWoSchedTask task : tasks) {
            List<Integer> orderedParents = new ArrayList<Integer>(parentSets.get(task.getTaskId()));
            Collections.sort(orderedParents);
            parents.put(task.getTaskId(), orderedParents);
            for (Integer parent : orderedParents) {
                children.get(parent).add(task.getTaskId());
            }
        }
        for (List<Integer> childIds : children.values()) {
            Collections.sort(childIds);
        }
        List<Map<Integer, List<Integer>>> result = new ArrayList<Map<Integer, List<Integer>>>();
        result.add(parents);
        result.add(children);
        return result;
    }

    private static List<Integer> topologicalOrder(
            Map<Integer, CaWoSchedTask> tasks,
            Map<Integer, List<Integer>> parents,
            Map<Integer, List<Integer>> children) {
        Map<Integer, Integer> indegree = new LinkedHashMap<Integer, Integer>();
        PriorityQueue<Integer> ready = new PriorityQueue<Integer>();
        for (Integer taskId : tasks.keySet()) {
            int degree = parents.get(taskId).size();
            indegree.put(taskId, degree);
            if (degree == 0) {
                ready.add(taskId);
            }
        }
        List<Integer> result = new ArrayList<Integer>();
        while (!ready.isEmpty()) {
            Integer taskId = ready.remove();
            result.add(taskId);
            for (Integer child : children.get(taskId)) {
                int degree = indegree.get(child) - 1;
                indegree.put(child, degree);
                if (degree == 0) {
                    ready.add(child);
                }
            }
        }
        if (result.size() != tasks.size()) {
            throw new IllegalArgumentException("CaWoSched fixed plan contains a dependency/order cycle");
        }
        return result;
    }

    private static double idleWattsSum(List<CaWoSchedTask> tasks, Map<Integer, Double> declared) {
        Map<Integer, Double> idleByVm = new LinkedHashMap<Integer, Double>();
        for (CaWoSchedTask task : tasks) {
            Double previous = idleByVm.put(task.getVmId(), task.getIdleWatts());
            if (previous != null && Double.compare(previous, task.getIdleWatts()) != 0) {
                throw new IllegalArgumentException("Inconsistent idle power for VM " + task.getVmId());
            }
        }
        if (declared != null && !declared.isEmpty()) {
            for (Map.Entry<Integer, Double> entry : declared.entrySet()) {
                if (entry.getKey() < 0
                        || entry.getValue() == null
                        || !(entry.getValue() >= 0.0)
                        || !Double.isFinite(entry.getValue())) {
                    throw new IllegalArgumentException("Invalid declared VM idle power");
                }
            }
            idleByVm = new LinkedHashMap<Integer, Double>(declared);
        }
        double sum = 0.0;
        for (double value : idleByVm.values()) {
            sum += value;
        }
        return sum;
    }

    private static List<PlatformProfile.CarbonWindow> validatedWindows(
            List<PlatformProfile.CarbonWindow> source, double deadline) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("CaWoSched requires complete green-power windows");
        }
        List<PlatformProfile.CarbonWindow> result = new ArrayList<PlatformProfile.CarbonWindow>(source);
        if (Math.abs(result.get(0).getStartSeconds()) > EPSILON) {
            throw new IllegalArgumentException("CaWoSched windows must start at time zero");
        }
        for (int index = 1; index < result.size(); index++) {
            if (Math.abs(result.get(index - 1).getEndSeconds()
                            - result.get(index).getStartSeconds())
                    > EPSILON) {
                throw new IllegalArgumentException("CaWoSched windows must be contiguous and non-overlapping");
            }
        }
        if (Math.abs(result.get(result.size() - 1).getEndSeconds() - deadline) > EPSILON) {
            throw new IllegalArgumentException("CaWoSched windows must cover exactly [0, deadline)");
        }
        return Collections.unmodifiableList(result);
    }
}
