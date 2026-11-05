package org.workflowsim.planning.timed;

/** Optimizes an existing fixed plan while leaving the execution engine unchanged. */
public interface PostPlanningOptimizer {

    TimedPlan optimize(TimedPlan initialPlan);
}
