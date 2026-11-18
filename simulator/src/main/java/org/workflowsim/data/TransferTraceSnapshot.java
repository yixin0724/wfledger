package org.workflowsim.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TransferTraceSnapshot {

    public enum Status {
        DISABLED,

        COMPLETE,

        TRUNCATED
    }

    private final Status status;
    private final List<TransferTraceEvent> events;
    private final long droppedCount;
    private final double engineTime;

    TransferTraceSnapshot(Status status, List<TransferTraceEvent> events, long droppedCount, double engineTime) {
        this.status = status;
        this.events = events.isEmpty()
                ? Collections.<TransferTraceEvent>emptyList()
                : Collections.unmodifiableList(new ArrayList<TransferTraceEvent>(events));
        this.droppedCount = droppedCount;
        this.engineTime = engineTime;
    }

    public Status getStatus() {
        return status;
    }

    public List<TransferTraceEvent> getEvents() {
        return events;
    }

    public long getDroppedCount() {
        return droppedCount;
    }

    public double getEngineTime() {
        return engineTime;
    }
}
