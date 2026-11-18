package org.workflowsim.planning.timed;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.workflowsim.platform.PlatformProfile;

public final class CarbonExcessEvaluator implements PlanObjectiveEvaluator {

    private static final double EPSILON = 1.0e-9;

    @Override
    public PlanEvaluation evaluate(TimedPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("Timed plan is required");
        }
        Map<Double, Double> events = new TreeMap<Double, Double>();
        for (PlatformProfile.CarbonWindow window : plan.getCarbonWindows()) {
            events.put(window.getStartSeconds(), events.getOrDefault(window.getStartSeconds(), 0.0));
            events.put(window.getEndSeconds(), events.getOrDefault(window.getEndSeconds(), 0.0));
        }
        for (TimedActivity activity : plan.getActivities()) {
            bump(events, activity.getStartSeconds(), activity.getDynamicWatts());
            bump(events, activity.getFinishSeconds(), -activity.getDynamicWatts());
        }
        double total = 0.0;
        double active = 0.0;
        double previous = 0.0;
        for (Map.Entry<Double, Double> event : events.entrySet()) {
            double time = event.getKey().doubleValue();
            total += excess(plan, previous, time, plan.getIdleWattsSum() + active);
            active += event.getValue().doubleValue();
            previous = time;
        }
        total += excess(plan, previous, plan.getDeadlineSeconds(), plan.getIdleWattsSum() + active);
        Map<String, Double> metrics = new LinkedHashMap<String, Double>();
        metrics.put("makespanSeconds", plan.getMakespanSeconds());
        metrics.put("carbonExcessWattSeconds", total);
        return new PlanEvaluation(metrics);
    }

    private static double excess(TimedPlan plan, double start, double end, double watts) {
        if (end <= start + EPSILON) {
            return 0.0;
        }
        double green = greenWattsAt(plan, (start + end) / 2.0);
        return Math.max(0.0, watts - green) * (end - start);
    }

    private static double greenWattsAt(TimedPlan plan, double time) {
        for (PlatformProfile.CarbonWindow window : plan.getCarbonWindows()) {
            if (time + EPSILON >= window.getStartSeconds() && time < window.getEndSeconds() + EPSILON) {
                return window.getGreenWatts();
            }
        }
        throw new IllegalStateException("Timed-plan horizon has no green-power declaration at " + time);
    }

    private static void bump(Map<Double, Double> events, double time, double watts) {
        events.put(time, events.getOrDefault(time, 0.0) + watts);
    }
}
