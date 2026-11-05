package org.workflowsim.planning.timed;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable named metrics produced by a PlanObjectiveEvaluator. */
public final class PlanEvaluation {

    private final Map<String, Double> metrics;

    public PlanEvaluation(Map<String, Double> metrics) {
        if (metrics == null) {
            throw new IllegalArgumentException("Plan evaluation metrics cannot be null");
        }
        Map<String, Double> copy = new LinkedHashMap<String, Double>(metrics);
        for (Map.Entry<String, Double> entry : copy.entrySet()) {
            if (entry.getValue() == null || !Double.isFinite(entry.getValue())) {
                throw new IllegalArgumentException("Plan evaluation metrics must be finite");
            }
        }
        this.metrics = Collections.unmodifiableMap(copy);
    }

    public double getMetric(String name) {
        Double value = metrics.get(name);
        if (value == null) {
            throw new IllegalArgumentException("No plan metric " + name);
        }
        return value.doubleValue();
    }

    public double getMakespanSeconds() {
        return getMetric("makespanSeconds");
    }

    public double getCarbonExcessWattSeconds() {
        return getMetric("carbonExcessWattSeconds");
    }

    public Map<String, Double> getMetrics() {
        return metrics;
    }
}
