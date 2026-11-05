package org.workflowsim.planning.timed;

import java.util.Collections;

/** Returns the maximum activity finish time of a timed plan. */
public final class MakespanEvaluator implements PlanObjectiveEvaluator {

    @Override
    public PlanEvaluation evaluate(TimedPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("Timed plan is required");
        }
        return new PlanEvaluation(Collections.singletonMap("makespanSeconds", plan.getMakespanSeconds()));
    }
}
