package org.workflowsim.planning.dvfs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.platform.PlatformProfile;

/**
 * Greedy deadline-constrained DVFS slack allocation over a fixed SHARED HEFT mapping and order.
 *
 * <p>Runtime law mirrored (FGCS-R3, 2026-11-24, m6, verified against {@code WorkflowScheduler.assignFrequencyTier},
 * {@code CloudletSchedulerSpaceShared.cloudletSubmit} and {@code WorkflowDatacenter.updateTaskExecTime}): the
 * declared work is rescaled to {@code round(L * (vmMips / tierMips))} MI and executed at the VM capacity, and
 * the SHARED stage-in MI are added at the VM capacity, so stage-in seconds are tier-independent; the per-tier
 * energy charges the selected tier's {@code P_max - P_idle(default)} over the whole Job envelope, stage-in
 * included. Root cause fixed: the planner used the exact {@code L / tierMips}, which differs from the
 * runtime's whole-MI rescaling (e.g. 0.6667 s versus 0.6665 s for 1000 MI at 1500 of 2000 MIPS), so DVFS
 * plans did not mirror execution. The stage-in convention was already consistent and is unchanged.
 */
public final class DvfsSlackOptimizer {

    /**
     * Legacy entry point: each VM's capacity is taken to be its default (first) tier speed.
     */
    public DvfsSlackSchedule optimize(
            List<DvfsSlackTask> tasks,
            Map<Integer, List<PlatformProfile.FrequencyTier>> tiersByVm,
            double deadlineSeconds) {
        if (tiersByVm == null) {
            throw new IllegalArgumentException("DVFS requires tasks, tier tables, and a deadline");
        }
        Map<Integer, Double> capacity = new HashMap<Integer, Double>();
        for (Map.Entry<Integer, List<PlatformProfile.FrequencyTier>> entry : tiersByVm.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                throw new IllegalArgumentException("No DVFS tiers for VM " + entry.getKey());
            }
            capacity.put(entry.getKey(), entry.getValue().get(0).getMips());
        }
        return optimize(tasks, tiersByVm, capacity, deadlineSeconds);
    }

    public DvfsSlackSchedule optimize(
            List<DvfsSlackTask> tasks,
            Map<Integer, List<PlatformProfile.FrequencyTier>> tiersByVm,
            Map<Integer, Double> vmCapacityMips,
            double deadlineSeconds) {
        if (tasks == null
                || tasks.isEmpty()
                || tiersByVm == null
                || vmCapacityMips == null
                || !(deadlineSeconds > 0.0)) {
            throw new IllegalArgumentException("DVFS requires tasks, tier tables, VM capacities, and a deadline");
        }
        this.vmCapacityMips = vmCapacityMips;
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

    private Map<Integer, Double> vmCapacityMips;

    private double scaledWorkSeconds(DvfsSlackTask task, PlatformProfile.FrequencyTier tier) {
        Double capacity = vmCapacityMips.get(task.getVmId());
        if (capacity == null || !(capacity > 0.0) || !Double.isFinite(capacity)) {
            throw new IllegalArgumentException("No positive DVFS capacity for VM " + task.getVmId());
        }
        double factor = capacity / tier.getMips();
        return Math.round(task.getWorkMi() * factor) / capacity;
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
            double finish = start + task.getStageInSeconds() + scaledWorkSeconds(task, tier);
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
