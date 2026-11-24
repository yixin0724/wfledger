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
            beam = selectBest(expanded, beamWidth);
        }
        List<Candidate> finalFront = nonDominated(beam);
        List<MoHeftSchedule> front = new ArrayList<MoHeftSchedule>();
        for (Candidate candidate : finalFront) {
            front.add(candidate.toSchedule());
        }
        return new MoHeftResult(front, balanced(finalFront).toSchedule());
    }

    /**
     * Keeps the {@code limit} best candidates by non-dominated sorting and crowding distance.
     *
     * <p>FGCS-R3 (2026-11-24) M5 root cause: the beam was {@code selectDiverse(nonDominated(expanded))},
     * so it shrank below {@code K} whenever the first front had fewer than {@code K} members, and the
     * discarded dominated partial schedules could no longer grow into non-dominated complete schedules.
     * Durillo, Nae and Prodan (FGCS 36, 2014) keep the K best partial schedules ranked by non-dominated
     * sorting with crowding distance (NSGA-II environmental selection): whole fronts are taken in rank
     * order while they fit, and the front that overflows is truncated by descending crowding distance
     * (ties by ascending candidate ID, for determinism).
     */
    private static List<Candidate> selectBest(List<Candidate> candidates, int limit) {
        List<Candidate> remaining = new ArrayList<Candidate>(candidates);
        List<Candidate> selected = new ArrayList<Candidate>();
        while (selected.size() < limit && !remaining.isEmpty()) {
            List<Candidate> front = nonDominated(remaining);
            remaining.removeAll(front);
            if (selected.size() + front.size() <= limit) {
                selected.addAll(front);
            } else {
                selected.addAll(byCrowding(front, limit - selected.size()));
            }
        }
        return selected;
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

    private static List<Candidate> byCrowding(List<Candidate> front, int count) {
        Map<Long, Double> distance = new HashMap<Long, Double>();
        for (Candidate candidate : front) {
            distance.put(candidate.id, 0.0);
        }
        addDistances(front, distance, true);
        addDistances(front, distance, false);
        List<Candidate> ordered = new ArrayList<Candidate>(front);
        ordered.sort((first, second) -> {
            int byDistance = Double.compare(distance.get(second.id), distance.get(first.id));
            return byDistance != 0 ? byDistance : Long.compare(first.id, second.id);
        });
        return new ArrayList<Candidate>(ordered.subList(0, count));
    }

    private static void addDistances(List<Candidate> candidates, Map<Long, Double> distance, boolean makespan) {
        List<Candidate> ordered = new ArrayList<Candidate>(candidates);
        ordered.sort(
                Comparator.<Candidate>comparingDouble(candidate -> makespan ? candidate.makespan : candidate.energy)
                        .thenComparingLong(candidate -> candidate.id));
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
                distance.put(candidate.id, distance.get(candidate.id) + contribution);
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
        private final Map<Integer, List<MoHeftSchedule.Entry>> reservations;
        private final double makespan;
        private final double energy;

        private Candidate(
                long id,
                Map<Integer, MoHeftSchedule.Entry> entries,
                Map<Integer, List<MoHeftSchedule.Entry>> reservations,
                double makespan,
                double energy) {
            this.id = id;
            this.entries = entries;
            this.reservations = reservations;
            this.makespan = makespan;
            this.energy = energy;
        }

        private static Candidate empty() {
            return new Candidate(
                    0L,
                    Collections.<Integer, MoHeftSchedule.Entry>emptyMap(),
                    Collections.<Integer, List<MoHeftSchedule.Entry>>emptyMap(),
                    0.0,
                    0.0);
        }

        private Candidate append(GreenHeftTask task, int vmId, GreenHeftProblem problem, long id) {
            Map<Integer, MoHeftSchedule.Entry> nextEntries = new LinkedHashMap<Integer, MoHeftSchedule.Entry>(entries);
            Map<Integer, List<MoHeftSchedule.Entry>> nextReservations =
                    new LinkedHashMap<Integer, List<MoHeftSchedule.Entry>>();
            for (Map.Entry<Integer, List<MoHeftSchedule.Entry>> entry : reservations.entrySet()) {
                nextReservations.put(entry.getKey(), new ArrayList<MoHeftSchedule.Entry>(entry.getValue()));
            }
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
            double start = parentReady;
            List<MoHeftSchedule.Entry> vmReservations = nextReservations.get(vmId);
            if (vmReservations != null) {
                for (MoHeftSchedule.Entry reservation : vmReservations) {
                    // FGCS-R3 (2026-11-24) m7: exact gap test, as in the SHARED planners.
                    if (start + task.getComputeSecondsByVm().get(vmId) <= reservation.getStartSeconds()) {
                        break;
                    }
                    start = Math.max(start, reservation.getFinishSeconds());
                }
            }
            double finish = start + task.getComputeSecondsByVm().get(vmId);
            double taskEnergy = task.getEnergyJoulesByVm().get(vmId);
            MoHeftSchedule.Entry selected = new MoHeftSchedule.Entry(task.getTaskId(), vmId, start, finish, taskEnergy);
            nextEntries.put(task.getTaskId(), selected);
            nextReservations
                    .computeIfAbsent(vmId, ignored -> new ArrayList<MoHeftSchedule.Entry>())
                    .add(selected);
            nextReservations.get(vmId).sort((first, second) -> {
                int byStart = Double.compare(first.getStartSeconds(), second.getStartSeconds());
                return byStart != 0 ? byStart : Integer.compare(first.getTaskId(), second.getTaskId());
            });
            return new Candidate(id, nextEntries, nextReservations, Math.max(makespan, finish), energy + taskEnergy);
        }

        private MoHeftSchedule toSchedule() {
            return new MoHeftSchedule(entries, makespan, energy);
        }
    }
}
