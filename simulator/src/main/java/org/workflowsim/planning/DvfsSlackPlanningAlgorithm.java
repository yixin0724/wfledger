package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.Task;
import org.workflowsim.planning.dvfs.DvfsSlackOptimizer;
import org.workflowsim.planning.dvfs.DvfsSlackSchedule;
import org.workflowsim.planning.dvfs.DvfsSlackTask;
import org.workflowsim.planning.networkheft.NetworkHeftAdapter;
import org.workflowsim.planning.networkheft.NetworkHeftPlanner;
import org.workflowsim.planning.networkheft.NetworkHeftSchedule;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DvfsSlackRegistry;

/** HEFT allocation followed by iterative DVFS slack allocation and tier registration. */
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
        NetworkHeftSchedule allocation = new NetworkHeftPlanner()
                .schedule(NetworkHeftAdapter.toProblem(getTaskList(), getVmList(), context.getPlatform()), 1);
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
        List<DvfsSlackTask> input = new ArrayList<DvfsSlackTask>();
        for (NetworkHeftSchedule.Entry entry : allocation.getTaskPlans()) {
            Task task = tasksById.get(entry.getTaskId());
            List<Integer> parents = new ArrayList<Integer>();
            for (Task parent : task.getParentList()) {
                parents.add(parent.getCloudletId());
            }
            input.add(new DvfsSlackTask(task.getCloudletId(), entry.getVmId(), task.getCloudletLength(), parents));
        }
        lastSchedule = new DvfsSlackOptimizer().optimize(input, tiers, deadline);
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
