package org.workflowsim.experiments.standard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.platform.PlatformProfile;

/**
 * STD-V1 (2026-11-25): hand-derived checks of the standard fleet constants (formula in the class javadoc of
 * {@link StandardPlatforms}; Opteron 2218 table from Lee and Zomaya, IEEE TPDS 2011).
 */
class StandardPlatformsTest {

    private static final double EPS = 1.0e-9;

    @Test
    void speedsAreEvenlySpacedBetween500And2000ForEveryDeclaredFleetSize() {
        // N = 8: step 1500 / 7; i = 1 -> 500 + 214.285714... = 714.2857142857143.
        assertEquals(500.0, StandardPlatforms.mips(8, 0), 0.0);
        assertEquals(500.0 + 1500.0 / 7.0, StandardPlatforms.mips(8, 1), EPS);
        assertEquals(2000.0, StandardPlatforms.mips(8, 7), 0.0);
        // N = 16: step 100; i = 5 -> 1000.
        assertEquals(1000.0, StandardPlatforms.mips(16, 5), 0.0);
        assertEquals(2000.0, StandardPlatforms.mips(16, 15), 0.0);
        // N = 32: max/min is 4 independent of N; the mean is (500 + 2000) / 2 = 1250.
        for (int n : StandardPlatforms.STANDARD_VM_COUNTS) {
            assertEquals(4.0, StandardPlatforms.mips(n, n - 1) / StandardPlatforms.mips(n, 0), 0.0);
            assertEquals(1250.0, StandardPlatforms.meanMips(n), 1.0e-9);
        }
        assertThrows(IllegalArgumentException.class, () -> StandardPlatforms.mips(1, 0));
    }

    @Test
    void priceIsLinearInSpeedAndPowerFollowsTheDeclaredRatio() {
        PlatformProfile platform = StandardPlatforms.plain(16, 10.0, 0.6);
        PlatformProfile.VmSpec vm = platform.getVms().get(5);
        // 1000 MIPS: price 1e-3 * 1000 = 1 unit/s; P_max = 0.1 * 1000 = 100 W; P_idle = 0.6 * 100 = 60 W.
        assertEquals(1000.0, vm.getMips(), 0.0);
        assertEquals(1.0, vm.getCosts().getCpuPerSecond(), EPS);
        assertEquals(100.0, vm.getMaxPowerWatts(), EPS);
        assertEquals(60.0, vm.getIdlePowerWatts(), EPS);
        assertEquals(10.0, platform.getStorage().getMaxTransferRateMbPerSecond(), 0.0);
        // Price per million instructions is identical on every VM: c = 1e-3 per MIPS-second.
        for (PlatformProfile.VmSpec each : platform.getVms()) {
            assertEquals(1.0e-3, each.getCosts().getCpuPerSecond() / each.getMips(), 1.0e-15);
        }
        assertThrows(IllegalArgumentException.class, () -> StandardPlatforms.plain(8, 10.0, 1.5));
    }

    @Test
    void opteronTiersFollowLeeZomayaVoltagesWithDynamicPowerProportionalToVSquaredF() {
        List<PlatformProfile.FrequencyTier> tiers = StandardPlatforms.opteron2218Tiers(1000.0, 0.6);
        assertEquals(6, tiers.size());
        // Default tier equals nominal: ratio 1 on every VM (uniform default-tier rule).
        assertEquals(1000.0, tiers.get(0).getMips(), 0.0);
        assertEquals(100.0, tiers.get(0).getMaxPowerWatts(), EPS);
        // Level 5: 1.05 V, 1.0 GHz. Speed 1000 * 1.0 / 2.6 = 384.6153846...;
        // relative dynamic (1.05/1.30)^2 * (1.0/2.6) = 0.6523668639 / 2.6 = 0.2509103323;
        // busy power 60 + 40 * 0.2509103323 = 70.03641329 W; idle stays at the default-tier 60 W.
        assertEquals(384.61538461538464, tiers.get(5).getMips(), 1.0e-9);
        assertEquals(70.03641329, tiers.get(5).getMaxPowerWatts(), 1.0e-6);
        assertEquals(60.0, tiers.get(5).getIdlePowerWatts(), EPS);
        // Level 1: 1.25 V, 2.4 GHz. Speed 923.0769...; (1.25/1.30)^2 * (2.4/2.6) = 0.924556 * 0.923077 = 0.853436;
        // busy power 60 + 40 * 0.853436 = 94.13744 W.
        assertEquals(923.0769230769231, tiers.get(1).getMips(), 1.0e-9);
        assertEquals(94.13746, tiers.get(1).getMaxPowerWatts(), 1.0e-4);
        PlatformProfile tiered = StandardPlatforms.tiered(8, 10.0, 0.6);
        for (PlatformProfile.VmSpec vm : tiered.getVms()) {
            assertEquals(vm.getMips(), vm.defaultFrequencyTier().getMips(), 0.0);
        }
    }
}
