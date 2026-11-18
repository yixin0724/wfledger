package org.workflowsim.planning;

import org.workflowsim.Task;
import org.workflowsim.planning.icpcp.IcpcpAdapter;
import org.workflowsim.planning.icpcp.IcpcpPlanner;
import org.workflowsim.planning.icpcp.IcpcpSchedule;

public final class IcpcpPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private IcpcpSchedule lastSchedule;

    public IcpcpPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("IC_PCP requires a PlanningContext");
        }
        this.context = context;
    }

    @Override
    public void run() {
        context.validateSharedStorageStaticDag();
        double deadline = context.getDeadlineSeconds();
        if (!(deadline > 0.0)) {
            throw new IllegalArgumentException("IC_PCP requires a positive workflow deadline");
        }
        lastSchedule = new IcpcpPlanner(IcpcpAdapter.toProblem(
                        getTaskList(),
                        getVmList(),
                        context.getPlatform(),
                        deadline,
                        context.getCloudSimMinEventIntervalSeconds()))
                .schedule();
        for (Task task : getTaskList()) {
            IcpcpSchedule.Entry entry = lastSchedule.getTaskPlan(task.getCloudletId());
            task.setVmId(entry.getVmId());
            task.setStaticScheduleStartTime(entry.getStartSeconds());
        }
    }

    public IcpcpSchedule getLastSchedule() {
        if (lastSchedule == null) {
            throw new IllegalStateException("IC_PCP has not completed");
        }
        return lastSchedule;
    }
}
