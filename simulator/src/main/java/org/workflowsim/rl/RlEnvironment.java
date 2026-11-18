package org.workflowsim.rl;

import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;

public final class RlEnvironment {

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
