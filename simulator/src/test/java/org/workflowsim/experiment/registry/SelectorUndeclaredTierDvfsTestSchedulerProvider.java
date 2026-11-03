package org.workflowsim.experiment.registry;

import java.util.List;

import org.workflowsim.Job;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;
import org.workflowsim.scheduling.FCFSSchedulingAlgorithm;
import org.workflowsim.scheduling.FrequencyTierSelector;

/**
 * BAT-D6 (2026-10-29) test-domain breakage face two: the selector returns an off-table tier
 * (mips 1500.0 is not in the declared table 2000/1000/500; the value sits exactly in between,
 * which makes the self-deception compelling). A tier enters the evidence chain with MIPS as
 * its unique identity (JobOutcome.tierMips -> Python audit tierByMips, same clause); an
 * off-table tier must be rejected explicitly, never silently rounded to the "nearest
 * declared tier".
 */
public final class SelectorUndeclaredTierDvfsTestSchedulerProvider implements SchedulingAlgorithmProvider {

    public static final String LABEL = "tier-selector-undeclared";

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public BaseSchedulingAlgorithm create() {
        return new SelectorUndeclaredSchedulingAlgorithm();
    }

    @Override
    public AlgorithmDeclaration declaration() {
        return new AlgorithmDeclaration.Builder()
                .dvfsAware(true)
                .frequencyTiersRequired(true)
                .description("BAT-D6 negative-face provider: selector returns an undeclared"
                        + " tier; must be rejected citing the per-Job tier evidence chain")
                .build();
    }

    static final class SelectorUndeclaredSchedulingAlgorithm extends FCFSSchedulingAlgorithm
            implements FrequencyTierSelector {

        /** Off-table tier: mips=1500 (between declared 2000/1000); power pair well-formed, tier identity undeclared. */
        private static final PlatformProfile.FrequencyTier UNDECLARED_TIER =
                new PlatformProfile.FrequencyTier(1500.0, 205.0, 300.0);

        @Override
        public PlatformProfile.FrequencyTier selectFrequencyTier(Job job, List<PlatformProfile.FrequencyTier> tiers) {
            return UNDECLARED_TIER;
        }
    }
}
