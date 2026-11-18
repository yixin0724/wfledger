package org.workflowsim.data.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonObject;

public final class FileLifecycleEvidence {

    public enum Status {
        COMPLETE,
        TRUNCATED
    }

    private final int maxRecords;
    private final long droppedRecords;
    private final double observedThrough;
    private final boolean shared;
    private final JsonObject filePlan, fabric;
    private final List<FileLifecycleEvent> events;

    private FileLifecycleEvidence(
            int maxRecords,
            long dropped,
            double time,
            boolean shared,
            JsonObject plan,
            JsonObject fabric,
            List<FileLifecycleEvent> events) {
        this.maxRecords = maxRecords;
        droppedRecords = dropped;
        observedThrough = time;
        this.shared = shared;
        filePlan = plan.deepCopy();
        this.fabric = fabric.deepCopy();
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
    }

    public static FileLifecycleEvidence capture(
            int budget,
            long dropped,
            double observedThrough,
            boolean shared,
            JsonObject plan,
            JsonObject fabric,
            List<FileLifecycleEvent> events) {
        if (budget < 1
                || dropped < 0
                || !Double.isFinite(observedThrough)
                || observedThrough < 0
                || plan == null
                || fabric == null
                || events == null
                || events.size() > budget
                || (dropped > 0 && events.size() != budget)) {
            throw new IllegalArgumentException("Invalid file lifecycle capture shape");
        }
        validatePrefixOrder(events, observedThrough);
        return new FileLifecycleEvidence(budget, dropped, observedThrough, shared, plan, fabric, events);
    }

    private static void validatePrefixOrder(List<FileLifecycleEvent> events, double observedThrough) {
        long sequence = 1;
        double previous = 0;
        for (FileLifecycleEvent event : events) {
            if (event == null
                    || event.getSequence() != sequence++
                    || event.getObservedTime() < previous
                    || event.getObservedTime() > observedThrough) {
                throw new IllegalArgumentException("Invalid file lifecycle prefix order");
            }
            previous = event.getObservedTime();
        }
    }

    public Status getStatus() {
        return droppedRecords == 0 ? Status.COMPLETE : Status.TRUNCATED;
    }

    public int getMaxRecords() {
        return maxRecords;
    }

    public long getDroppedRecords() {
        return droppedRecords;
    }

    public double getObservedThrough() {
        return observedThrough;
    }

    public boolean isShared() {
        return shared;
    }

    public JsonObject getFilePlan() {
        return filePlan.deepCopy();
    }

    public JsonObject getFabric() {
        return fabric.deepCopy();
    }

    public List<FileLifecycleEvent> getEvents() {
        return events;
    }
}
