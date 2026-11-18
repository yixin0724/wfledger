package org.workflowsim.planning;

import org.workflowsim.Task;
import org.workflowsim.planning.networkheft.NetworkHeftAdapter;
import org.workflowsim.planning.networkheft.NetworkHeftPlanner;
import org.workflowsim.planning.networkheft.NetworkHeftSchedule;

public final class NetworkHeftPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private NetworkHeftSchedule lastSchedule;

    public NetworkHeftPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("NETWORK_HEFT requires a PlanningContext");
        }
        this.context = context;
    }

    @Override
    public void run() {
        context.validateSharedStorageStaticDag();
        lastSchedule = new NetworkHeftPlanner()
                .schedule(NetworkHeftAdapter.toProblem(getTaskList(), getVmList(), context.getPlatform()));
        for (Task task : getTaskList()) {
            NetworkHeftSchedule.Entry entry = lastSchedule.getTaskPlan(task.getCloudletId());
            task.setVmId(entry.getVmId());
            task.setStaticScheduleStartTime(entry.getStartSeconds());
        }
    }

    public NetworkHeftSchedule getLastSchedule() {
        if (lastSchedule == null) {
            throw new IllegalStateException("NETWORK_HEFT has not completed");
        }
        return lastSchedule;
    }
}
