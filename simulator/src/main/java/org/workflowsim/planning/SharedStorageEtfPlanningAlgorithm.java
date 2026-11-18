package org.workflowsim.planning;

public final class SharedStorageEtfPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private SharedStorageDagPlanTrace lastPlanTrace;

    public SharedStorageEtfPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("SHARED_STORAGE_ETF requires a PlanningContext from SimulationRunner");
        }
        this.context = context;
    }

    @Override
    public void run() {
        lastPlanTrace = null;
        lastPlanTrace =
                SharedStorageDagPlanner.plan(getTaskList(), getVmList(), context, SharedStorageDagPlanner.Strategy.ETF);
    }

    public SharedStorageDagPlanTrace getLastPlanTrace() {
        if (lastPlanTrace == null) {
            throw new IllegalStateException("SHARED_STORAGE_ETF has not completed a plan");
        }
        return lastPlanTrace;
    }
}
