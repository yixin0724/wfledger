package org.workflowsim.planning.cawosched;

import java.util.ArrayList;
import java.util.List;

import org.workflowsim.planning.timed.PostPlanningOptimizer;
import org.workflowsim.planning.timed.TimedActivity;
import org.workflowsim.planning.timed.TimedPlan;

public final class CaWoSchedOptimizer implements PostPlanningOptimizer {

    private final CaWoSchedVariant variant;

    public CaWoSchedOptimizer(CaWoSchedVariant variant) {
        if (variant == null) {
            throw new IllegalArgumentException("CaWoSched variant is required");
        }
        this.variant = variant;
    }

    @Override
    public TimedPlan optimize(TimedPlan initialPlan) {
        if (initialPlan == null) {
            throw new IllegalArgumentException("Initial timed plan is required");
        }
        List<CaWoSchedTask> tasks = new ArrayList<CaWoSchedTask>();
        for (TimedActivity activity : initialPlan.getActivities()) {
            tasks.add(new CaWoSchedTask(
                    activity.getActivityId(),
                    activity.getResourceId(),
                    activity.getOrderOnResource(),
                    activity.getDurationSeconds(),
                    activity.getIdleWatts(),
                    activity.getActiveWatts(),
                    activity.getPredecessorActivityIds()));
        }
        CaWoSchedPlanner planner = new CaWoSchedPlanner(
                tasks,
                initialPlan.getCarbonWindows(),
                initialPlan.getDeadlineSeconds(),
                initialPlan.getDeclaredIdleWattsByResource());
        CaWoSchedPlan optimized =
                planner.plan(variant.getScore(), variant.isWeighted(), variant.isRefined(), variant.isLocalSearch());
        List<TimedActivity> activities = new ArrayList<TimedActivity>();
        for (TimedActivity activity : initialPlan.getActivities()) {
            activities.add(activity.withStartSeconds(
                    optimized.getTaskPlan(activity.getActivityId()).getStartSeconds()));
        }
        return new TimedPlan(
                activities,
                initialPlan.getCarbonWindows(),
                initialPlan.getDeadlineSeconds(),
                initialPlan.getDeclaredIdleWattsByResource(),
                optimized.getDecisionOrderTaskIds());
    }
}
