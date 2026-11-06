package org.workflowsim.planning.networkheft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Network-Aware HEFT with flow stretching and iterative re-scheduling. */
public final class NetworkHeftPlanner {

    private final NetworkHeftFlowStretcher stretcher = new NetworkHeftFlowStretcher();

    public NetworkHeftSchedule schedule(NetworkHeftProblem problem) {
        return schedule(problem, 8);
    }

    public NetworkHeftSchedule schedule(NetworkHeftProblem problem, int iterations) {
        if (iterations < 1) {
            throw new IllegalArgumentException("Network-Aware HEFT iterations must be positive");
        }
        Map<String, Double> modifiedDurations = Collections.emptyMap();
        NetworkHeftSchedule best = null;
        for (int iteration = 0; iteration < iterations; iteration++) {
            NetworkHeftSchedule candidate = heft(problem, modifiedDurations);
            Map<String, Double> stretched = stretcher.stretch(
                    problem.getTasks(),
                    candidate.getMapping(),
                    entries(candidate),
                    problem.getBottleneckCapacityBytesPerSecond());
            NetworkHeftSchedule realistic = realisticSchedule(problem, candidate, stretched);
            if (best == null || realistic.getMakespanSeconds() < best.getMakespanSeconds() - 1.0e-9) {
                best = realistic;
            }
            modifiedDurations = stretched;
        }
        return best;
    }

    private NetworkHeftSchedule heft(NetworkHeftProblem problem, Map<String, Double> modifiedDurations) {
        List<Integer> order = topological(problem);
        Map<Integer, Double> ranks = new HashMap<Integer, Double>();
        for (int index = order.size() - 1; index >= 0; index--) {
            NetworkHeftTask task = problem.getTask(order.get(index));
            double successor = 0.0;
            for (NetworkHeftEdge edge : task.getEdgesByChild().values()) {
                double communication = duration(task.getTaskId(), edge, modifiedDurations);
                successor = Math.max(successor, communication + ranks.getOrDefault(edge.getChildTaskId(), 0.0));
            }
            ranks.put(task.getTaskId(), averageCompute(task) + successor);
        }
        order.sort((first, second) -> {
            int byRank = Double.compare(ranks.get(second), ranks.get(first));
            return byRank != 0 ? byRank : Integer.compare(first, second);
        });
        Map<Integer, NetworkHeftSchedule.Entry> entries = new LinkedHashMap<Integer, NetworkHeftSchedule.Entry>();
        Map<Integer, Integer> mapping = new LinkedHashMap<Integer, Integer>();
        Map<Integer, Double> available = new HashMap<Integer, Double>();
        double makespan = 0.0;
        for (Integer taskId : order) {
            NetworkHeftTask task = problem.getTask(taskId);
            int bestVm = -1;
            double bestStart = Double.POSITIVE_INFINITY;
            double bestFinish = Double.POSITIVE_INFINITY;
            for (Integer vmId : task.getComputeSecondsByVm().keySet()) {
                double ready = 0.0;
                for (Integer parentId : task.getParentTaskIds()) {
                    NetworkHeftSchedule.Entry parent = entries.get(parentId);
                    NetworkHeftEdge edge =
                            problem.getTask(parentId).getEdgesByChild().get(taskId);
                    double communication = parent.getVmId() == vmId ? 0.0 : duration(parentId, edge, modifiedDurations);
                    ready = Math.max(ready, parent.getFinishSeconds() + communication);
                }
                double start = Math.max(available.getOrDefault(vmId, 0.0), ready);
                double finish = start + task.getComputeSecondsByVm().get(vmId);
                if (finish < bestFinish - 1.0e-9 || (Math.abs(finish - bestFinish) <= 1.0e-9 && vmId < bestVm)) {
                    bestVm = vmId;
                    bestStart = start;
                    bestFinish = finish;
                }
            }
            available.put(bestVm, bestFinish);
            entries.put(taskId, new NetworkHeftSchedule.Entry(taskId, bestVm, bestStart, bestFinish));
            mapping.put(taskId, bestVm);
            makespan = Math.max(makespan, bestFinish);
        }
        return new NetworkHeftSchedule(entries, mapping, makespan);
    }

    private NetworkHeftSchedule realisticSchedule(
            NetworkHeftProblem problem, NetworkHeftSchedule candidate, Map<String, Double> stretched) {
        Map<Integer, NetworkHeftSchedule.Entry> entries = new LinkedHashMap<Integer, NetworkHeftSchedule.Entry>();
        Map<Integer, Double> available = new HashMap<Integer, Double>();
        double makespan = 0.0;
        for (Integer taskId : topological(problem)) {
            NetworkHeftTask task = problem.getTask(taskId);
            int vmId = candidate.getVmId(taskId);
            double ready = 0.0;
            for (Integer parentId : task.getParentTaskIds()) {
                NetworkHeftSchedule.Entry parent = entries.get(parentId);
                NetworkHeftEdge edge =
                        problem.getTask(parentId).getEdgesByChild().get(taskId);
                double communication = parent.getVmId() == vmId ? 0.0 : duration(parentId, edge, stretched);
                ready = Math.max(ready, parent.getFinishSeconds() + communication);
            }
            double start = Math.max(available.getOrDefault(vmId, 0.0), ready);
            double finish = start + task.getComputeSecondsByVm().get(vmId);
            available.put(vmId, finish);
            entries.put(taskId, new NetworkHeftSchedule.Entry(taskId, vmId, start, finish));
            makespan = Math.max(makespan, finish);
        }
        return new NetworkHeftSchedule(entries, candidate.getMapping(), makespan);
    }

    private static double duration(int parentTaskId, NetworkHeftEdge edge, Map<String, Double> durations) {
        return durations.getOrDefault(parentTaskId + "->" + edge.getChildTaskId(), edge.getNominalDurationSeconds());
    }

    private static Map<Integer, NetworkHeftSchedule.Entry> entries(NetworkHeftSchedule schedule) {
        Map<Integer, NetworkHeftSchedule.Entry> result = new LinkedHashMap<Integer, NetworkHeftSchedule.Entry>();
        for (NetworkHeftSchedule.Entry entry : schedule.getTaskPlans()) {
            result.put(entry.getTaskId(), entry);
        }
        return result;
    }

    private static List<Integer> topological(NetworkHeftProblem problem) {
        Map<Integer, Integer> remaining = new HashMap<Integer, Integer>();
        for (NetworkHeftTask task : problem.getTasks()) {
            remaining.put(task.getTaskId(), task.getParentTaskIds().size());
        }
        List<Integer> result = new ArrayList<Integer>();
        while (result.size() < problem.getTasks().size()) {
            Integer ready = null;
            for (NetworkHeftTask task : problem.getTasks()) {
                if (remaining.containsKey(task.getTaskId()) && remaining.get(task.getTaskId()) == 0) {
                    ready = task.getTaskId();
                    break;
                }
            }
            if (ready == null) {
                throw new IllegalArgumentException("Network-Aware HEFT graph contains a cycle");
            }
            result.add(ready);
            remaining.remove(ready);
            for (Integer child : problem.getTask(ready).getEdgesByChild().keySet()) {
                remaining.put(child, remaining.get(child) - 1);
            }
        }
        return result;
    }

    private static double averageCompute(NetworkHeftTask task) {
        double total = 0.0;
        for (double seconds : task.getComputeSecondsByVm().values()) {
            total += seconds;
        }
        return total / task.getComputeSecondsByVm().size();
    }
}
