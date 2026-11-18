package org.workflowsim.planning.dvfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.workflowsim.platform.PlatformProfile;

class DvfsSlackOptimizerTest {

    @Test
    void acceptsLowerTiersOnlyWhenEnergyAndDeadlineBothAllow() {
        DvfsSlackTask first = new DvfsSlackTask(1, 0, 1000L, Collections.<Integer>emptyList());
        DvfsSlackTask second = new DvfsSlackTask(2, 0, 1000L, Collections.singletonList(1));
        Map<Integer, List<PlatformProfile.FrequencyTier>> tiers =
                new LinkedHashMap<Integer, List<PlatformProfile.FrequencyTier>>();
        tiers.put(
                0,
                Arrays.asList(
                        new PlatformProfile.FrequencyTier(1000.0, 10.0, 100.0),
                        new PlatformProfile.FrequencyTier(500.0, 2.0, 20.0)));

        DvfsSlackSchedule schedule = new DvfsSlackOptimizer().optimize(Arrays.asList(first, second), tiers, 4.0);

        assertEquals(500.0, schedule.getTaskPlan(1).getTierMips(), 1.0e-9);
        assertEquals(500.0, schedule.getTaskPlan(2).getTierMips(), 1.0e-9);
        assertEquals(4.0, schedule.getMakespanSeconds(), 1.0e-9);
        assertEquals(80.0, schedule.getTotalEnergyJoules(), 1.0e-9);
    }

    @Test
    void unusedVmIdleEnergyPreventsAnEnergyIncreasingDowngrade() {
        DvfsSlackTask task = new DvfsSlackTask(1, 0, 1000L, Collections.<Integer>emptyList());
        Map<Integer, List<PlatformProfile.FrequencyTier>> tiers =
                new LinkedHashMap<Integer, List<PlatformProfile.FrequencyTier>>();
        tiers.put(
                0,
                Arrays.asList(
                        new PlatformProfile.FrequencyTier(1000.0, 10.0, 100.0),
                        new PlatformProfile.FrequencyTier(500.0, 0.0, 40.0)));
        tiers.put(1, Collections.singletonList(new PlatformProfile.FrequencyTier(1000.0, 100.0, 100.0)));

        DvfsSlackSchedule schedule = new DvfsSlackOptimizer().optimize(Collections.singletonList(task), tiers, 2.0);

        assertEquals(1000.0, schedule.getTaskPlan(1).getTierMips(), 1.0e-9);
        assertEquals(200.0, schedule.getTotalEnergyJoules(), 1.0e-9);
    }

    @Test
    void keepsTheOriginalTierWhenDownscalingWouldIncreaseEnergy() {
        DvfsSlackTask task = new DvfsSlackTask(1, 0, 1000L, Collections.<Integer>emptyList());
        Map<Integer, List<PlatformProfile.FrequencyTier>> tiers =
                new LinkedHashMap<Integer, List<PlatformProfile.FrequencyTier>>();
        tiers.put(
                0,
                Arrays.asList(
                        new PlatformProfile.FrequencyTier(1000.0, 0.0, 1.0),
                        new PlatformProfile.FrequencyTier(500.0, 0.0, 2.0)));

        DvfsSlackSchedule schedule = new DvfsSlackOptimizer().optimize(Collections.singletonList(task), tiers, 2.0);

        assertEquals(1000.0, schedule.getTaskPlan(1).getTierMips(), 1.0e-9);
        assertThrows(IllegalArgumentException.class, () -> new DvfsSlackOptimizer()
                .optimize(Collections.singletonList(task), tiers, 0.5));
    }
}
