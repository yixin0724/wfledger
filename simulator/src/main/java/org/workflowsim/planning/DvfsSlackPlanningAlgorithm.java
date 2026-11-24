package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.Task;
import org.workflowsim.planning.dvfs.DvfsSlackOptimizer;
import org.workflowsim.planning.dvfs.DvfsSlackSchedule;
import org.workflowsim.planning.dvfs.DvfsSlackTask;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DvfsSlackRegistry;

public final class DvfsSlackPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private DvfsSlackSchedule lastSchedule;

    public DvfsSlackPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("DVFS_SLACK requires a PlanningContext");
        }
        this.context = context;
    }

    @Override
    public void run() {
        context.validateSharedStorageStaticDag();
        double deadline = context.getDeadlineSeconds();
        if (!(deadline > 0.0)) {
            throw new IllegalArgumentException("DVFS_SLACK requires a positive workflow deadline");
        }
        SharedStorageHeftPlanningAlgorithm base = new SharedStorageHeftPlanningAlgorithm(context);
        base.setTaskList(getTaskList());
        base.setVmList(getVmList());
        base.run();
        SharedStorageDagPlanTrace allocation = base.getLastPlanTrace();
        Map<Integer, List<PlatformProfile.FrequencyTier>> tiers =
                new LinkedHashMap<Integer, List<PlatformProfile.FrequencyTier>>();
        for (PlatformProfile.VmSpec spec : context.getPlatform().getVms()) {
            if (!spec.hasFrequencyTiers()) {
                throw new IllegalArgumentException("DVFS_SLACK requires frequency tiers on every VM");
            }
            tiers.put(spec.getId(), spec.getFrequencyTiers());
        }
        Map<Integer, Task> tasksById = new LinkedHashMap<Integer, Task>();
        for (Task task : getTaskList()) {
            tasksById.put(task.getCloudletId(), task);
        }
        List<SharedStorageDagPlanTrace.TaskPlan> ordered = new ArrayList<SharedStorageDagPlanTrace.TaskPlan>(
                allocation.getTaskPlans().values());
        Collections.sort(
                ordered,
                Comparator.comparingDouble(SharedStorageDagPlanTrace.TaskPlan::getPlannedStartSeconds)
                        .thenComparingInt(SharedStorageDagPlanTrace.TaskPlan::getTaskId));
        List<DvfsSlackTask> input = new ArrayList<DvfsSlackTask>();
        for (SharedStorageDagPlanTrace.TaskPlan entry : ordered) {
            Task task = tasksById.get(entry.getTaskId());
            List<Integer> parents = new ArrayList<Integer>();
            for (Task parent : task.getParentList()) {
                parents.add(parent.getCloudletId());
            }
            double earliest = task.getParentList().isEmpty() ? allocation.getStageInFinishSeconds() : 0.0;
            input.add(new DvfsSlackTask(
                    task.getCloudletId(),
                    entry.getVmId(),
                    task.getCloudletLength(),
                    task.getNumberOfPes(),
                    entry.getPlannedDataStageInSeconds(),
                    earliest,
                    parents));
        }
        Map<Integer, Double> capacity = new LinkedHashMap<Integer, Double>();
        for (Object vm : getVmList()) {
            org.workflowsim.CondorVM condorVm = (org.workflowsim.CondorVM) vm;
            capacity.put(condorVm.getId(), condorVm.getMips());
        }
        lastSchedule = new DvfsSlackOptimizer().optimize(input, tiers, capacity, deadline);
        Map<Integer, Double> selectedTiers = new LinkedHashMap<Integer, Double>();
        for (DvfsSlackSchedule.Entry entry : lastSchedule.getTaskPlans()) {
            Task task = tasksById.get(entry.getTaskId());
            task.setVmId(entry.getVmId());
            task.setStaticScheduleStartTime(entry.getStartSeconds());
            selectedTiers.put(entry.getTaskId(), entry.getTierMips());
        }
        DvfsSlackRegistry.configure(selectedTiers);
    }

    public DvfsSlackSchedule getLastSchedule() {
        if (lastSchedule == null) {
            throw new IllegalStateException("DVFS_SLACK has not completed");
        }
        return lastSchedule;
    }
}
