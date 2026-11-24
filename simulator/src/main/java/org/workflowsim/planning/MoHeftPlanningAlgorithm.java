package org.workflowsim.planning;

import org.workflowsim.Task;
import org.workflowsim.planning.greenheft.GreenHeftAdapter;
import org.workflowsim.planning.greenheft.GreenHeftProblem;
import org.workflowsim.planning.moheft.MoHeftPlanner;
import org.workflowsim.planning.moheft.MoHeftResult;
import org.workflowsim.planning.moheft.MoHeftSchedule;

public final class MoHeftPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private MoHeftResult lastResult;

    public MoHeftPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("MO_HEFT requires a PlanningContext");
        }
        this.context = context;
    }

    @Override
    public void run() {
        context.validateSharedStorageStaticDag();
        GreenHeftProblem problem = GreenHeftAdapter.toProblem(
                getTaskList(),
                getVmList(),
                context.getPlatform(),
                context.getSharedStorageTransferRateMbPerSecond(),
                context.getCloudSimMinEventIntervalSeconds());
        lastResult = new MoHeftPlanner(8).schedule(problem);
        MoHeftSchedule selected = lastResult.getBalancedSchedule();
        for (Task task : getTaskList()) {
            MoHeftSchedule.Entry entry = selected.getTaskPlan(task.getCloudletId());
            task.setVmId(entry.getVmId());
            task.setStaticScheduleStartTime(entry.getStartSeconds());
        }
    }

    public MoHeftResult getLastResult() {
        if (lastResult == null) {
            throw new IllegalStateException("MO_HEFT has not completed");
        }
        return lastResult;
    }
}
