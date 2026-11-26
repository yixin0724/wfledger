package org.workflowsim.experiments.standard;

import java.util.Map;
import java.util.TreeMap;

import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.SimulationConfig;

/** Deadline levels interpolated between a fast and a slow reference schedule. */
public final class DeadlineInterpolation {

    public static final double[] STANDARD_LEVELS = {0.1, 0.3, 0.5, 0.7, 0.9};

    private DeadlineInterpolation() {}

    public static double deadline(double fastMakespan, double slowMakespan, double level) {
        if (!(fastMakespan > 0.0) || Double.isInfinite(slowMakespan) || !(slowMakespan >= fastMakespan)) {
            throw new IllegalArgumentException("Deadline references require 0 < MS_fast <= MS_slow < inf: fast="
                    + fastMakespan + " slow=" + slowMakespan);
        }
        if (!(level >= 0.0) || level > 1.0) {
            throw new IllegalArgumentException("Deadline level must lie in [0, 1]: " + level);
        }
        return fastMakespan + level * (slowMakespan - fastMakespan);
    }

    public static References measure(SimulationConfig referenceConfig, PlatformProfile tieredPlatform)
            throws SimulationExecutionException {
        SimulationRunner runner = new SimulationRunner();
        SimulationReport fast = runner.run(
                referenceConfig.toBuilder()
                        .frequencyPolicy(SimulationConfig.FrequencyPolicy.DEFAULT)
                        .build(),
                tieredPlatform);
        SimulationReport slow = runner.run(
                referenceConfig.toBuilder()
                        .frequencyPolicy(SimulationConfig.FrequencyPolicy.MIN_POWER)
                        .build(),
                tieredPlatform);
        if (!fast.isWorkflowCompletedSuccessfully() || !slow.isWorkflowCompletedSuccessfully()) {
            throw new IllegalStateException("Deadline reference runs must complete every logical task");
        }
        Map<Integer, Integer> fastMapping = mapping(fast);
        Map<Integer, Integer> slowMapping = mapping(slow);
        if (!fastMapping.equals(slowMapping)) {
            throw new IllegalStateException("MS_slow must reuse the MS_fast mapping; the planner placed tasks "
                    + "differently under MIN_POWER: " + fastMapping + " vs " + slowMapping);
        }
        return new References(fast.getMakespan(), slow.getMakespan());
    }

    private static Map<Integer, Integer> mapping(SimulationReport report) {
        Map<Integer, Integer> mapping = new TreeMap<Integer, Integer>();
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            Integer previous = mapping.put(task.getTaskId(), task.getVmId());
            if (previous != null && previous.intValue() != task.getVmId()) {
                throw new IllegalStateException("Task " + task.getTaskId() + " ran on two VMs in a failure-free run");
            }
        }
        return mapping;
    }

    /** The two reference makespans. */
    public static final class References {
        private final double fastMakespan;
        private final double slowMakespan;

        References(double fastMakespan, double slowMakespan) {
            this.fastMakespan = fastMakespan;
            this.slowMakespan = slowMakespan;
        }

        public double getFastMakespan() {
            return fastMakespan;
        }

        public double getSlowMakespan() {
            return slowMakespan;
        }

        public double deadline(double level) {
            return DeadlineInterpolation.deadline(fastMakespan, slowMakespan, level);
        }
    }
}
