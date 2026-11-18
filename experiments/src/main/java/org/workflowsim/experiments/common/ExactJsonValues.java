package org.workflowsim.experiments.common;

import java.math.BigDecimal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class ExactJsonValues {
    private ExactJsonValues() {}

    public static boolean equal(JsonElement first, JsonElement second) {
        if (first == null || second == null) {
            return first == second;
        }
        if (first.isJsonObject() && second.isJsonObject()) {
            return equalObjectMembers(first, second);
        }
        if (first.isJsonArray() && second.isJsonArray()) {
            return equalArrayElements(first, second);
        }
        if (first.isJsonPrimitive()
                && second.isJsonPrimitive()
                && first.getAsJsonPrimitive().isNumber()
                && second.getAsJsonPrimitive().isNumber()) {
            try {
                return new BigDecimal(first.getAsString()).compareTo(new BigDecimal(second.getAsString())) == 0;
            } catch (NumberFormatException invalidNumber) {
                return false;
            }
        }
        return first.equals(second);
    }

    private static boolean equalObjectMembers(JsonElement first, JsonElement second) {
        JsonObject a = first.getAsJsonObject();
        JsonObject b = second.getAsJsonObject();
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        for (String key : a.keySet()) {
            if (!equal(a.get(key), b.get(key))) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalArrayElements(JsonElement first, JsonElement second) {
        JsonArray a = first.getAsJsonArray();
        JsonArray b = second.getAsJsonArray();
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!equal(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }
}
