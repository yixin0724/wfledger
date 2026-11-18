package org.workflowsim.planning.timed;

public interface PostPlanningOptimizer {

    TimedPlan optimize(TimedPlan initialPlan);
}
