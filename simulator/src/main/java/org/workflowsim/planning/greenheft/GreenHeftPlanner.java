package org.workflowsim.planning.greenheft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GreenHeftPlanner {

    public GreenHeftSchedule schedule(GreenHeftProblem problem) {
        Map<Integer, Double> ranks = upwardRanks(problem);
        List<GreenHeftTask> ordered = new ArrayList<GreenHeftTask>(problem.getTasks());
        ordered.sort((first, second) -> {
            int byRank = Double.compare(ranks.get(second.getTaskId()), ranks.get(first.getTaskId()));
            return byRank != 0 ? byRank : Integer.compare(first.getTaskId(), second.getTaskId());
        });
        Map<Integer, GreenHeftSchedule.Entry> scheduled = new LinkedHashMap<Integer, GreenHeftSchedule.Entry>();
        Map<Integer, List<GreenHeftSchedule.Entry>> reservations =
                new HashMap<Integer, List<GreenHeftSchedule.Entry>>();
        double makespan = 0.0;
        double energy = 0.0;
        for (GreenHeftTask task : ordered) {
            int bestVm = -1;
            double bestEnergy = Double.POSITIVE_INFINITY;
            double bestFinish = Double.POSITIVE_INFINITY;
            double bestStart = Double.POSITIVE_INFINITY;
            for (Integer vmId : task.getComputeSecondsByVm().keySet()) {
                double parentReady = 0.0;
                for (Integer parentId : task.getParentTaskIds()) {
                    GreenHeftTask parentTask = problem.getTask(parentId);
                    GreenHeftSchedule.Entry parent = scheduled.get(parentId);
                    if (parent == null) {
                        throw new IllegalArgumentException("GreenHEFT rank order is not topological");
                    }
                    double transfer = parent.getVmId() == vmId
                            ? 0.0
                            : parentTask.getCommunicationSecondsByChild().getOrDefault(task.getTaskId(), 0.0);
                    parentReady = Math.max(parentReady, parent.getFinishSeconds() + transfer);
                }
                double start = earliestStart(
                        reservations.get(vmId),
                        parentReady,
                        task.getComputeSecondsByVm().get(vmId));
                double finish = start + task.getComputeSecondsByVm().get(vmId);
                double taskEnergy = task.getEnergyJoulesByVm().get(vmId);
                if (taskEnergy < bestEnergy - 1.0e-9
                        || (Math.abs(taskEnergy - bestEnergy) <= 1.0e-9 && finish < bestFinish - 1.0e-9)
                        || (Math.abs(taskEnergy - bestEnergy) <= 1.0e-9
                                && Math.abs(finish - bestFinish) <= 1.0e-9
                                && vmId < bestVm)) {
                    bestVm = vmId;
                    bestEnergy = taskEnergy;
                    bestFinish = finish;
                    bestStart = start;
                }
            }
            GreenHeftSchedule.Entry selected =
                    new GreenHeftSchedule.Entry(task.getTaskId(), bestVm, bestStart, bestFinish, bestEnergy);
            reservations
                    .computeIfAbsent(bestVm, ignored -> new ArrayList<GreenHeftSchedule.Entry>())
                    .add(selected);
            reservations.get(bestVm).sort((first, second) -> {
                int byStart = Double.compare(first.getStartSeconds(), second.getStartSeconds());
                return byStart != 0 ? byStart : Integer.compare(first.getTaskId(), second.getTaskId());
            });
            scheduled.put(task.getTaskId(), selected);
            energy += bestEnergy;
            makespan = Math.max(makespan, bestFinish);
        }
        return new GreenHeftSchedule(scheduled, makespan, energy);
    }

    private static double earliestStart(List<GreenHeftSchedule.Entry> reservations, double ready, double duration) {
        double start = ready;
        if (reservations == null) {
            return start;
        }
        for (GreenHeftSchedule.Entry reservation : reservations) {
            if (start + duration <= reservation.getStartSeconds() + 1.0e-9) {
                break;
            }
            start = Math.max(start, reservation.getFinishSeconds());
        }
        return start;
    }

    private Map<Integer, Double> upwardRanks(GreenHeftProblem problem) {
        Map<Integer, Double> ranks = new HashMap<Integer, Double>();
        List<GreenHeftTask> topological = new ArrayList<GreenHeftTask>();
        Map<Integer, Integer> remaining = new HashMap<Integer, Integer>();
        for (GreenHeftTask task : problem.getTasks()) {
            remaining.put(task.getTaskId(), task.getParentTaskIds().size());
        }
        while (topological.size() < problem.getTasks().size()) {
            GreenHeftTask ready = null;
            for (GreenHeftTask task : problem.getTasks()) {
                if (remaining.containsKey(task.getTaskId()) && remaining.get(task.getTaskId()) == 0) {
                    ready = task;
                    break;
                }
            }
            if (ready == null) {
                throw new IllegalArgumentException("GreenHEFT task graph contains a cycle");
            }
            topological.add(ready);
            remaining.remove(ready.getTaskId());
            for (Integer child : ready.getCommunicationSecondsByChild().keySet()) {
                remaining.put(child, remaining.get(child) - 1);
            }
        }
        for (int index = topological.size() - 1; index >= 0; index--) {
            GreenHeftTask task = topological.get(index);
            double successor = 0.0;
            for (Map.Entry<Integer, Double> child :
                    task.getCommunicationSecondsByChild().entrySet()) {
                successor = Math.max(successor, child.getValue() + ranks.getOrDefault(child.getKey(), 0.0));
            }
            ranks.put(task.getTaskId(), averageCompute(task) + successor);
        }
        return ranks;
    }

    private static double averageCompute(GreenHeftTask task) {
        double sum = 0.0;
        for (double seconds : task.getComputeSecondsByVm().values()) {
            sum += seconds;
        }
        return sum / task.getComputeSecondsByVm().size();
    }
}
