package org.workflowsim.planning.icpcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One-phase IC-PCP (Abrishami, Naghibzadeh and Epema, FGCS 29(1), 2013) over a fixed, declared VM pool.
 *
 * <p>FGCS-R3 (2026-11-24) root causes fixed here:
 *
 * <ul>
 *   <li>M1: the critical parent was the unassigned parent with the smallest slack. The paper defines it as
 *       the unassigned parent with the latest data arrival time, {@code max EFT(p) + TT(p,t)}; ties use the
 *       lower Task ID.
 *   <li>M2: path feasibility used one global minimum-slack test over a rank-ordered serialization of every
 *       VM, so the path was never checked against a concrete instance slot. The paper's AssignPath places a
 *       whole partial critical path on one instance and requires each task to finish by its LFT. Here every
 *       VM is one instance; the path is placed consecutively at one position of that VM's task sequence,
 *       start times are recomputed by a forward EST pass over the DAG plus the per-VM sequences, and the
 *       candidate is applicable only if every assigned task (the path and the tasks already on any VM)
 *       still finishes by its LFT. Among applicable candidates the minimum incremental billed cost wins,
 *       then the lower VM ID, then the earlier sequence position.
 * </ul>
 *
 * <p>Remaining deviation from the paper (fixed-pool adaptation): no new instance can be launched. A VM
 * without tasks plays the role of a new instance of its type at its own declared rate; when no VM is
 * applicable the planner fails explicitly instead of launching one. Already placed tasks may be delayed
 * (never reordered or remapped) when a path is inserted before them, but only within their LFT. EST/LFT of
 * unassigned tasks use the minimum execution time over compatible VMs (the paper's MET) and count the edge
 * transfer time, because their VM is unknown.
 */
public final class IcpcpPlanner {

    private static final int START_ID = -1;
    private static final int END_ID = -2;
    private static final double TOLERANCE = 1.0e-9;

    private final IcpcpProblem problem;
    private final Map<Integer, List<Integer>> parents = new HashMap<Integer, List<Integer>>();
    private final Map<Integer, List<Integer>> children = new HashMap<Integer, List<Integer>>();
    private final List<List<Integer>> assignedPaths = new ArrayList<List<Integer>>();
    private final Set<Integer> scheduled = new HashSet<Integer>();
    private State state;

    public IcpcpPlanner(IcpcpProblem problem) {
        if (problem == null) {
            throw new IllegalArgumentException("IC-PCP problem is required");
        }
        this.problem = problem;
        buildGraph();
        Map<Integer, List<Integer>> sequences = new TreeMap<Integer, List<Integer>>();
        for (Integer vmId : problem.getVmIds()) {
            sequences.put(vmId, new ArrayList<Integer>());
        }
        Timing timing = evaluate(new HashMap<Integer, Integer>(), sequences);
        if (timing == null) {
            throw new IllegalArgumentException("IC-PCP task graph contains a cycle");
        }
        this.state = new State(new HashMap<Integer, Integer>(), sequences, timing);
    }

    public IcpcpSchedule schedule() {
        assignParents(END_ID);
        if (scheduled.size() != problem.getTasks().size()) {
            throw new IllegalStateException("IC-PCP did not schedule every task");
        }
        Map<Integer, IcpcpSchedule.Entry> entries = entries(state);
        double makespan = 0.0;
        for (IcpcpSchedule.Entry entry : entries.values()) {
            makespan = Math.max(makespan, entry.getFinishSeconds());
        }
        return new IcpcpSchedule(entries, assignedPaths, makespan, cost(entries), minimumSlack(state));
    }

    private void assignParents(int taskId) {
        while (hasUnscheduledParents(taskId)) {
            List<Integer> path = new ArrayList<Integer>();
            int current = taskId;
            while (hasUnscheduledParents(current)) {
                int critical = criticalParent(current);
                path.add(0, critical);
                current = critical;
            }
            State best = assignPath(path);
            if (best == null) {
                throw new IllegalArgumentException("IC-PCP cannot satisfy the deadline for path " + path
                        + " on any declared VM (the fixed pool cannot launch a new instance)");
            }
            state = best;
            scheduled.addAll(path);
            assignedPaths.add(new ArrayList<Integer>(path));
            for (Integer pathTask : path) {
                assignParents(pathTask);
            }
        }
    }

    /** Latest data arrival time {@code max EFT(p) + TT(p,t)} over unassigned parents (FGCS-R3 M1). */
    private int criticalParent(int taskId) {
        int best = -1;
        double bestArrival = Double.NEGATIVE_INFINITY;
        for (Integer parent : parents.get(taskId)) {
            if (parent == START_ID || scheduled.contains(parent)) {
                continue;
            }
            double arrival = state.timing.finish.get(parent) + transfer(parent, taskId, state.assignments);
            if (arrival > bestArrival + TOLERANCE || (Math.abs(arrival - bestArrival) <= TOLERANCE && parent < best)) {
                best = parent;
                bestArrival = arrival;
            }
        }
        if (best < 0) {
            throw new IllegalStateException("No unscheduled critical parent for task " + taskId);
        }
        return best;
    }

    private State assignPath(List<Integer> path) {
        State best = null;
        double bestDelta = Double.POSITIVE_INFINITY;
        double currentCost = cost(entries(state));
        for (Integer vmId : problem.getVmIds()) {
            if (!supportsPath(vmId, path)) {
                continue;
            }
            List<Integer> sequence = state.sequences.get(vmId);
            for (int position = 0; position <= sequence.size(); position++) {
                Map<Integer, Integer> trialAssignments = new HashMap<Integer, Integer>(state.assignments);
                for (Integer task : path) {
                    trialAssignments.put(task, vmId);
                }
                Map<Integer, List<Integer>> trialSequences = copy(state.sequences);
                trialSequences.get(vmId).addAll(position, path);
                Timing timing = evaluate(trialAssignments, trialSequences);
                if (timing == null || !meetsLatestFinishTimes(trialAssignments, timing)) {
                    continue;
                }
                State candidate = new State(trialAssignments, trialSequences, timing);
                double delta = cost(entries(candidate)) - currentCost;
                if (best == null || delta < bestDelta - TOLERANCE) {
                    best = candidate;
                    bestDelta = delta;
                }
            }
        }
        return best;
    }

    /**
     * Paper applicability test: every assigned task finishes by its LFT. Exit LFTs equal the deadline and
     * EST/LFT use the same runtimes, so this also bounds the virtual END node by the deadline; a separate
     * END test would be redundant.
     */
    private boolean meetsLatestFinishTimes(Map<Integer, Integer> assigned, Timing timing) {
        for (Integer taskId : assigned.keySet()) {
            if (timing.finish.get(taskId) > timing.latestFinish.get(taskId) + TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    private boolean supportsPath(int vmId, List<Integer> path) {
        for (Integer taskId : path) {
            if (!problem.getTask(taskId).getComputeSecondsByVm().containsKey(vmId)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasUnscheduledParents(int taskId) {
        for (Integer parent : parents.get(taskId)) {
            if (parent != START_ID && !scheduled.contains(parent)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Forward EST and backward LFT over the DAG plus the per-VM sequence edges. Returns {@code null} when
     * the sequences contradict the DAG (a cycle), which makes that insertion position inapplicable.
     */
    private Timing evaluate(Map<Integer, Integer> assigned, Map<Integer, List<Integer>> sequences) {
        Map<Integer, Integer> previousOnVm = new HashMap<Integer, Integer>();
        Map<Integer, Integer> nextOnVm = new HashMap<Integer, Integer>();
        for (List<Integer> sequence : sequences.values()) {
            for (int index = 1; index < sequence.size(); index++) {
                previousOnVm.put(sequence.get(index), sequence.get(index - 1));
                nextOnVm.put(sequence.get(index - 1), sequence.get(index));
            }
        }
        List<Integer> order = topologicalOrder(previousOnVm, nextOnVm);
        if (order == null) {
            return null;
        }
        Map<Integer, Double> start = new HashMap<Integer, Double>();
        Map<Integer, Double> finish = new HashMap<Integer, Double>();
        double end = problem.getWorkflowStartSeconds();
        for (Integer taskId : order) {
            double ready = problem.getWorkflowStartSeconds();
            for (Integer parent : parents.get(taskId)) {
                if (parent != START_ID) {
                    ready = Math.max(ready, finish.get(parent) + transfer(parent, taskId, assigned));
                }
            }
            Integer previous = previousOnVm.get(taskId);
            if (previous != null) {
                ready = Math.max(ready, finish.get(previous));
            }
            start.put(taskId, ready);
            finish.put(taskId, ready + runtime(taskId, assigned));
            end = Math.max(end, finish.get(taskId));
        }
        start.put(END_ID, end);
        finish.put(END_ID, end);
        Map<Integer, Double> latestFinish = new HashMap<Integer, Double>();
        for (int index = order.size() - 1; index >= 0; index--) {
            int taskId = order.get(index);
            double latest = problem.getDeadlineSeconds();
            for (Integer child : children.get(taskId)) {
                if (child != END_ID) {
                    latest = Math.min(
                            latest,
                            latestFinish.get(child) - runtime(child, assigned) - transfer(taskId, child, assigned));
                }
            }
            Integer next = nextOnVm.get(taskId);
            if (next != null) {
                latest = Math.min(latest, latestFinish.get(next) - runtime(next, assigned));
            }
            latestFinish.put(taskId, latest);
        }
        latestFinish.put(END_ID, problem.getDeadlineSeconds());
        return new Timing(start, finish, latestFinish);
    }

    private List<Integer> topologicalOrder(Map<Integer, Integer> previousOnVm, Map<Integer, Integer> nextOnVm) {
        Map<Integer, Integer> remaining = new HashMap<Integer, Integer>();
        for (IcpcpTask task : problem.getTasks()) {
            int count = task.getParentTaskIds().size();
            if (previousOnVm.containsKey(task.getTaskId())) {
                count++;
            }
            remaining.put(task.getTaskId(), count);
        }
        TreeSet<Integer> ready = new TreeSet<Integer>();
        for (Map.Entry<Integer, Integer> entry : remaining.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
        List<Integer> result = new ArrayList<Integer>();
        while (!ready.isEmpty()) {
            int taskId = ready.pollFirst();
            result.add(taskId);
            List<Integer> successors = new ArrayList<Integer>();
            for (Integer child : children.get(taskId)) {
                if (child != END_ID) {
                    successors.add(child);
                }
            }
            Integer next = nextOnVm.get(taskId);
            if (next != null) {
                successors.add(next);
            }
            for (Integer successor : successors) {
                int count = remaining.get(successor) - 1;
                remaining.put(successor, count);
                if (count == 0) {
                    ready.add(successor);
                }
            }
        }
        return result.size() == problem.getTasks().size() ? result : null;
    }

    private double runtime(int taskId, Map<Integer, Integer> assigned) {
        Integer vmId = assigned.get(taskId);
        return vmId == null
                ? fastestCompute(taskId)
                : problem.getTask(taskId).getComputeSecondsByVm().get(vmId);
    }

    /** Edge transfer time; zero only when both endpoints are assigned to the same VM. */
    private double transfer(int parent, int child, Map<Integer, Integer> assigned) {
        if (parent == START_ID || child == END_ID) {
            return 0.0;
        }
        Integer parentVm = assigned.get(parent);
        Integer childVm = assigned.get(child);
        if (parentVm != null && parentVm.equals(childVm)) {
            return 0.0;
        }
        return problem.getTask(parent).getCommunicationSecondsByChild().getOrDefault(child, 0.0);
    }

    private Map<Integer, IcpcpSchedule.Entry> entries(State current) {
        Map<Integer, IcpcpSchedule.Entry> result = new LinkedHashMap<Integer, IcpcpSchedule.Entry>();
        for (IcpcpTask task : problem.getTasks()) {
            Integer vmId = current.assignments.get(task.getTaskId());
            if (vmId != null) {
                result.put(
                        task.getTaskId(),
                        new IcpcpSchedule.Entry(
                                task.getTaskId(),
                                vmId,
                                current.timing.start.get(task.getTaskId()),
                                current.timing.finish.get(task.getTaskId())));
            }
        }
        return result;
    }

    private double cost(Map<Integer, IcpcpSchedule.Entry> schedule) {
        Map<Integer, List<IcpcpSchedule.Entry>> byVm = new TreeMap<Integer, List<IcpcpSchedule.Entry>>();
        for (IcpcpSchedule.Entry entry : schedule.values()) {
            byVm.computeIfAbsent(entry.getVmId(), ignored -> new ArrayList<IcpcpSchedule.Entry>())
                    .add(entry);
        }
        double total = 0.0;
        for (Map.Entry<Integer, List<IcpcpSchedule.Entry>> entry : byVm.entrySet()) {
            double first = Double.POSITIVE_INFINITY;
            double last = Double.NEGATIVE_INFINITY;
            for (IcpcpSchedule.Entry task : entry.getValue()) {
                first = Math.min(first, task.getStartSeconds());
                last = Math.max(last, task.getFinishSeconds());
            }
            double periods = Math.ceil(Math.max(0.0, last - first) / problem.getBillingPeriodSeconds() - 1.0e-12);
            total += periods * problem.getBillingPeriodSeconds() * problem.getVmCostPerSecond(entry.getKey());
        }
        return total;
    }

    /** Minimum of {@code LFT - EFT} over every task and of {@code deadline - makespan} (virtual END). */
    private double minimumSlack(State current) {
        double minimum = Double.POSITIVE_INFINITY;
        for (IcpcpTask task : problem.getTasks()) {
            minimum = Math.min(
                    minimum,
                    current.timing.latestFinish.get(task.getTaskId()) - current.timing.finish.get(task.getTaskId()));
        }
        minimum = Math.min(minimum, current.timing.latestFinish.get(END_ID) - current.timing.finish.get(END_ID));
        return minimum;
    }

    private double fastestCompute(int taskId) {
        double minimum = Double.POSITIVE_INFINITY;
        for (double seconds : problem.getTask(taskId).getComputeSecondsByVm().values()) {
            minimum = Math.min(minimum, seconds);
        }
        return minimum;
    }

    private static Map<Integer, List<Integer>> copy(Map<Integer, List<Integer>> sequences) {
        Map<Integer, List<Integer>> result = new TreeMap<Integer, List<Integer>>();
        for (Map.Entry<Integer, List<Integer>> entry : sequences.entrySet()) {
            result.put(entry.getKey(), new ArrayList<Integer>(entry.getValue()));
        }
        return result;
    }

    private void buildGraph() {
        for (IcpcpTask task : problem.getTasks()) {
            List<Integer> taskParents = new ArrayList<Integer>(task.getParentTaskIds());
            if (taskParents.isEmpty()) {
                taskParents.add(START_ID);
            }
            parents.put(task.getTaskId(), taskParents);
            children.computeIfAbsent(task.getTaskId(), ignored -> new ArrayList<Integer>());
        }
        for (IcpcpTask task : problem.getTasks()) {
            for (Integer parent : task.getParentTaskIds()) {
                List<Integer> parentChildren = children.get(parent);
                if (parentChildren == null) {
                    throw new IllegalArgumentException(
                            "IC-PCP task " + task.getTaskId() + " references unknown parent " + parent);
                }
                parentChildren.add(task.getTaskId());
            }
        }
        List<Integer> exits = new ArrayList<Integer>();
        for (IcpcpTask task : problem.getTasks()) {
            if (children.get(task.getTaskId()).isEmpty()) {
                children.get(task.getTaskId()).add(END_ID);
                exits.add(task.getTaskId());
            }
        }
        parents.put(END_ID, exits);
    }

    private static final class Timing {
        private final Map<Integer, Double> start;
        private final Map<Integer, Double> finish;
        private final Map<Integer, Double> latestFinish;

        private Timing(Map<Integer, Double> start, Map<Integer, Double> finish, Map<Integer, Double> latestFinish) {
            this.start = start;
            this.finish = finish;
            this.latestFinish = latestFinish;
        }
    }

    private static final class State {
        private final Map<Integer, Integer> assignments;
        private final Map<Integer, List<Integer>> sequences;
        private final Timing timing;

        private State(Map<Integer, Integer> assignments, Map<Integer, List<Integer>> sequences, Timing timing) {
            this.assignments = assignments;
            this.sequences = sequences;
            this.timing = timing;
        }
    }
}
