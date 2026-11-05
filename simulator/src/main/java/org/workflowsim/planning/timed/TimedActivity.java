package org.workflowsim.planning.timed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable activity in a generic timed plan. */
public final class TimedActivity {

    private final int activityId;
    private final int logicalTaskId;
    private final TimedActivityType type;
    private final int resourceId;
    private final int orderOnResource;
    private final double durationSeconds;
    private final double startSeconds;
    private final double idleWatts;
    private final double activeWatts;
    private final List<Integer> predecessorActivityIds;

    public TimedActivity(
            int activityId,
            int logicalTaskId,
            TimedActivityType type,
            int resourceId,
            int orderOnResource,
            double durationSeconds,
            double startSeconds,
            double idleWatts,
            double activeWatts,
            List<Integer> predecessorActivityIds) {
        if (activityId < 0 || logicalTaskId < 0 || resourceId < 0 || orderOnResource < 0) {
            throw new IllegalArgumentException("Timed activity identifiers must be non-negative");
        }
        if (type == null) {
            throw new IllegalArgumentException("Timed activity type is required");
        }
        if (!(durationSeconds > 0.0) || !Double.isFinite(durationSeconds)) {
            throw new IllegalArgumentException("Timed activity duration must be finite and positive");
        }
        if (!(startSeconds >= 0.0) || !Double.isFinite(startSeconds)) {
            throw new IllegalArgumentException("Timed activity start must be finite and non-negative");
        }
        if (!(idleWatts >= 0.0)
                || !Double.isFinite(idleWatts)
                || !(activeWatts >= idleWatts)
                || !Double.isFinite(activeWatts)) {
            throw new IllegalArgumentException("Timed activity power must satisfy 0 <= idle <= active");
        }
        if (predecessorActivityIds == null) {
            throw new IllegalArgumentException("Timed activity predecessors cannot be null");
        }
        this.activityId = activityId;
        this.logicalTaskId = logicalTaskId;
        this.type = type;
        this.resourceId = resourceId;
        this.orderOnResource = orderOnResource;
        this.durationSeconds = durationSeconds;
        this.startSeconds = startSeconds;
        this.idleWatts = idleWatts;
        this.activeWatts = activeWatts;
        this.predecessorActivityIds = Collections.unmodifiableList(new ArrayList<Integer>(predecessorActivityIds));
    }

    public TimedActivity withStartSeconds(double value) {
        return new TimedActivity(
                activityId,
                logicalTaskId,
                type,
                resourceId,
                orderOnResource,
                durationSeconds,
                value,
                idleWatts,
                activeWatts,
                predecessorActivityIds);
    }

    public int getActivityId() {
        return activityId;
    }

    public int getLogicalTaskId() {
        return logicalTaskId;
    }

    public TimedActivityType getType() {
        return type;
    }

    public int getResourceId() {
        return resourceId;
    }

    public int getOrderOnResource() {
        return orderOnResource;
    }

    public double getDurationSeconds() {
        return durationSeconds;
    }

    public double getStartSeconds() {
        return startSeconds;
    }

    public double getFinishSeconds() {
        return startSeconds + durationSeconds;
    }

    public double getIdleWatts() {
        return idleWatts;
    }

    public double getActiveWatts() {
        return activeWatts;
    }

    public double getDynamicWatts() {
        return activeWatts - idleWatts;
    }

    public List<Integer> getPredecessorActivityIds() {
        return predecessorActivityIds;
    }
}
