package org.workflowsim.rl;

import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;

/**
 * Environment facade for the R4 RL track: wraps the platform's deterministic event loop
 * in an episode interface.
 *
 * <p>One {@link #runEpisode(SimulationConfig, PlatformProfile, RlPolicy)} call is one full
 * episode: register the policy -> run the simulation through the standard
 * {@link SimulationRunner} (the RL_POLICY scheduler asks the policy for a Job-to-VM action
 * on every ready-batch update) -> collect the decision trace -> release the registry ->
 * return an {@link RlEpisodeResult} (makespan, reward = -makespan, step count, trace).</p>
 *
 * <p><b>Boundary</b>: this environment provides the state, action, and reward contract and
 * a deterministic closed loop, not the learning algorithm itself; connecting an external
 * trainer such as PyTorch/JAX requires an out-of-process bridge (the platform is a
 * single-process Java event loop) and is an extension direction after R4. The baseline
 * policy {@link EarliestFinishGreedyPolicy} validates the closed loop and provides a
 * comparable reference score. As a whole this track is a demo interface (AUD-F11): the
 * observation is non-Markovian, the reward is terminal-only, and the policy registry is a
 * static single-process registry, so concurrent episodes in one JVM are not supported --
 * see the {@code org.workflowsim.rl} package description for the full boundary
 * statement.</p>
 */
public final class RlEnvironment {

    /**
     * Runs one complete episode.
     *
     * @param config the scheduling algorithm must be {@link SchedulingAlgorithm#RL_POLICY}
     * @param platform platform description (consistent with the standard runner contract)
     * @param policy scheduling policy for this episode (must be deterministic)
     * @return episode result (evidence report + reward + decision trace)
     * @throws SimulationConfigurationException when the configuration does not select
     *         RL_POLICY or the policy is null
     * @throws org.workflowsim.exception.SimulationExecutionException when simulation
     *         execution fails
     */
    public RlEpisodeResult runEpisode(SimulationConfig config, PlatformProfile platform, RlPolicy policy)
            throws org.workflowsim.exception.SimulationExecutionException {
        if (config == null) {
            throw new SimulationConfigurationException("Configuration is required");
        }
        if (config.getSchedulingAlgorithm() != SchedulingAlgorithm.RL_POLICY) {
            throw new SimulationConfigurationException("RlEnvironment requires " + "SchedulingAlgorithm.RL_POLICY, got "
                    + config.getSchedulingAlgorithm());
        }
        RlPolicyRegistry.init(policy);
        try {
            SimulationReport report = new SimulationRunner().run(config, platform);
            return new RlEpisodeResult(report, RlPolicyRegistry.decisions(), RlPolicyRegistry.stepCount());
        } finally {
            RlPolicyRegistry.reset();
        }
    }
}
