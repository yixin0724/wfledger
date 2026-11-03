package org.workflowsim.experiment.registry;

import java.util.List;

import org.workflowsim.Job;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;
import org.workflowsim.scheduling.FCFSSchedulingAlgorithm;
import org.workflowsim.scheduling.FrequencyTierSelector;

/**
 * BAT-D6 (2026-10-29) test-domain registration provider: the legal consumption surface of
 * the per-Job frequency-tier assignment API {@link FrequencyTierSelector} -- every Job
 * fixed to the <b>last tier</b> of the table (the same tier as MIN_POWER, but from a
 * different source: an algorithm-side per-Job decision rather than a session-level policy).
 *
 * <p>The test uses it to pin down precedence: the same run keeps its session policy at
 * DEFAULT (first tier), so if the active policy instance implements the selector,
 * JobOutcome.tierMips must be the last tier rather than the first -- proving that
 * "algorithm-side per-Job tier selection moves up" genuinely takes effect instead of
 * restating the session policy. VM placement deliberately inherits FCFS (lowest-numbered
 * idle compatible VM), so the only axis on which it differs from the built-in FCFS control
 * arm is the frequency-tier decision.</p>
 */
public final class SelectorTierDvfsTestSchedulerProvider implements SchedulingAlgorithmProvider {

    public static final String LABEL = "tier-selector-last";

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public BaseSchedulingAlgorithm create() {
        return new SelectorLastSchedulingAlgorithm();
    }

    @Override
    public AlgorithmDeclaration declaration() {
        return new AlgorithmDeclaration.Builder()
                .dvfsAware(true)
                .frequencyTiersRequired(true)
                .description("BAT-D6 test provider: per-Job selector always picking the last"
                        + " (lowest-mips) tier; exercises the scheduling-time tier API")
                .build();
    }

    /** FCFS placement plus a selector that always returns the last tier for each Job. */
    static final class SelectorLastSchedulingAlgorithm extends FCFSSchedulingAlgorithm
            implements FrequencyTierSelector {

        @Override
        public PlatformProfile.FrequencyTier selectFrequencyTier(Job job, List<PlatformProfile.FrequencyTier> tiers) {
            return tiers.get(tiers.size() - 1);
        }
    }
}
