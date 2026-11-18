package org.workflowsim.experiment.registry;

import java.util.List;

import org.workflowsim.Job;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;
import org.workflowsim.scheduling.FCFSSchedulingAlgorithm;
import org.workflowsim.scheduling.FrequencyTierSelector;

public final class SelectorNullTierDvfsTestSchedulerProvider implements SchedulingAlgorithmProvider {

    public static final String LABEL = "tier-selector-null";

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public BaseSchedulingAlgorithm create() {
        return new SelectorNullSchedulingAlgorithm();
    }

    @Override
    public AlgorithmDeclaration declaration() {
        return new AlgorithmDeclaration.Builder()
                .dvfsAware(true)
                .frequencyTiersRequired(true)
                .description("BAT-D6 negative-face provider: selector returns null; must be"
                        + " rejected fail-fast, never silently fallen back to the default tier")
                .build();
    }

    static final class SelectorNullSchedulingAlgorithm extends FCFSSchedulingAlgorithm
            implements FrequencyTierSelector {

        @Override
        public PlatformProfile.FrequencyTier selectFrequencyTier(Job job, List<PlatformProfile.FrequencyTier> tiers) {
            return null;
        }
    }
}
