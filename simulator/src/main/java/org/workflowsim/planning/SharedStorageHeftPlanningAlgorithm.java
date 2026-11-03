package org.workflowsim.planning;

/**
 * HEFT static DAG planner aligned with the controlled shared-storage execution model of
 * WorkflowSim.
 *
 * <p>Selects Tasks in descending order of the upward rank {@code r_u}, then chooses the
 * compatible VM by the reservation insertion earliest finish time (EFT). Equal ranks choose the
 * smaller Task ID, and equal EFTs the smaller VM ID. The rank uses the average model duration
 * over the compatible VMs, which includes the shared-storage stage-in of this model.</p>
 *
 * <p>Applies only to shared storage, no clustering, no failures, no modeled overhead and
 * {@code SPACE_SHARED} VMs; it is not a topology-aware, link-contention-aware, or
 * real-platform-calibrated HEFT.</p>
 */
public final class SharedStorageHeftPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private SharedStorageDagPlanTrace lastPlanTrace;

    public SharedStorageHeftPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("SHARED_STORAGE_HEFT requires a PlanningContext from SimulationRunner");
        }
        this.context = context;
    }

    @Override
    public void run() {
        lastPlanTrace = null;
        lastPlanTrace = SharedStorageDagPlanner.plan(
                getTaskList(), getVmList(), context, SharedStorageDagPlanner.Strategy.HEFT);
    }

    /** Returns the immutable decision trace of the most recent successful plan. */
    public SharedStorageDagPlanTrace getLastPlanTrace() {
        if (lastPlanTrace == null) {
            throw new IllegalStateException("SHARED_STORAGE_HEFT has not completed a plan");
        }
        return lastPlanTrace;
    }
}
