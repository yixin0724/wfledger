package org.workflowsim.scheduling;

import java.util.List;

import org.workflowsim.Job;
import org.workflowsim.platform.PlatformProfile;

public interface FrequencyTierSelector {

    PlatformProfile.FrequencyTier selectFrequencyTier(Job job, List<PlatformProfile.FrequencyTier> tiers);
}
