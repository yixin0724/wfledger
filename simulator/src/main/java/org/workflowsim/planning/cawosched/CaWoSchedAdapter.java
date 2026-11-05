package org.workflowsim.planning.cawosched;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.planning.SharedStorageDagPlanTrace;
import org.workflowsim.platform.PlatformProfile;

/** Converts an existing SHARED plan into the compute-only fixed-mapping CaWoSched input. */
public final class CaWoSchedAdapter {

    private CaWoSchedAdapter() {}

    /**
     * Uses the trace's VM mapping and per-VM order, raw compute length / VM MIPS, and the declared
     * VM power values. Data stage-in, communication processors, and communication energy are not
     * part of this adapter; those remain an explicitly excluded adaptation boundary.
     */
    public static CaWoSchedPlanner fromSharedStoragePlan(
            List<Task> tasks,
            List<CondorVM> vms,
            PlatformProfile platform,
            SharedStorageDagPlanTrace trace,
            List<PlatformProfile.CarbonWindow> windows,
            double deadlineSeconds) {
        if (tasks == null || vms == null || platform == null || trace == null) {
            throw new IllegalArgumentException("SHARED plan, VMs, platform, and trace are required");
        }
        Map<Integer, CondorVM> vmById = new LinkedHashMap<Integer, CondorVM>();
        for (CondorVM vm : vms) {
            vmById.put(vm.getId(), vm);
        }
        Map<Integer, PlatformProfile.VmSpec> specs = new LinkedHashMap<Integer, PlatformProfile.VmSpec>();
        Map<Integer, Double> declaredIdle = new LinkedHashMap<Integer, Double>();
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            specs.put(spec.getId(), spec);
            declaredIdle.put(spec.getId(), spec.getIdlePowerWatts());
        }
        Map<Integer, Integer> orderByTask = perVmOrder(trace);
        List<Task> sorted = new ArrayList<Task>(tasks);
        Collections.sort(sorted, Comparator.comparingInt(Task::getCloudletId));
        List<CaWoSchedTask> converted = new ArrayList<CaWoSchedTask>();
        for (Task task : sorted) {
            SharedStorageDagPlanTrace.TaskPlan plan = trace.getTaskPlan(task.getCloudletId());
            if (plan == null) {
                throw new IllegalArgumentException("SHARED trace misses task " + task.getCloudletId());
            }
            CondorVM vm = vmById.get(plan.getVmId());
            PlatformProfile.VmSpec spec = specs.get(plan.getVmId());
            if (vm == null || spec == null) {
                throw new IllegalArgumentException("SHARED trace references undeclared VM " + plan.getVmId());
            }
            List<Integer> parents = new ArrayList<Integer>();
            for (Task parent : task.getParentList()) {
                parents.add(parent.getCloudletId());
            }
            Collections.sort(parents);
            converted.add(new CaWoSchedTask(
                    task.getCloudletId(),
                    plan.getVmId(),
                    orderByTask.get(task.getCloudletId()),
                    task.getCloudletLength() / vm.getMips(),
                    spec.getIdlePowerWatts(),
                    spec.getMaxPowerWatts(),
                    parents));
        }
        return new CaWoSchedPlanner(converted, windows, deadlineSeconds, declaredIdle);
    }

    private static Map<Integer, Integer> perVmOrder(SharedStorageDagPlanTrace trace) {
        Map<Integer, List<SharedStorageDagPlanTrace.TaskPlan>> byVm =
                new LinkedHashMap<Integer, List<SharedStorageDagPlanTrace.TaskPlan>>();
        for (SharedStorageDagPlanTrace.TaskPlan plan : trace.getTaskPlans().values()) {
            byVm.computeIfAbsent(plan.getVmId(), key -> new ArrayList<SharedStorageDagPlanTrace.TaskPlan>())
                    .add(plan);
        }
        Map<Integer, Integer> result = new LinkedHashMap<Integer, Integer>();
        for (List<SharedStorageDagPlanTrace.TaskPlan> plans : byVm.values()) {
            Collections.sort(plans, new Comparator<SharedStorageDagPlanTrace.TaskPlan>() {
                @Override
                public int compare(
                        SharedStorageDagPlanTrace.TaskPlan first, SharedStorageDagPlanTrace.TaskPlan second) {
                    int byStart = Double.compare(first.getPlannedStartSeconds(), second.getPlannedStartSeconds());
                    return byStart != 0 ? byStart : Integer.compare(first.getTaskId(), second.getTaskId());
                }
            });
            for (int index = 0; index < plans.size(); index++) {
                result.put(plans.get(index).getTaskId(), index);
            }
        }
        return result;
    }
}
