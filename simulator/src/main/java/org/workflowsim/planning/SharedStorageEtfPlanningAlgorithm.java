package org.workflowsim.planning;

/**
 * Earliest Task First (ETF) static DAG planner aligned with the controlled shared-storage
 * execution model of WorkflowSim.
 *
 * <p>Each round selects, among the Task-VM pairs whose dependencies are satisfied, the pair with
 * the smallest reservation earliest start time; equal start times first select the Task with the
 * higher static b-level, then break ties by the smaller Task ID and VM ID. The decision trace
 * records that start time and the selection order.</p>
 *
 * <p>This implementation has no network topology, link-contention, or communication-routing
 * modeling, so it cannot support experimental conclusions about network-aware ETF.</p>
 */
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

    /** Returns the immutable decision trace of the most recent successful plan. */
    public SharedStorageDagPlanTrace getLastPlanTrace() {
        if (lastPlanTrace == null) {
            throw new IllegalStateException("SHARED_STORAGE_ETF has not completed a plan");
        }
        return lastPlanTrace;
    }
}
