package org.workflowsim.planning;

import org.workflowsim.planning.naco.NacoAdapter;
import org.workflowsim.planning.naco.NacoDncProblem;
import org.workflowsim.planning.naco.NacoGaHeftOptimizer;
import org.workflowsim.planning.naco.NacoSchedule;

/** NACO GA/HEFT + DNC planner for the controlled LOCAL static-DAG model. */
public final class NacoGaHeftPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private NacoSchedule lastSchedule;

    public NacoGaHeftPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("NACO_GA_HEFT requires a PlanningContext");
        }
        this.context = context;
    }

    @Override
    public void run() {
        context.validateLocalStaticDag();
        NacoDncProblem problem = NacoAdapter.toProblem(getTaskList(), getVmList(), context.getPlatform());
        lastSchedule = new NacoGaHeftOptimizer(problem, 16, 20).optimize();
        for (org.workflowsim.Task task : getTaskList()) {
            NacoSchedule.Entry entry = lastSchedule.getTaskPlan(task.getCloudletId());
            task.setVmId(entry.getVmId());
            task.setStaticScheduleStartTime(entry.getStartSeconds());
        }
    }

    public NacoSchedule getLastSchedule() {
        if (lastSchedule == null) {
            throw new IllegalStateException("NACO_GA_HEFT has not completed");
        }
        return lastSchedule;
    }
}
