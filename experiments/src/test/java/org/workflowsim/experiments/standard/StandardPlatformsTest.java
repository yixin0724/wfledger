package org.workflowsim.experiments.standard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.platform.PlatformProfile;

class StandardPlatformsTest {

    private static final double EPS = 1.0e-9;

    @Test
    void speedsAreEvenlySpacedBetween500And2000ForEveryDeclaredFleetSize() {

        assertEquals(500.0, StandardPlatforms.mips(8, 0), 0.0);
        assertEquals(500.0 + 1500.0 / 7.0, StandardPlatforms.mips(8, 1), EPS);
        assertEquals(2000.0, StandardPlatforms.mips(8, 7), 0.0);

        assertEquals(1000.0, StandardPlatforms.mips(16, 5), 0.0);
        assertEquals(2000.0, StandardPlatforms.mips(16, 15), 0.0);

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

        assertEquals(1000.0, vm.getMips(), 0.0);
        assertEquals(1.0, vm.getCosts().getCpuPerSecond(), EPS);
        assertEquals(100.0, vm.getMaxPowerWatts(), EPS);
        assertEquals(60.0, vm.getIdlePowerWatts(), EPS);
        assertEquals(10.0, platform.getStorage().getMaxTransferRateMbPerSecond(), 0.0);

        for (PlatformProfile.VmSpec each : platform.getVms()) {
            assertEquals(1.0e-3, each.getCosts().getCpuPerSecond() / each.getMips(), 1.0e-15);
        }
        assertThrows(IllegalArgumentException.class, () -> StandardPlatforms.plain(8, 10.0, 1.5));
    }

    @Test
    void opteronTiersFollowLeeZomayaVoltagesWithDynamicPowerProportionalToVSquaredF() {
        List<PlatformProfile.FrequencyTier> tiers = StandardPlatforms.opteron2218Tiers(1000.0, 0.6);
        assertEquals(6, tiers.size());

        assertEquals(1000.0, tiers.get(0).getMips(), 0.0);
        assertEquals(100.0, tiers.get(0).getMaxPowerWatts(), EPS);

        assertEquals(384.61538461538464, tiers.get(5).getMips(), 1.0e-9);
        assertEquals(70.03641329, tiers.get(5).getMaxPowerWatts(), 1.0e-6);
        assertEquals(60.0, tiers.get(5).getIdlePowerWatts(), EPS);

        assertEquals(923.0769230769231, tiers.get(1).getMips(), 1.0e-9);
        assertEquals(94.13746, tiers.get(1).getMaxPowerWatts(), 1.0e-4);
        PlatformProfile tiered = StandardPlatforms.tiered(8, 10.0, 0.6);
        for (PlatformProfile.VmSpec vm : tiered.getVms()) {
            assertEquals(vm.getMips(), vm.defaultFrequencyTier().getMips(), 0.0);
        }
    }
}
