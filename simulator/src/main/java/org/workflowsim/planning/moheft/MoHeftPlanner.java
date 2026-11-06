package org.workflowsim.planning.moheft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.planning.greenheft.GreenHeftPlanner;
import org.workflowsim.planning.greenheft.GreenHeftProblem;
import org.workflowsim.planning.greenheft.GreenHeftSchedule;
import org.workflowsim.planning.greenheft.GreenHeftTask;

/** MOHEFT beam search over task-to-VM candidates with a makespan-energy Pareto front. */
public final class MoHeftPlanner {

    private final int beamWidth;

    public MoHeftPlanner(int beamWidth) {
        if (beamWidth < 2) {
            throw new IllegalArgumentException("MOHEFT beam width must be at least 2");
        }
        this.beamWidth = beamWidth;
    }

    public MoHeftResult schedule(GreenHeftProblem problem) {
        GreenHeftSchedule orderSchedule = new GreenHeftPlanner().schedule(problem);
        List<Candidate> beam = new ArrayList<Candidate>();
        beam.add(Candidate.empty());
        long nextId = 1L;
        for (GreenHeftSchedule.Entry order : orderSchedule.getTaskPlans()) {
            GreenHeftTask task = problem.getTask(order.getTaskId());
            List<Candidate> expanded = new ArrayList<Candidate>();
            for (Candidate candidate : beam) {
                for (Integer vmId : task.getComputeSecondsByVm().keySet()) {
                    expanded.add(candidate.append(task, vmId, problem, nextId++));
                }
            }
            beam = selectDiverse(nonDominated(expanded), beamWidth);
        }
        List<MoHeftSchedule> front = new ArrayList<MoHeftSchedule>();
        for (Candidate candidate : beam) {
            front.add(candidate.toSchedule());
        }
        return new MoHeftResult(front, balanced(beam).toSchedule());
    }

    private static List<Candidate> nonDominated(List<Candidate> candidates) {
        List<Candidate> result = new ArrayList<Candidate>();
        for (Candidate candidate : candidates) {
            boolean dominated = false;
            for (Candidate other : candidates) {
                if (dominates(other, candidate)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static boolean dominates(Candidate first, Candidate second) {
        boolean noWorse = first.makespan <= second.makespan && first.energy <= second.energy;
        boolean strictlyBetter = first.makespan < second.makespan || first.energy < second.energy;
        return noWorse && strictlyBetter;
    }

    private static List<Candidate> selectDiverse(List<Candidate> candidates, int limit) {
        if (candidates.size() <= limit) {
            return candidates;
        }
        Map<Long, Double> distance = new HashMap<Long, Double>();
        addDistances(candidates, distance, true);
        addDistances(candidates, distance, false);
        List<Candidate> selected = new ArrayList<Candidate>();
        addExtreme(selected, candidates, true, true);
        addExtreme(selected, candidates, true, false);
        addExtreme(selected, candidates, false, true);
        addExtreme(selected, candidates, false, false);
        if (selected.size() >= limit) {
            return new ArrayList<Candidate>(selected.subList(0, limit));
        }
        List<Candidate> ordered = new ArrayList<Candidate>(candidates);
        ordered.sort((first, second) -> {
            int byDistance = Double.compare(distance.get(second.id), distance.get(first.id));
            return byDistance != 0 ? byDistance : Long.compare(first.id, second.id);
        });
        for (Candidate candidate : ordered) {
            if (!selected.contains(candidate)) {
                selected.add(candidate);
                if (selected.size() == limit) {
                    break;
                }
            }
        }
        return selected;
    }

    private static void addExtreme(
            List<Candidate> selected, List<Candidate> candidates, boolean makespan, boolean minimum) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (best == null
                    || (makespan && (minimum ? candidate.makespan < best.makespan : candidate.makespan > best.makespan))
                    || (!makespan && (minimum ? candidate.energy < best.energy : candidate.energy > best.energy))) {
                best = candidate;
            }
        }
        if (best != null && !selected.contains(best)) {
            selected.add(best);
        }
    }

    private static void addDistances(List<Candidate> candidates, Map<Long, Double> distance, boolean makespan) {
        List<Candidate> ordered = new ArrayList<Candidate>(candidates);
        ordered.sort(Comparator.comparingDouble(candidate -> makespan ? candidate.makespan : candidate.energy));
        double first = makespan ? ordered.get(0).makespan : ordered.get(0).energy;
        double last = makespan ? ordered.get(ordered.size() - 1).makespan : ordered.get(ordered.size() - 1).energy;
        double range = last - first;
        for (int index = 0; index < ordered.size(); index++) {
            Candidate candidate = ordered.get(index);
            if (index == 0 || index == ordered.size() - 1) {
                distance.put(candidate.id, Double.POSITIVE_INFINITY);
            } else {
                double previous = makespan ? ordered.get(index - 1).makespan : ordered.get(index - 1).energy;
                double next = makespan ? ordered.get(index + 1).makespan : ordered.get(index + 1).energy;
                double contribution = range == 0.0 ? 0.0 : (next - previous) / range;
                distance.put(candidate.id, distance.getOrDefault(candidate.id, 0.0) + contribution);
            }
        }
    }

    private static Candidate balanced(List<Candidate> candidates) {
        double minMakespan = Double.POSITIVE_INFINITY;
        double maxMakespan = Double.NEGATIVE_INFINITY;
        double minEnergy = Double.POSITIVE_INFINITY;
        double maxEnergy = Double.NEGATIVE_INFINITY;
        for (Candidate candidate : candidates) {
            minMakespan = Math.min(minMakespan, candidate.makespan);
            maxMakespan = Math.max(maxMakespan, candidate.makespan);
            minEnergy = Math.min(minEnergy, candidate.energy);
            maxEnergy = Math.max(maxEnergy, candidate.energy);
        }
        Candidate best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Candidate candidate : candidates) {
            double makespanPart =
                    maxMakespan == minMakespan ? 0.0 : (candidate.makespan - minMakespan) / (maxMakespan - minMakespan);
            double energyPart = maxEnergy == minEnergy ? 0.0 : (candidate.energy - minEnergy) / (maxEnergy - minEnergy);
            double distance = Math.sqrt(makespanPart * makespanPart + energyPart * energyPart);
            if (distance < bestDistance - 1.0e-12
                    || (Math.abs(distance - bestDistance) <= 1.0e-12 && candidate.makespan < best.makespan)
                    || (Math.abs(distance - bestDistance) <= 1.0e-12
                            && candidate.makespan == best.makespan
                            && candidate.energy < best.energy)
                    || (Math.abs(distance - bestDistance) <= 1.0e-12
                            && candidate.makespan == best.makespan
                            && candidate.energy == best.energy
                            && candidate.id < best.id)) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static final class Candidate {
        private final long id;
        private final Map<Integer, MoHeftSchedule.Entry> entries;
        private final Map<Integer, Double> available;
        private final double makespan;
        private final double energy;

        private Candidate(
                long id,
                Map<Integer, MoHeftSchedule.Entry> entries,
                Map<Integer, Double> available,
                double makespan,
                double energy) {
            this.id = id;
            this.entries = entries;
            this.available = available;
            this.makespan = makespan;
            this.energy = energy;
        }

        private static Candidate empty() {
            return new Candidate(
                    0L,
                    Collections.<Integer, MoHeftSchedule.Entry>emptyMap(),
                    Collections.<Integer, Double>emptyMap(),
                    0.0,
                    0.0);
        }

        private Candidate append(GreenHeftTask task, int vmId, GreenHeftProblem problem, long id) {
            Map<Integer, MoHeftSchedule.Entry> nextEntries = new LinkedHashMap<Integer, MoHeftSchedule.Entry>(entries);
            Map<Integer, Double> nextAvailable = new HashMap<Integer, Double>(available);
            double parentReady = 0.0;
            for (Integer parentId : task.getParentTaskIds()) {
                MoHeftSchedule.Entry parent = nextEntries.get(parentId);
                if (parent == null) {
                    throw new IllegalArgumentException("MOHEFT task order is not topological");
                }
                GreenHeftTask parentTask = problem.getTask(parentId);
                double transfer = parent.getVmId() == vmId
                        ? 0.0
                        : parentTask.getCommunicationSecondsByChild().getOrDefault(task.getTaskId(), 0.0);
                parentReady = Math.max(parentReady, parent.getFinishSeconds() + transfer);
            }
            double start = Math.max(nextAvailable.getOrDefault(vmId, 0.0), parentReady);
            double finish = start + task.getComputeSecondsByVm().get(vmId);
            double taskEnergy = task.getEnergyJoulesByVm().get(vmId);
            nextEntries.put(
                    task.getTaskId(), new MoHeftSchedule.Entry(task.getTaskId(), vmId, start, finish, taskEnergy));
            nextAvailable.put(vmId, finish);
            return new Candidate(id, nextEntries, nextAvailable, Math.max(makespan, finish), energy + taskEnergy);
        }

        private MoHeftSchedule toSchedule() {
            return new MoHeftSchedule(entries, makespan, energy);
        }
    }
}
