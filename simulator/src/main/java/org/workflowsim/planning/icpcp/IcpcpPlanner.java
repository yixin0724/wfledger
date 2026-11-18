package org.workflowsim.planning.icpcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class IcpcpPlanner {

    private static final int START_ID = -1;
    private static final int END_ID = -2;
    private final IcpcpProblem problem;
    private final Map<Integer, List<Integer>> parents = new HashMap<Integer, List<Integer>>();
    private final Map<Integer, List<Integer>> children = new HashMap<Integer, List<Integer>>();
    private final List<Integer> priority;
    private final List<List<Integer>> assignedPaths = new ArrayList<List<Integer>>();
    private Map<Integer, IcpcpSchedule.Entry> entries = new LinkedHashMap<Integer, IcpcpSchedule.Entry>();
    private Map<Integer, Integer> assignments = new HashMap<Integer, Integer>();
    private final Set<Integer> scheduled = new HashSet<Integer>();
    private Map<Integer, Double> est;
    private Map<Integer, Double> lft;

    public IcpcpPlanner(IcpcpProblem problem) {
        if (problem == null) {
            throw new IllegalArgumentException("IC-PCP problem is required");
        }
        this.problem = problem;
        buildGraph();
        this.priority = buildPriority();
        this.est = computeEst(assignments);
        this.lft = computeLft(assignments);
    }

    public IcpcpSchedule schedule() {
        assignParents(END_ID);
        if (scheduled.size() != problem.getTasks().size()) {
            throw new IllegalStateException("IC-PCP did not schedule every task");
        }
        double makespan = 0.0;
        for (IcpcpSchedule.Entry entry : entries.values()) {
            makespan = Math.max(makespan, entry.getFinishSeconds());
        }
        return new IcpcpSchedule(entries, assignedPaths, makespan, cost(entries), slack(est, lft));
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
            Candidate best = assignPath(path);
            if (best == null) {
                throw new IllegalArgumentException("IC-PCP cannot satisfy the deadline for path " + path);
            }
            entries = best.entries;
            assignments = best.assignments;
            scheduled.addAll(path);
            assignedPaths.add(new ArrayList<Integer>(path));
            est = computeEst(assignments);
            lft = computeLft(assignments);
            for (Integer pathTask : path) {
                assignParents(pathTask);
            }
        }
    }

    private Candidate assignPath(List<Integer> path) {
        Candidate best = null;
        double currentCost = cost(entries);
        for (Integer vmId : problem.getVmIds()) {
            if (!supportsPath(vmId, path)) {
                continue;
            }
            Map<Integer, Integer> trialAssignments = new HashMap<Integer, Integer>(assignments);
            for (Integer task : path) {
                trialAssignments.put(task, vmId);
            }
            Map<Integer, Double> trialEst = computeEst(trialAssignments);
            Map<Integer, Double> trialLft = computeLft(trialAssignments);
            double slack = slack(trialEst, trialLft);
            if (slack < -1.0e-9) {
                continue;
            }
            Map<Integer, IcpcpSchedule.Entry> trialEntries = scheduleEntries(trialAssignments, trialEst);
            double costDelta = cost(trialEntries) - currentCost;
            if (best == null
                    || costDelta < best.costDelta - 1.0e-9
                    || (Math.abs(costDelta - best.costDelta) <= 1.0e-9 && slack > best.slack + 1.0e-9)
                    || (Math.abs(costDelta - best.costDelta) <= 1.0e-9
                            && Math.abs(slack - best.slack) <= 1.0e-9
                            && vmId < best.vmId)) {
                best = new Candidate(vmId, trialAssignments, trialEntries, costDelta, slack);
            }
        }
        return best;
    }

    private boolean supportsPath(int vmId, List<Integer> path) {
        for (Integer taskId : path) {
            if (!problem.getTask(taskId).getComputeSecondsByVm().containsKey(vmId)) {
                return false;
            }
        }
        return true;
    }

    private int criticalParent(int taskId) {
        int best = -1;
        double bestSlack = Double.POSITIVE_INFINITY;
        for (Integer parent : parents.get(taskId)) {
            if (parent == START_ID || scheduled.contains(parent)) {
                continue;
            }
            double slack = lft.get(parent) - est.get(parent);
            if (slack < bestSlack - 1.0e-9 || (Math.abs(slack - bestSlack) <= 1.0e-9 && parent < best)) {
                best = parent;
                bestSlack = slack;
            }
        }
        if (best < 0) {
            throw new IllegalStateException("No unscheduled critical parent for task " + taskId);
        }
        return best;
    }

    private boolean hasUnscheduledParents(int taskId) {
        for (Integer parent : parents.get(taskId)) {
            if (parent != START_ID && !scheduled.contains(parent)) {
                return true;
            }
        }
        return false;
    }

    private Map<Integer, Double> computeEst(Map<Integer, Integer> assigned) {
        Map<Integer, Double> result = new HashMap<Integer, Double>();
        Map<Integer, Double> finish = new HashMap<Integer, Double>();
        Map<Integer, Double> available = new HashMap<Integer, Double>();
        result.put(START_ID, problem.getWorkflowStartSeconds());
        finish.put(START_ID, problem.getWorkflowStartSeconds());
        for (Integer taskId : priority) {
            Integer vmId = assigned.get(taskId);
            double ready = 0.0;
            for (Integer parent : parents.get(taskId)) {
                double parentReady = finish.get(parent);
                Integer parentVm = assigned.get(parent);
                if (parentVm == null || !parentVm.equals(vmId)) {
                    parentReady += communication(parent, taskId);
                }
                ready = Math.max(ready, parentReady);
            }
            if (vmId == null) {
                result.put(taskId, ready);
                finish.put(taskId, ready + fastestCompute(taskId));
            } else {
                double start = Math.max(ready, available.getOrDefault(vmId, 0.0));
                double taskFinish =
                        start + problem.getTask(taskId).getComputeSecondsByVm().get(vmId);
                result.put(taskId, start);
                finish.put(taskId, taskFinish);
                available.put(vmId, taskFinish);
            }
        }
        double end = problem.getWorkflowStartSeconds();
        for (Integer taskId : priority) {
            end = Math.max(end, finish.get(taskId));
        }
        result.put(END_ID, end);
        finish.put(END_ID, end);
        return result;
    }

    private Map<Integer, Double> computeLft(Map<Integer, Integer> assigned) {
        Map<Integer, Double> result = new HashMap<Integer, Double>();
        result.put(END_ID, problem.getDeadlineSeconds());
        for (int index = priority.size() - 1; index >= 0; index--) {
            int taskId = priority.get(index);
            Integer vmId = assigned.get(taskId);
            double latest = problem.getDeadlineSeconds();
            for (Integer child : children.get(taskId)) {
                double childRuntime = child == END_ID
                        ? 0.0
                        : assigned.containsKey(child)
                                ? problem.getTask(child).getComputeSecondsByVm().get(assigned.get(child))
                                : fastestCompute(child);
                Integer childVm = assigned.get(child);
                double comm = childVm == null || !childVm.equals(vmId) ? communication(taskId, child) : 0.0;
                latest = Math.min(latest, result.get(child) - childRuntime - comm);
            }
            result.put(taskId, latest);
        }
        return result;
    }

    private Map<Integer, IcpcpSchedule.Entry> scheduleEntries(
            Map<Integer, Integer> assigned, Map<Integer, Double> starts) {
        Map<Integer, IcpcpSchedule.Entry> result = new LinkedHashMap<Integer, IcpcpSchedule.Entry>();
        for (Integer taskId : priority) {
            Integer vmId = assigned.get(taskId);
            if (vmId != null) {
                double start = starts.get(taskId);
                result.put(
                        taskId,
                        new IcpcpSchedule.Entry(
                                taskId,
                                vmId,
                                start,
                                start
                                        + problem.getTask(taskId)
                                                .getComputeSecondsByVm()
                                                .get(vmId)));
            }
        }
        return result;
    }

    private double cost(Map<Integer, IcpcpSchedule.Entry> schedule) {
        Map<Integer, List<IcpcpSchedule.Entry>> byVm = new HashMap<Integer, List<IcpcpSchedule.Entry>>();
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

    private double slack(Map<Integer, Double> starts, Map<Integer, Double> latest) {
        double minimum = Double.POSITIVE_INFINITY;
        for (IcpcpTask task : problem.getTasks()) {
            minimum = Math.min(minimum, latest.get(task.getTaskId()) - starts.get(task.getTaskId()));
        }
        minimum = Math.min(minimum, latest.get(END_ID) - starts.get(END_ID));
        return minimum;
    }

    private double fastestCompute(int taskId) {
        double minimum = Double.POSITIVE_INFINITY;
        for (double seconds : problem.getTask(taskId).getComputeSecondsByVm().values()) {
            minimum = Math.min(minimum, seconds);
        }
        return minimum;
    }

    private double communication(int parent, int child) {
        if (parent == START_ID || child == END_ID) {
            return 0.0;
        }
        return problem.getTask(parent).getCommunicationSecondsByChild().getOrDefault(child, 0.0);
    }

    private void buildGraph() {
        for (IcpcpTask task : problem.getTasks()) {
            List<Integer> taskParents = new ArrayList<Integer>(task.getParentTaskIds());
            if (taskParents.isEmpty()) {
                taskParents.add(START_ID);
            }
            parents.put(task.getTaskId(), taskParents);
            for (Integer parent : taskParents) {
                children.computeIfAbsent(parent, ignored -> new ArrayList<Integer>())
                        .add(task.getTaskId());
            }
            children.computeIfAbsent(task.getTaskId(), ignored -> new ArrayList<Integer>());
        }
        for (IcpcpTask task : problem.getTasks()) {
            if (children.get(task.getTaskId()).isEmpty()) {
                children.get(task.getTaskId()).add(END_ID);
            }
        }
        children.put(START_ID, children.getOrDefault(START_ID, new ArrayList<Integer>()));
        parents.put(END_ID, new ArrayList<Integer>());
        for (IcpcpTask task : problem.getTasks()) {
            if (children.get(task.getTaskId()).contains(END_ID)) {
                parents.get(END_ID).add(task.getTaskId());
            }
        }
    }

    private List<Integer> buildPriority() {
        Map<Integer, Double> ranks = new HashMap<Integer, Double>();
        for (int index = topological().size() - 1; index >= 0; index--) {
            int taskId = topological().get(index);
            double successor = 0.0;
            for (Integer child : children.get(taskId)) {
                successor = Math.max(successor, communication(taskId, child) + ranks.getOrDefault(child, 0.0));
            }
            ranks.put(taskId, averageCompute(taskId) + successor);
        }
        List<Integer> result = new ArrayList<Integer>(topological());
        result.sort((first, second) -> {
            int byRank = Double.compare(ranks.get(second), ranks.get(first));
            return byRank != 0 ? byRank : Integer.compare(first, second);
        });
        return result;
    }

    private List<Integer> topologicalCache;

    private List<Integer> topological() {
        if (topologicalCache != null) {
            return topologicalCache;
        }
        Map<Integer, Integer> remaining = new HashMap<Integer, Integer>();
        for (IcpcpTask task : problem.getTasks()) {
            remaining.put(task.getTaskId(), task.getParentTaskIds().size());
        }
        List<Integer> result = new ArrayList<Integer>();
        while (result.size() < problem.getTasks().size()) {
            Integer ready = null;
            for (IcpcpTask task : problem.getTasks()) {
                if (remaining.containsKey(task.getTaskId()) && remaining.get(task.getTaskId()) == 0) {
                    ready = task.getTaskId();
                    break;
                }
            }
            if (ready == null) {
                throw new IllegalArgumentException("IC-PCP task graph contains a cycle");
            }
            result.add(ready);
            remaining.remove(ready);
            for (Integer child : children.get(ready)) {
                if (child != END_ID) {
                    remaining.put(child, remaining.get(child) - 1);
                }
            }
        }
        topologicalCache = result;
        return result;
    }

    private double averageCompute(int taskId) {
        double total = 0.0;
        for (double seconds : problem.getTask(taskId).getComputeSecondsByVm().values()) {
            total += seconds;
        }
        return total / problem.getTask(taskId).getComputeSecondsByVm().size();
    }

    private static final class Candidate {
        private final int vmId;
        private final Map<Integer, Integer> assignments;
        private final Map<Integer, IcpcpSchedule.Entry> entries;
        private final double costDelta;
        private final double slack;

        private Candidate(
                int vmId,
                Map<Integer, Integer> assignments,
                Map<Integer, IcpcpSchedule.Entry> entries,
                double costDelta,
                double slack) {
            this.vmId = vmId;
            this.assignments = assignments;
            this.entries = entries;
            this.costDelta = costDelta;
            this.slack = slack;
        }
    }
}
