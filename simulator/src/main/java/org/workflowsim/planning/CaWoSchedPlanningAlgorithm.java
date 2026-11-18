package org.workflowsim.planning;

import java.util.List;

import org.workflowsim.Task;
import org.workflowsim.planning.cawosched.CaWoSchedAdapter;
import org.workflowsim.planning.cawosched.CaWoSchedVariant;
import org.workflowsim.planning.timed.TimedActivity;
import org.workflowsim.planning.timed.TimedPlan;
import org.workflowsim.platform.PlatformProfile;

public final class CaWoSchedPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private final CaWoSchedVariant variant;
    private TimedPlan lastTimedPlan;

    public CaWoSchedPlanningAlgorithm(PlanningContext context, CaWoSchedVariant variant) {
        if (context == null || variant == null) {
            throw new IllegalArgumentException("CaWoSched planning requires context and variant");
        }
        this.context = context;
        this.variant = variant;
    }

    @Override
    public void run() {
        context.validateSharedStorageStaticDag();
        SharedStorageDagPlanTrace heft = SharedStorageDagPlanner.plan(
                getTaskList(), getVmList(), context, SharedStorageDagPlanner.Strategy.HEFT);
        List<PlatformProfile.CarbonWindow> windows = context.getPlatform().getCarbonWindows();
        if (windows.isEmpty()) {
            throw new IllegalArgumentException(
                    "CaWoSched requires declared carbonWindows and a complete deadline horizon");
        }
        double deadline = Math.min(
                context.getDeadlineSeconds(), windows.get(windows.size() - 1).getEndSeconds());
        TimedPlan initial = CaWoSchedAdapter.toTimedPlan(
                getTaskList(), getVmList(), context.getPlatform(), heft, windows, deadline);
        lastTimedPlan = variant.optimizer().optimize(initial);
        for (Task task : getTaskList()) {
            TimedActivity activity = lastTimedPlan.getActivity(task.getCloudletId());
            task.setVmId(activity.getResourceId());
            task.setStaticScheduleStartTime(activity.getStartSeconds());
        }
    }

    public TimedPlan getLastTimedPlan() {
        if (lastTimedPlan == null) {
            throw new IllegalStateException("CaWoSched planning has not completed");
        }
        return lastTimedPlan;
    }
}
