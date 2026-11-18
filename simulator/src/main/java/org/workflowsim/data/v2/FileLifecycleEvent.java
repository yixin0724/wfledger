package org.workflowsim.data.v2;

import com.google.gson.JsonObject;

public final class FileLifecycleEvent {

    public enum Type {
        EXTERNAL_SEEDED,
        TASK_FINISHED,
        JOB_INPUT_REQUESTED,
        INPUT_RESOLVED,
        COPY_ADMITTED,
        COPY_SETTLED,
        JOB_DATA_READY,
        JOB_CPU_STARTED,
        INPUT_WAITING_FOR_STORE,
        OUTPUT_RESOLVED
    }

    private final long sequence;
    private final double observedTime;
    private final Type type;
    private final JsonObject payload;

    private FileLifecycleEvent(long sequence, double observedTime, Type type, JsonObject payload) {
        this.sequence = sequence;
        this.observedTime = observedTime;
        this.type = type;
        this.payload = payload.deepCopy();
    }

    public static FileLifecycleEvent of(long sequence, double observedTime, Type type, JsonObject payload) {
        if (sequence < 1 || !Double.isFinite(observedTime) || observedTime < 0 || type == null || payload == null) {
            throw new IllegalArgumentException("Invalid file lifecycle event fields");
        }
        return new FileLifecycleEvent(sequence, observedTime == 0 ? 0 : observedTime, type, payload);
    }

    public long getSequence() {
        return sequence;
    }

    public double getObservedTime() {
        return observedTime;
    }

    public Type getType() {
        return type;
    }

    public JsonObject getPayload() {
        return payload.deepCopy();
    }
}
