package org.workflowsim.planning.timed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.workflowsim.platform.PlatformProfile;

/** Immutable timed activities, deadline, green windows, and optimizer decision order. */
public final class TimedPlan {

    private static final double EPSILON = 1.0e-9;

    private final Map<Integer, TimedActivity> activities;
    private final List<PlatformProfile.CarbonWindow> carbonWindows;
    private final double deadlineSeconds;
    private final double makespanSeconds;
    private final List<Integer> decisionOrderActivityIds;
    private final Map<Integer, Double> declaredIdleWattsByResource;
    private final double idleWattsSum;

    public TimedPlan(
            List<TimedActivity> sourceActivities,
            List<PlatformProfile.CarbonWindow> carbonWindows,
            double deadlineSeconds,
            Map<Integer, Double> declaredIdleWattsByResource) {
        this(
                sourceActivities,
                carbonWindows,
                deadlineSeconds,
                declaredIdleWattsByResource,
                Collections.<Integer>emptyList());
    }

    public TimedPlan(
            List<TimedActivity> sourceActivities,
            List<PlatformProfile.CarbonWindow> carbonWindows,
            double deadlineSeconds,
            Map<Integer, Double> declaredIdleWattsByResource,
            List<Integer> decisionOrderActivityIds) {
        if (sourceActivities == null || sourceActivities.isEmpty()) {
            throw new IllegalArgumentException("Timed plan requires at least one activity");
        }
        if (!(deadlineSeconds > 0.0) || !Double.isFinite(deadlineSeconds)) {
            throw new IllegalArgumentException("Timed plan deadline must be finite and positive");
        }
        this.deadlineSeconds = deadlineSeconds;
        this.carbonWindows = Collections.unmodifiableList(validatedWindows(carbonWindows, deadlineSeconds));
        this.decisionOrderActivityIds = Collections.unmodifiableList(new ArrayList<Integer>(decisionOrderActivityIds));
        Map<Integer, TimedActivity> indexed = new LinkedHashMap<Integer, TimedActivity>();
        Map<Integer, Set<Integer>> orderKeys = new LinkedHashMap<Integer, Set<Integer>>();
        Map<Integer, Double> idleByResource = new LinkedHashMap<Integer, Double>();
        double makespan = 0.0;
        List<TimedActivity> sorted = new ArrayList<TimedActivity>(sourceActivities);
        Collections.sort(sorted, Comparator.comparingInt(TimedActivity::getActivityId));
        for (TimedActivity activity : sorted) {
            if (indexed.put(activity.getActivityId(), activity) != null) {
                throw new IllegalArgumentException("Duplicate timed activity ID " + activity.getActivityId());
            }
            if (!orderKeys
                    .computeIfAbsent(activity.getResourceId(), key -> new LinkedHashSet<Integer>())
                    .add(activity.getOrderOnResource())) {
                throw new IllegalArgumentException("Duplicate order on resource " + activity.getResourceId());
            }
            for (Integer predecessor : activity.getPredecessorActivityIds()) {
                if (predecessor.intValue() == activity.getActivityId()) {
                    throw new IllegalArgumentException("Timed activity cannot depend on itself");
                }
            }
            Double previousIdle = idleByResource.put(activity.getResourceId(), activity.getIdleWatts());
            if (previousIdle != null && Double.compare(previousIdle, activity.getIdleWatts()) != 0) {
                throw new IllegalArgumentException("Inconsistent idle power for resource " + activity.getResourceId());
            }
            makespan = Math.max(makespan, activity.getFinishSeconds());
        }
        for (TimedActivity activity : sorted) {
            for (Integer predecessor : activity.getPredecessorActivityIds()) {
                if (!indexed.containsKey(predecessor)) {
                    throw new IllegalArgumentException("Unknown predecessor activity " + predecessor);
                }
            }
        }
        this.activities = Collections.unmodifiableMap(indexed);
        this.makespanSeconds = makespan;
        if (declaredIdleWattsByResource != null && !declaredIdleWattsByResource.isEmpty()) {
            idleByResource = new LinkedHashMap<Integer, Double>(declaredIdleWattsByResource);
        }
        this.declaredIdleWattsByResource =
                Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(idleByResource));
        double idleSum = 0.0;
        for (double idle : idleByResource.values()) {
            if (!(idle >= 0.0) || !Double.isFinite(idle)) {
                throw new IllegalArgumentException("Invalid declared idle power");
            }
            idleSum += idle;
        }
        this.idleWattsSum = idleSum;
    }

    public TimedActivity getActivity(int activityId) {
        TimedActivity activity = activities.get(activityId);
        if (activity == null) {
            throw new IllegalArgumentException("No timed activity " + activityId);
        }
        return activity;
    }

    public List<TimedActivity> getActivities() {
        return Collections.unmodifiableList(new ArrayList<TimedActivity>(activities.values()));
    }

    public List<PlatformProfile.CarbonWindow> getCarbonWindows() {
        return carbonWindows;
    }

    public double getDeadlineSeconds() {
        return deadlineSeconds;
    }

    public double getMakespanSeconds() {
        return makespanSeconds;
    }

    public List<Integer> getDecisionOrderActivityIds() {
        return decisionOrderActivityIds;
    }

    public double getIdleWattsSum() {
        return idleWattsSum;
    }

    public Map<Integer, Double> getDeclaredIdleWattsByResource() {
        return declaredIdleWattsByResource;
    }

    private static List<PlatformProfile.CarbonWindow> validatedWindows(
            List<PlatformProfile.CarbonWindow> source, double deadline) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("Timed plan requires complete green-power windows");
        }
        List<PlatformProfile.CarbonWindow> result = new ArrayList<PlatformProfile.CarbonWindow>(source);
        if (Math.abs(result.get(0).getStartSeconds()) > EPSILON) {
            throw new IllegalArgumentException("Timed-plan windows must start at zero");
        }
        for (int index = 1; index < result.size(); index++) {
            if (Math.abs(result.get(index - 1).getEndSeconds()
                            - result.get(index).getStartSeconds())
                    > EPSILON) {
                throw new IllegalArgumentException("Timed-plan windows must be contiguous");
            }
        }
        if (Math.abs(result.get(result.size() - 1).getEndSeconds() - deadline) > EPSILON) {
            throw new IllegalArgumentException("Timed-plan windows must cover exactly [0, deadline)");
        }
        return result;
    }
}
