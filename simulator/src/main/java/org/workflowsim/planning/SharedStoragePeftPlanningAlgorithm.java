package org.workflowsim.planning;

/**
 * Predict Earliest Finish Time (PEFT) planner aligned with the WorkflowSim controlled
 * shared-storage execution model.
 *
 * <p>It first prioritizes the ready Task with the largest optimistic cost table (OCT) averaged
 * over compatible VMs, then selects the VM that minimizes the inserted {@code EFT + OCT}.
 * Equal Task priority and equal VM target both resolve to the smaller ID. Because this model has
 * no link model, the cross-processor communication term of the original PEFT is zero here.</p>
 *
 * <p>The implementation keeps the optimistic lookahead over successor tasks; it does not
 * represent network-aware PEFT or PEFT calibrated against a real platform.</p>
 */
public final class SharedStoragePeftPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private SharedStorageDagPlanTrace lastPlanTrace;

    public SharedStoragePeftPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("SHARED_STORAGE_PEFT requires a PlanningContext from SimulationRunner");
        }
        this.context = context;
    }

    @Override
    public void run() {
        lastPlanTrace = null;
        lastPlanTrace = SharedStorageDagPlanner.plan(
                getTaskList(), getVmList(), context, SharedStorageDagPlanner.Strategy.PEFT);
    }

    /** Returns the immutable decision trace of the most recent successful plan. */
    public SharedStorageDagPlanTrace getLastPlanTrace() {
        if (lastPlanTrace == null) {
            throw new IllegalStateException("SHARED_STORAGE_PEFT has not completed a plan");
        }
        return lastPlanTrace;
    }
}
