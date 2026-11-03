package org.workflowsim.planning;

/**
 * DLS static DAG planner aligned with the WorkflowSim controlled shared-storage execution model.
 *
 * <p>Each round selects, among the Task-VM pairs whose dependencies are already satisfied, the
 * one with the largest dynamic level:
 * {@code dynamicLevel = upwardRank - earliestInsertionStart}. Ties within the same dynamic
 * level are broken by smaller Task ID, then by smaller VM ID; the decision trace records the
 * dynamic level and the selection order of each round.</p>
 *
 * <p>It introduces no interconnect topology or link reservation, so it is only a DLS adaptation
 * under this controlled model, not a general network-aware DLS.</p>
 */
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

    /** Returns the immutable decision trace of the most recent successful plan. */
    public SharedStorageDagPlanTrace getLastPlanTrace() {
        if (lastPlanTrace == null) {
            throw new IllegalStateException("SHARED_STORAGE_DLS has not completed a plan");
        }
        return lastPlanTrace;
    }
}
