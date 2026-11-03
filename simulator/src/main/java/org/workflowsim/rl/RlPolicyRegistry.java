package org.workflowsim.rl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.workflowsim.exception.SimulationConfigurationException;

/**
 * Session-level registry for RL policies: delivers the policy instance into the scheduler
 * and collects the decision trace.
 *
 * <p>Scheduler instances are constructed from an enum by {@code WorkflowScheduler} and
 * cannot receive a policy through a constructor, so the registry follows the existing
 * platform static-configuration pattern (see {@code FailureParameters}): {@code RlEnvironment}
 * calls {@link #init(RlPolicy)} before each episode starts and {@link #reset()} after it
 * ends. The registry is not a thread-safe resource; platform simulation is a single-threaded
 * event loop.</p>
 */
public final class RlPolicyRegistry {

    private static RlPolicy currentPolicy;
    private static final List<RlDecision> DECISIONS = new ArrayList<RlDecision>();
    private static int stepCount;

    private RlPolicyRegistry() {}

    /**
     * Registers the episode policy and clears the trace.
     *
     * @param policy policy used by this episode
     * @throws SimulationConfigurationException when the policy is null
     */
    public static void init(RlPolicy policy) {
        if (policy == null) {
            throw new SimulationConfigurationException("RL policy is required");
        }
        currentPolicy = policy;
        DECISIONS.clear();
        stepCount = 0;
    }

    /**
     * @return the currently registered policy
     * @throws SimulationConfigurationException when no policy is registered (the RL_POLICY
     *         scheduler must run inside an episode managed by {@code RlEnvironment})
     */
    public static RlPolicy current() {
        if (currentPolicy == null) {
            throw new SimulationConfigurationException("SchedulingAlgorithm.RL_POLICY requires an RL "
                    + "policy registered through RlEnvironment.runEpisode; run the episode through the "
                    + "environment facade instead of SimulationRunner directly");
        }
        return currentPolicy;
    }

    /**
     * Records one successful dispatch decision.
     *
     * <p>For {@code RlPolicySchedulingAlgorithm} use only, at the moment it performs a
     * dispatch; other callers must not write to the trace.</p>
     */
    public static void recordDecision(double time, int jobId, int vmId) {
        DECISIONS.add(new RlDecision(time, jobId, vmId));
    }

    /**
     * Records one policy query (one step per ready-batch update, regardless of how many
     * dispatches it produces).
     *
     * <p>For {@code RlPolicySchedulingAlgorithm} use only, before it requests an action.</p>
     */
    public static void recordStep() {
        stepCount++;
    }

    /** @return the decision trace of this episode (immutable, insertion order) */
    public static List<RlDecision> decisions() {
        return Collections.unmodifiableList(new ArrayList<RlDecision>(DECISIONS));
    }

    /** @return the policy query count of this episode (one step per ready-batch update) */
    public static int stepCount() {
        return stepCount;
    }

    /** Releases the episode state; tests must call it after every case. */
    public static void reset() {
        currentPolicy = null;
        DECISIONS.clear();
        stepCount = 0;
    }
}
