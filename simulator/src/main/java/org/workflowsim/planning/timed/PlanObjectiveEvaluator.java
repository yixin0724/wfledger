package org.workflowsim.planning.timed;

/** Independently evaluates one timed plan without changing the planning algorithm. */
public interface PlanObjectiveEvaluator {

    PlanEvaluation evaluate(TimedPlan plan);
}
