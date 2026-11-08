package org.workflowsim.scheduling;

import java.util.List;

import org.workflowsim.Job;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DvfsSlackRegistry;

/** STATIC dispatcher that consumes the planner's declared per-task DVFS tier. */
public final class DvfsSlackSchedulingAlgorithm extends StaticSchedulingAlgorithm implements FrequencyTierSelector {

    @Override
    public PlatformProfile.FrequencyTier selectFrequencyTier(Job job, List<PlatformProfile.FrequencyTier> tiers) {
        double selected = DvfsSlackRegistry.getTierMips(job.getTaskList().get(0).getCloudletId());
        for (PlatformProfile.FrequencyTier tier : tiers) {
            if (Double.compare(tier.getMips(), selected) == 0) {
                return tier;
            }
        }
        throw new IllegalStateException("Planner selected an undeclared DVFS tier for Job " + job.getCloudletId());
    }
}
