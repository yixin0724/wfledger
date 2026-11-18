package org.workflowsim.planning;

public final class SharedStorageDlsPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private SharedStorageDagPlanTrace lastPlanTrace;

    public SharedStorageDlsPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("SHARED_STORAGE_DLS requires a PlanningContext from SimulationRunner");
        }
        this.context = context;
    }

    @Override
    public void run() {
        lastPlanTrace = null;
        lastPlanTrace =
                SharedStorageDagPlanner.plan(getTaskList(), getVmList(), context, SharedStorageDagPlanner.Strategy.DLS);
    }

    public SharedStorageDagPlanTrace getLastPlanTrace() {
        if (lastPlanTrace == null) {
            throw new IllegalStateException("SHARED_STORAGE_DLS has not completed a plan");
        }
        return lastPlanTrace;
    }
}
