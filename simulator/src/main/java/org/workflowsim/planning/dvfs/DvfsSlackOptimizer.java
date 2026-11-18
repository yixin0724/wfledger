package org.workflowsim.planning.dvfs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.platform.PlatformProfile;

public final class DvfsSlackOptimizer {

    public DvfsSlackSchedule optimize(
            List<DvfsSlackTask> tasks,
            Map<Integer, List<PlatformProfile.FrequencyTier>> tiersByVm,
            double deadlineSeconds) {
        if (tasks == null || tasks.isEmpty() || tiersByVm == null || !(deadlineSeconds > 0.0)) {
            throw new IllegalArgumentException("DVFS requires tasks, tier tables, and a deadline");
        }
        Map<Integer, Integer> tierIndex = new HashMap<Integer, Integer>();
        for (DvfsSlackTask task : tasks) {
            if (!tiersByVm.containsKey(task.getVmId())
                    || tiersByVm.get(task.getVmId()).isEmpty()) {
                throw new IllegalArgumentException("No DVFS tiers for VM " + task.getVmId());
            }
            tierIndex.put(task.getTaskId(), 0);
        }
        DvfsSlackSchedule current = evaluate(tasks, tiersByVm, tierIndex);
        if (current.getMakespanSeconds() > deadlineSeconds + 1.0e-9) {
            throw new IllegalArgumentException("Initial DVFS schedule misses the deadline");
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (DvfsSlackTask task : tasks) {
                List<PlatformProfile.FrequencyTier> tiers = tiersByVm.get(task.getVmId());
                int currentIndex = tierIndex.get(task.getTaskId());
                int bestIndex = currentIndex;
                double bestEnergy = current.getTotalEnergyJoules();
                for (int index = currentIndex + 1; index < tiers.size(); index++) {
                    Map<Integer, Integer> trial = new HashMap<Integer, Integer>(tierIndex);
                    trial.put(task.getTaskId(), index);
                    DvfsSlackSchedule candidate = evaluate(tasks, tiersByVm, trial);
                    if (candidate.getMakespanSeconds() <= deadlineSeconds + 1.0e-9
                            && candidate.getTotalEnergyJoules() < bestEnergy - 1.0e-9) {
                        bestIndex = index;
                        bestEnergy = candidate.getTotalEnergyJoules();
                    }
                }
                if (bestIndex != currentIndex) {
                    tierIndex.put(task.getTaskId(), bestIndex);
                    current = evaluate(tasks, tiersByVm, tierIndex);
                    changed = true;
                }
            }
        }
        return current;
    }

    private DvfsSlackSchedule evaluate(
            List<DvfsSlackTask> tasks,
            Map<Integer, List<PlatformProfile.FrequencyTier>> tiersByVm,
            Map<Integer, Integer> tierIndex) {
        Map<Integer, DvfsSlackTask> byId = new HashMap<Integer, DvfsSlackTask>();
        for (DvfsSlackTask task : tasks) {
            byId.put(task.getTaskId(), task);
        }
        List<DvfsSlackTask> ordered = new ArrayList<DvfsSlackTask>(tasks);

        Map<Integer, DvfsSlackSchedule.Entry> entries = new LinkedHashMap<Integer, DvfsSlackSchedule.Entry>();
        Map<Integer, Double> available = new HashMap<Integer, Double>();
        double makespan = 0.0;
        for (DvfsSlackTask task : ordered) {
            int index = tierIndex.get(task.getTaskId());
            PlatformProfile.FrequencyTier tier = tiersByVm.get(task.getVmId()).get(index);
            double ready = 0.0;
            for (Integer parentId : task.getParentTaskIds()) {
                DvfsSlackTask parent = byId.get(parentId);
                if (parent == null) {
                    throw new IllegalArgumentException("Unknown DVFS parent " + parentId);
                }
                DvfsSlackSchedule.Entry parentEntry = entries.get(parentId);
                if (parentEntry == null) {
                    throw new IllegalArgumentException("DVFS tasks are not topologically ordered");
                }
                ready = Math.max(ready, parentEntry.getFinishSeconds());
            }
            double start = Math.max(
                    task.getEarliestStartSeconds(), Math.max(available.getOrDefault(task.getVmId(), 0.0), ready));
            double finish = start + task.getStageInSeconds() + task.getWorkMi() / tier.getMips();
            entries.put(
                    task.getTaskId(),
                    new DvfsSlackSchedule.Entry(task.getTaskId(), task.getVmId(), tier.getMips(), start, finish));
            available.put(task.getVmId(), finish);
            makespan = Math.max(makespan, finish);
        }
        double energy = 0.0;
        for (Map.Entry<Integer, List<PlatformProfile.FrequencyTier>> entry : tiersByVm.entrySet()) {
            PlatformProfile.FrequencyTier defaultTier = entry.getValue().get(0);
            energy += defaultTier.getIdlePowerWatts() * makespan;
        }
        for (DvfsSlackTask task : ordered) {
            DvfsSlackSchedule.Entry plan = entries.get(task.getTaskId());
            PlatformProfile.FrequencyTier tier = tiersByVm.get(task.getVmId()).get(tierIndex.get(task.getTaskId()));
            PlatformProfile.FrequencyTier defaultTier =
                    tiersByVm.get(task.getVmId()).get(0);
            energy += (tier.getMaxPowerWatts() - defaultTier.getIdlePowerWatts())
                    * (plan.getFinishSeconds() - plan.getStartSeconds());
        }
        return new DvfsSlackSchedule(entries, makespan, energy);
    }
}
