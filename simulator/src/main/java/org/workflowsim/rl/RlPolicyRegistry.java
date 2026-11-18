package org.workflowsim.rl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.workflowsim.exception.SimulationConfigurationException;

public final class RlPolicyRegistry {

    private static RlPolicy currentPolicy;
    private static final List<RlDecision> DECISIONS = new ArrayList<RlDecision>();
    private static int stepCount;

    private RlPolicyRegistry() {}

    public static void init(RlPolicy policy) {
        if (policy == null) {
            throw new SimulationConfigurationException("RL policy is required");
        }
        currentPolicy = policy;
        DECISIONS.clear();
        stepCount = 0;
    }

    public static RlPolicy current() {
        if (currentPolicy == null) {
            throw new SimulationConfigurationException("SchedulingAlgorithm.RL_POLICY requires an RL "
                    + "policy registered through RlEnvironment.runEpisode; run the episode through the "
                    + "environment facade instead of SimulationRunner directly");
        }
        return currentPolicy;
    }

    public static void recordDecision(double time, int jobId, int vmId) {
        DECISIONS.add(new RlDecision(time, jobId, vmId));
    }

    public static void recordStep() {
        stepCount++;
    }

    public static List<RlDecision> decisions() {
        return Collections.unmodifiableList(new ArrayList<RlDecision>(DECISIONS));
    }

    public static int stepCount() {
        return stepCount;
    }

    public static void reset() {
        currentPolicy = null;
        DECISIONS.clear();
        stepCount = 0;
    }
}
