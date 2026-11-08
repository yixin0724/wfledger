package org.workflowsim.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Session-scoped DVFS tier map produced by the planning layer. */
public final class DvfsSlackRegistry {
    private static Map<Integer, Double> tierByTask = Collections.emptyMap();

    private DvfsSlackRegistry() {}

    public static synchronized void configure(Map<Integer, Double> values) {
        if (values == null) {
            throw new IllegalArgumentException("DVFS tier map cannot be null");
        }
        tierByTask = Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(values));
    }

    public static synchronized double getTierMips(int taskId) {
        Double value = tierByTask.get(taskId);
        if (value == null) {
            throw new IllegalStateException("No DVFS tier assigned for task " + taskId);
        }
        return value;
    }

    public static synchronized void clear() {
        tierByTask = Collections.emptyMap();
    }
}
