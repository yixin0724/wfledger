package org.workflowsim.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public final class OverheadModelConfig {

    private static final OverheadModelConfig NONE = new OverheadModelConfig(
            0,
            0.0,
            Collections.<Integer, DistributionSpec>emptyMap(),
            Collections.<Integer, DistributionSpec>emptyMap(),
            Collections.<Integer, DistributionSpec>emptyMap(),
            Collections.<Integer, DistributionSpec>emptyMap());

    private final int workflowEngineDelayInterval;
    private final double bandwidth;
    private final Map<Integer, DistributionSpec> workflowEngineDelays;
    private final Map<Integer, DistributionSpec> queueDelays;
    private final Map<Integer, DistributionSpec> postDelays;
    private final Map<Integer, DistributionSpec> clusteringDelays;

    private OverheadModelConfig(
            int workflowEngineDelayInterval,
            double bandwidth,
            Map<Integer, DistributionSpec> workflowEngineDelays,
            Map<Integer, DistributionSpec> queueDelays,
            Map<Integer, DistributionSpec> postDelays,
            Map<Integer, DistributionSpec> clusteringDelays) {
        this.workflowEngineDelayInterval = workflowEngineDelayInterval;
        this.bandwidth = bandwidth;
        this.workflowEngineDelays = frozenCopy(workflowEngineDelays);
        this.queueDelays = frozenCopy(queueDelays);
        this.postDelays = frozenCopy(postDelays);
        this.clusteringDelays = frozenCopy(clusteringDelays);
    }

    public static OverheadModelConfig none() {
        return NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static OverheadModelConfig fromLegacy(OverheadParameters parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("Overhead parameters cannot be null");
        }
        return builder()
                .workflowEngineDelayInterval(parameters.getWEDInterval())
                .bandwidth(parameters.getBandwidth())
                .workflowEngineDelays(copyLegacy(parameters.getWEDDelay()))
                .queueDelays(copyLegacy(parameters.getQueueDelay()))
                .postDelays(copyLegacy(parameters.getPostDelay()))
                .clusteringDelays(copyLegacy(parameters.getClustDelay()))
                .build();
    }

    public int getWorkflowEngineDelayInterval() {
        return workflowEngineDelayInterval;
    }

    public double getBandwidth() {
        return bandwidth;
    }

    public Map<Integer, DistributionSpec> getWorkflowEngineDelays() {
        return workflowEngineDelays;
    }

    public Map<Integer, DistributionSpec> getQueueDelays() {
        return queueDelays;
    }

    public Map<Integer, DistributionSpec> getPostDelays() {
        return postDelays;
    }

    public Map<Integer, DistributionSpec> getClusteringDelays() {
        return clusteringDelays;
    }

    public OverheadParameters createParameters() {
        return new OverheadParameters(
                workflowEngineDelayInterval,
                createGenerators(workflowEngineDelays, "overhead.workflow-engine"),
                createGenerators(queueDelays, "overhead.queue"),
                createGenerators(postDelays, "overhead.post"),
                createGenerators(clusteringDelays, "overhead.clustering"),
                bandwidth);
    }

    private static Map<Integer, DistributionSpec> copyLegacy(Map<Integer, DistributionGenerator> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, DistributionSpec> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, DistributionGenerator> entry : source.entrySet()) {
            DistributionGenerator generator = entry.getValue();
            if (generator == null) {
                throw new IllegalArgumentException(
                        "Legacy overhead generator for depth " + entry.getKey() + " is null");
            }
            result.put(
                    entry.getKey(),
                    DistributionSpec.of(generator.getFamily(), generator.getScale(), generator.getShape()));
        }
        return result;
    }

    private static Map<Integer, DistributionGenerator> createGenerators(
            Map<Integer, DistributionSpec> specs, String prefix) {
        if (specs.isEmpty()) {
            return null;
        }
        Map<Integer, DistributionGenerator> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, DistributionSpec> entry : specs.entrySet()) {
            result.put(entry.getKey(), entry.getValue().createGenerator(prefix + ".depth" + entry.getKey()));
        }
        return result;
    }

    private static Map<Integer, DistributionSpec> frozenCopy(Map<Integer, DistributionSpec> source) {
        validateDelayMap(source);
        return Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(source)));
    }

    private static void validateDelayMap(Map<Integer, DistributionSpec> values) {
        if (values == null) {
            throw new IllegalArgumentException("Overhead delay map cannot be null");
        }
        for (Map.Entry<Integer, DistributionSpec> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 0 || entry.getValue() == null) {
                throw new IllegalArgumentException("Overhead delays require non-negative depths and non-null specs");
            }
            if (entry.getValue().getFamily() == DistributionGenerator.DistributionFamily.NORMAL) {
                throw new IllegalArgumentException("Overhead delays cannot use NORMAL because it can generate "
                        + "negative simulated time; use WEIBULL, GAMMA, or LOGNORMAL");
            }
        }
    }

    public static final class Builder {

        private int workflowEngineDelayInterval;
        private double bandwidth;
        private Map<Integer, DistributionSpec> workflowEngineDelays = Collections.emptyMap();
        private Map<Integer, DistributionSpec> queueDelays = Collections.emptyMap();
        private Map<Integer, DistributionSpec> postDelays = Collections.emptyMap();
        private Map<Integer, DistributionSpec> clusteringDelays = Collections.emptyMap();

        public Builder workflowEngineDelayInterval(int value) {
            workflowEngineDelayInterval = value;
            return this;
        }

        public Builder bandwidth(double value) {
            bandwidth = value;
            return this;
        }

        public Builder workflowEngineDelays(Map<Integer, DistributionSpec> value) {
            workflowEngineDelays = value;
            return this;
        }

        public Builder queueDelays(Map<Integer, DistributionSpec> value) {
            queueDelays = value;
            return this;
        }

        public Builder postDelays(Map<Integer, DistributionSpec> value) {
            postDelays = value;
            return this;
        }

        public Builder clusteringDelays(Map<Integer, DistributionSpec> value) {
            clusteringDelays = value;
            return this;
        }

        public OverheadModelConfig build() {
            if (workflowEngineDelayInterval < 0
                    || Double.isNaN(bandwidth)
                    || Double.isInfinite(bandwidth)
                    || bandwidth < 0.0) {
                throw new IllegalArgumentException("Overhead interval and bandwidth must be finite and non-negative");
            }
            return new OverheadModelConfig(
                    workflowEngineDelayInterval,
                    bandwidth,
                    workflowEngineDelays,
                    queueDelays,
                    postDelays,
                    clusteringDelays);
        }
    }
}
