package org.workflowsim.data.v2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class DataflowAssignmentConfig {

    public enum Mode {
        OFF,

        CONTROL_READY_ONLINE_ASSIGNMENT_V1
    }

    public static final String POLICY = "NOMINAL_INPUT_EARLIEST_RESERVATION_V1";

    private static final DataflowAssignmentConfig OFF = new DataflowAssignmentConfig(Mode.OFF);
    private static final DataflowAssignmentConfig ONLINE_NOMINAL =
            new DataflowAssignmentConfig(Mode.CONTROL_READY_ONLINE_ASSIGNMENT_V1);

    private final Mode mode;

    private DataflowAssignmentConfig(Mode mode) {
        this.mode = mode;
    }

    public static DataflowAssignmentConfig off() {
        return OFF;
    }

    public static DataflowAssignmentConfig onlineNominal() {
        return ONLINE_NOMINAL;
    }

    public Mode getMode() {
        return mode;
    }

    public String getPolicy() {
        return isEnabled() ? POLICY : null;
    }

    public boolean isEnabled() {
        return mode == Mode.CONTROL_READY_ONLINE_ASSIGNMENT_V1;
    }

    public static DataflowAssignmentConfig decodeConfig(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(
                    "dataflowAssignment must be an object containing exactly mode and policy");
        }
        JsonObject object = element.getAsJsonObject();
        if (object.size() != 2 || !object.has("mode") || !object.has("policy")) {
            throw new IllegalArgumentException("dataflowAssignment must contain exactly mode and policy");
        }
        String mode = requireString(object, "mode");
        String policy = requireString(object, "policy");
        if (!Mode.CONTROL_READY_ONLINE_ASSIGNMENT_V1.name().equals(mode)) {
            throw new IllegalArgumentException("Unsupported dataflowAssignment mode: " + mode);
        }
        if (!POLICY.equals(policy)) {
            throw new IllegalArgumentException("Unsupported dataflowAssignment policy: " + policy);
        }
        return onlineNominal();
    }

    private static String requireString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("dataflowAssignment " + key + " must be a JSON string");
        }
        return value.getAsString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DataflowAssignmentConfig && mode == ((DataflowAssignmentConfig) other).mode;
    }

    @Override
    public int hashCode() {
        return mode.hashCode();
    }
}
