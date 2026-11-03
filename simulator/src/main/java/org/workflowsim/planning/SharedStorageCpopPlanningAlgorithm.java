package org.workflowsim.planning;

/**
 * CPOP static DAG planner aligned with the controlled shared-storage execution model of
 * WorkflowSim.
 *
 * <p>Uses {@code r_u + r_d} as the priority, identifies one critical path, and pins that path to
 * the compatible VM with the smallest total model duration. Equal priorities, path branches and
 * processor costs all break ties by the smaller ID; non-critical Tasks use the same reservation
 * insertion assignment as HEFT.</p>
 *
 * <p>Describes only the controlled shared-storage model; it does not represent network topology,
 * shared-link contention, or real processor binding.</p>
 */
public final class SharedStorageCpopPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private SharedStorageDagPlanTrace lastPlanTrace;

    public SharedStorageCpopPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("SHARED_STORAGE_CPOP requires a PlanningContext from SimulationRunner");
        }
        this.context = context;
    }

    @Override
    public void run() {
        lastPlanTrace = null;
        lastPlanTrace = SharedStorageDagPlanner.plan(
                getTaskList(), getVmList(), context, SharedStorageDagPlanner.Strategy.CPOP);
    }

    /** Returns the immutable decision trace of the most recent successful plan. */
    public SharedStorageDagPlanTrace getLastPlanTrace() {
        if (lastPlanTrace == null) {
            throw new IllegalStateException("SHARED_STORAGE_CPOP has not completed a plan");
        }
        return lastPlanTrace;
    }
}
