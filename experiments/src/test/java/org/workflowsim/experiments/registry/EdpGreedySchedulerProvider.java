package org.workflowsim.experiments.registry;

import java.util.List;

import org.workflowsim.Job;
import org.workflowsim.experiment.registry.AlgorithmDeclaration;
import org.workflowsim.experiment.registry.SchedulingAlgorithmProvider;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;
import org.workflowsim.scheduling.FCFSSchedulingAlgorithm;
import org.workflowsim.scheduling.FrequencyTierSelector;

public final class EdpGreedySchedulerProvider implements SchedulingAlgorithmProvider {

    public static final String LABEL = "EDP_GREEDY";

    public static final double LONG_JOB_THRESHOLD_MI = 60_000.0;

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public BaseSchedulingAlgorithm create() {
        return new EdpGreedySchedulingAlgorithm();
    }

    @Override
    public AlgorithmDeclaration declaration() {
        return new AlgorithmDeclaration.Builder()
                .fileSystemScope(AlgorithmDeclaration.FileSystemScope.ANY)
                .allowDataMovementKind("LEGACY_WORKFLOWSIM_V1")
                .acceptsTaskCostMatrix(false)
                .allowsFailureModel(false)
                .dvfsAware(true)
                .frequencyTiersRequired(true)
                .powerModelRequired(true)
                .description("D6 demonstrator: FCFS placement with a length-based per-Job tier"
                        + " split (long jobs to the lowest tier, short jobs to the default tier)"
                        + " through the scheduling-time tier API; requires a tiered powered"
                        + " profile; a slack-aware EDP engine is follow-up work")
                .build();
    }

    static final class EdpGreedySchedulingAlgorithm extends FCFSSchedulingAlgorithm implements FrequencyTierSelector {

        @Override
        public PlatformProfile.FrequencyTier selectFrequencyTier(Job job, List<PlatformProfile.FrequencyTier> tiers) {

            long workMi = job.getEffectiveExecutionLengthMi();
            return workMi >= LONG_JOB_THRESHOLD_MI ? tiers.get(tiers.size() - 1) : tiers.get(0);
        }
    }
}
