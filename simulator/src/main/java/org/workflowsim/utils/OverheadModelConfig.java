package org.workflowsim.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable overhead model whose sampled delays are instantiated inside the session.
 *
 * <p>All four delay maps are keyed by task depth; key {@code 0} keeps its historical meaning as
 * the default delay used when no depth is configured explicitly. This configuration holds no
 * mutable sampling state: the actual generators are created in a session whose root seed has
 * already been set.</p>
 */
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

    /** @return shared immutable model that introduces no workflow overhead */
    public static OverheadModelConfig none() {
        return NONE;
    }

    /** @return a new overhead model builder */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Compatibility bridge for early {@link SimulationConfig} callers.
     *
     * <p>The method extracts only the distribution family and parameters of the legacy generators
     * and resamples them in the session under the configured root seed, so that existing mutable
     * random state cannot leak into a new experiment session.</p>
     *
     * @param parameters legacy overhead parameters
     * @return equivalent immutable overhead model
     */
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

    /**
     * @return maximum Jobs per WED sub-batch; 0 means no split, and non-empty batches still apply
     *         the configured delays
     */
    public int getWorkflowEngineDelayInterval() {
        return workflowEngineDelayInterval;
    }
    /** @return logical bandwidth used by the legacy overhead model */
    public double getBandwidth() {
        return bandwidth;
    }
    /** @return workflow engine delay distributions keyed by task depth */
    public Map<Integer, DistributionSpec> getWorkflowEngineDelays() {
        return workflowEngineDelays;
    }
    /** @return queue delay distributions keyed by task depth */
    public Map<Integer, DistributionSpec> getQueueDelays() {
        return queueDelays;
    }
    /** @return post-processing delay distributions keyed by task depth */
    public Map<Integer, DistributionSpec> getPostDelays() {
        return postDelays;
    }
    /** @return clustering delay distributions keyed by task depth */
    public Map<Integer, DistributionSpec> getClusteringDelays() {
        return clusteringDelays;
    }

    /**
     * Creates new stateful legacy overhead parameters for a session whose random seed is already
     * set.
     *
     * @return overhead parameters used by the historical execution path
     */
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

    /**
     * Overhead model builder that can be rebuilt deterministically and recorded in the experiment
     * manifest.
     */
    public static final class Builder {

        private int workflowEngineDelayInterval;
        private double bandwidth;
        private Map<Integer, DistributionSpec> workflowEngineDelays = Collections.emptyMap();
        private Map<Integer, DistributionSpec> queueDelays = Collections.emptyMap();
        private Map<Integer, DistributionSpec> postDelays = Collections.emptyMap();
        private Map<Integer, DistributionSpec> clusteringDelays = Collections.emptyMap();

        /**
         * @param value maximum Jobs per WED sub-batch; 0 leaves the whole batch unsplit
         * @return this builder
         */
        public Builder workflowEngineDelayInterval(int value) {
            workflowEngineDelayInterval = value;
            return this;
        }

        /**
         * @param value logical bandwidth of the legacy overhead model
         * @return this builder
         */
        public Builder bandwidth(double value) {
            bandwidth = value;
            return this;
        }

        /**
         * @param value workflow engine delay distributions
         * @return this builder
         */
        public Builder workflowEngineDelays(Map<Integer, DistributionSpec> value) {
            workflowEngineDelays = value;
            return this;
        }

        /**
         * @param value queue delay distributions
         * @return this builder
         */
        public Builder queueDelays(Map<Integer, DistributionSpec> value) {
            queueDelays = value;
            return this;
        }

        /**
         * @param value post-processing delay distributions
         * @return this builder
         */
        public Builder postDelays(Map<Integer, DistributionSpec> value) {
            postDelays = value;
            return this;
        }

        /**
         * @param value clustering delay distributions
         * @return this builder
         */
        public Builder clusteringDelays(Map<Integer, DistributionSpec> value) {
            clusteringDelays = value;
            return this;
        }

        /**
         * Validates the numeric ranges and creates the overhead model.
         *
         * @return immutable overhead model
         */
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
