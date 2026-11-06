package org.workflowsim.planning;

import org.workflowsim.Task;
import org.workflowsim.planning.greenheft.GreenHeftAdapter;
import org.workflowsim.planning.greenheft.GreenHeftPlanner;
import org.workflowsim.planning.greenheft.GreenHeftProblem;
import org.workflowsim.planning.greenheft.GreenHeftSchedule;

/** GreenHEFT static planner using declared VM power profiles. */
public final class GreenHeftPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private GreenHeftSchedule lastSchedule;

    public GreenHeftPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("GREEN_HEFT requires a PlanningContext");
        }
        this.context = context;
    }

    @Override
    public void run() {
        context.validateSharedStorageStaticDag();
        GreenHeftProblem problem = GreenHeftAdapter.toProblem(getTaskList(), getVmList(), context.getPlatform());
        lastSchedule = new GreenHeftPlanner().schedule(problem);
        for (Task task : getTaskList()) {
            GreenHeftSchedule.Entry entry = lastSchedule.getTaskPlan(task.getCloudletId());
            task.setVmId(entry.getVmId());
            task.setStaticScheduleStartTime(entry.getStartSeconds());
        }
    }

    public GreenHeftSchedule getLastSchedule() {
        if (lastSchedule == null) {
            throw new IllegalStateException("GREEN_HEFT has not completed");
        }
        return lastSchedule;
    }
}
