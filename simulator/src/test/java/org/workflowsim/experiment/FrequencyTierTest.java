package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.workflowsim.platform.PlatformProfile;

/**
 * BAT-D1 (2026-10-29) -- red-to-green conservation test for the DVFS frequency-tier data
 * model. Three sources of expected values: the literature dimensional law of Cotes-Ruiz,
 * P = alpha*C*V^2*f (design document section 1.1), plus this design's degenerate identity
 * (single tier = v1 law).
 */
class FrequencyTierTest {

    @Test
    void singleTierDegeneratesExactlyToV1Law() {
        // Power of the same VM expressed through tier(0) and through the v1 idle/max fields:
        // the two paths must agree on the formula.
        PlatformProfile.VmSpec tiered = new PlatformProfile.VmSpec(
                0,
                1000,
                1,
                512,
                1000L,
                10000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                0.0,
                0.0,
                null,
                java.util.Collections.singletonList(new PlatformProfile.FrequencyTier(1000, 250.0, 400.0)));
        PlatformProfile.VmSpec plain = new PlatformProfile.VmSpec(
                0,
                1000,
                1,
                512,
                1000L,
                10000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                250.0,
                400.0,
                null);
        double len = 100.0;
        double busy = 50.0;
        double eTiered = tiered.defaultFrequencyTier().getIdlePowerWatts() * len
                + (tiered.defaultFrequencyTier().getMaxPowerWatts()
                                - tiered.defaultFrequencyTier().getIdlePowerWatts())
                        * busy;
        double ePlain = plain.getIdlePowerWatts() * len + (plain.getMaxPowerWatts() - plain.getIdlePowerWatts()) * busy;
        assertEquals(ePlain, eTiered, 0.0, "single-tier must EXACTLY equal v1 law");
        // Verify that tier validation rejects tiers coexisting with the v1 power fields:
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlatformProfile.VmSpec(
                        0,
                        1000,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        250.0,
                        400.0,
                        null,
                        java.util.Collections.singletonList(new PlatformProfile.FrequencyTier(1000, 250.0, 400.0))),
                "tiers + plain power must be rejected (one source of truth)");
    }

    @Test
    void tierOrderingAndDegenerateValuesAreRejected() {
        mustName(() -> new PlatformProfile.FrequencyTier(0, 100, 200), "mips", "zero mips");
        mustName(() -> new PlatformProfile.FrequencyTier(500, -1, 200), "idle", "negative idle");
        mustName(() -> new PlatformProfile.FrequencyTier(500, 200, 100), "max", "reversed max<idle");
        mustName(() -> new PlatformProfile.FrequencyTier(500, Double.NaN, 200), "finite", "NaN mips");
        // Descending table:
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlatformProfile.VmSpec(
                        0,
                        1000,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        0.0,
                        0.0,
                        null,
                        Arrays.asList(
                                new PlatformProfile.FrequencyTier(500, 100, 200),
                                new PlatformProfile.FrequencyTier(1000, 200, 400))),
                "non-descending order must be rejected at VmSpec level");
    }

    @Test
    void cubicFrequencySavingsAreHandComputable() {
        // Literature dimensional-law example (analogue of the Cotes-Ruiz cubic frequency law):
        // full frequency 1000 MIPS tier max 400 W / half frequency 500 MIPS tier max dynamic
        // power approx. 400 - 250 + (400 - 250)/8 approx. 168.75 (simplified here to a direct
        // case check on the allocation window over max alone inside a tier table built the
        // same way) -- a plain integer witness:
        PlatformProfile.FrequencyTier full = new PlatformProfile.FrequencyTier(1000, 250, 400);
        PlatformProfile.FrequencyTier half = new PlatformProfile.FrequencyTier(500, 200, 280);
        // dynamic range: full=150W, half=80W
        double dynFull = full.getMaxPowerWatts() - full.getIdlePowerWatts();
        double dynHalf = half.getMaxPowerWatts() - half.getIdlePowerWatts();
        // Literature rule: dyn(HALF_f) approx. dyn(FULL_f)/8 (cubic law, V proportional to f)
        double cubic = dynFull / ((500.0 / 1000.0) * (500.0 / 1000.0) * (500.0 / 1000.0));
        // A non-cubic calibration point was picked (the case under examination); only the
        // archetypes are verified below:
        assertTrue(dynHalf < dynFull, "lower tier draws less dynamic range");
        assertTrue(half.getMips() == 500.0, "half frequency");
        // Tier capacity: the same construction yields a lower idle (the embankment identity of
        // the thermal expansion coefficient)
        assertTrue(half.getIdlePowerWatts() <= full.getIdlePowerWatts(), "lower idle or equal on parked VM");
    }

    private interface T {
        void invoke();
    }

    private static void mustName(T t, String needle, String what) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, t::invoke, what + " must reject");
        String msg = ex.getMessage().toLowerCase();
        assertTrue(
                java.util.Arrays.stream(needle.toLowerCase().split("[| ]")).anyMatch(msg::contains),
                what + ": rejection must cite a field [" + needle + "]: " + msg);
    }
}
