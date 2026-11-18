package org.workflowsim.planning;

import org.workflowsim.Task;
import org.workflowsim.planning.dheft.DheftAdapter;
import org.workflowsim.planning.dheft.DheftPlanner;
import org.workflowsim.planning.dheft.DheftSchedule;

public final class DheftPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private DheftSchedule lastSchedule;

    public DheftPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("DHEFT requires a PlanningContext");
        }
        this.context = context;
    }

    @Override
    public void run() {
        context.validateLocalStaticDag();
        lastSchedule =
                new DheftPlanner().schedule(DheftAdapter.toProblem(getTaskList(), getVmList(), context.getPlatform()));
        for (Task task : getTaskList()) {
            DheftSchedule.Entry entry = lastSchedule.getTaskPlan(task.getCloudletId());
            task.setVmId(entry.getVmId());
            task.setStaticScheduleStartTime(entry.getStartSeconds());
        }
    }

    public DheftSchedule getLastSchedule() {
        if (lastSchedule == null) {
            throw new IllegalStateException("DHEFT has not completed");
        }
        return lastSchedule;
    }
}
