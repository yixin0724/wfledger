package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.platform.PlatformProfile;

class FrequencyTierInputFuzzTest {

    @Test
    void nanAndInfinityOnEveryFieldAreRejectedWithFieldNaming() {

        mustCite(() -> new PlatformProfile.FrequencyTier(Double.NaN, 100.0, 200.0), "mips", "NaN mips");
        mustCite(() -> new PlatformProfile.FrequencyTier(Double.POSITIVE_INFINITY, 100.0, 200.0), "mips", "+Inf mips");
        mustCite(() -> new PlatformProfile.FrequencyTier(Double.NEGATIVE_INFINITY, 100.0, 200.0), "mips", "-Inf mips");

        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, Double.NaN, 200.0), "idle", "NaN idle");
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, Double.POSITIVE_INFINITY, 200.0), "idle", "+Inf idle");
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, Double.NEGATIVE_INFINITY, 200.0), "idle", "-Inf idle");

        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, 100.0, Double.NaN), "max", "NaN max");
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, 100.0, Double.POSITIVE_INFINITY), "max", "+Inf max");
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, 100.0, Double.NEGATIVE_INFINITY), "max", "-Inf max");

        IllegalArgumentException finite = assertThrows(
                IllegalArgumentException.class,
                () -> new PlatformProfile.FrequencyTier(
                        Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
        assertTrue(
                finite.getMessage().contains("finite"),
                "rejection must cite the finite clause: " + finite.getMessage());
    }

    @Test
    void degenerateFiniteValuesAreRejectedWithFieldNaming() {

        mustCite(() -> new PlatformProfile.FrequencyTier(0.0, 100.0, 200.0), "mips", "zero mips");
        mustCite(() -> new PlatformProfile.FrequencyTier(-1000.0, 100.0, 200.0), "mips", "negative mips");

        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, -0.5, 200.0), "idle", "negative idle");

        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, 250.0, 200.0), "max", "reversed max<idle");

        assertEquals(
                250.0,
                new PlatformProfile.FrequencyTier(1000.0, 250.0, 250.0).getMaxPowerWatts(),
                0.0,
                "max==idle is the degenerate zero-dynamic tier, legal by contract");
    }

    @Test
    void duplicateMipsInATwoTierTableIsRejectedCitingTheOrderingClause() {
        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> spec(Arrays.asList(
                        new PlatformProfile.FrequencyTier(1000.0, 220.0, 280.0),
                        new PlatformProfile.FrequencyTier(1000.0, 210.0, 240.0))),
                "two tiers with exactly equal mips must be rejected (duplicate tier)");
        assertTrue(
                duplicate.getMessage().contains("descending"),
                "rejection must cite the strict descending clause: " + duplicate.getMessage());
        assertTrue(
                duplicate.getMessage().contains("mips"),
                "rejection must cite the mips field: " + duplicate.getMessage());

        IllegalArgumentException middle = assertThrows(
                IllegalArgumentException.class,
                () -> spec(Arrays.asList(
                        new PlatformProfile.FrequencyTier(2000.0, 250.0, 400.0),
                        new PlatformProfile.FrequencyTier(1000.0, 220.0, 280.0),
                        new PlatformProfile.FrequencyTier(1000.0, 210.0, 240.0))),
                "middle duplicate must be rejected too");
        assertTrue(middle.getMessage().contains("descending"), "same clause: " + middle.getMessage());
    }

    @Test
    void tiersAndPlainPowerStillMutuallyExclusiveCitingBothFaces() {
        IllegalArgumentException both = assertThrows(
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
                        Collections.singletonList(new PlatformProfile.FrequencyTier(1000.0, 220.0, 280.0))),
                "one source of truth");
        assertTrue(
                both.getMessage().contains("frequencyTiers")
                        && both.getMessage().contains("idlePowerWatts")
                        && both.getMessage().contains("maxPowerWatts"),
                "rejection must cite BOTH power faces: " + both.getMessage());
    }

    @Test
    void v1TierlessInputsRemainLegalOnEveryConstructorPath() {
        for (PlatformProfile.VmSpec spec : Arrays.asList(
                v1Spec(null),
                v1Spec(new ArrayList<PlatformProfile.FrequencyTier>()),
                new PlatformProfile.VmSpec(
                        0,
                        1000.0,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        250.0,
                        400.0,
                        null),
                new PlatformProfile.VmSpec(
                        0,
                        1000.0,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        250.0,
                        400.0))) {
            assertFalse(spec.hasFrequencyTiers(), "tierless construction must stay v1");
            assertTrue(spec.getFrequencyTiers().isEmpty(), "no tier table");
            assertTrue(spec.hasPowerModel(), "v1 flat power works through the same constructor");
        }
        try {
            v1Spec(null).defaultFrequencyTier();
            org.junit.jupiter.api.Assertions.fail(
                    "tierless VmSpec.defaultFrequencyTier() must fail loudly, not fabricate a tier");
        } catch (IndexOutOfBoundsException | IllegalStateException expected) {

        }
    }

    @Test
    void singleElementTierListIsTheLegalV1DegenerationNotARejection() {
        PlatformProfile.VmSpec single =
                spec(Collections.singletonList(new PlatformProfile.FrequencyTier(1000.0, 250.0, 400.0)));
        assertTrue(single.hasFrequencyTiers(), "single-tier table IS DVFS mode (evidence face: tierMips recorded)");
        assertEquals(1000.0, single.defaultFrequencyTier().getMips(), 0.0, "the only tier is the default tier");
        assertEquals(0.0, single.getIdlePowerWatts(), 0.0, "flat power fields stay zero (one source of truth)");
    }

    private static PlatformProfile.VmSpec spec(List<PlatformProfile.FrequencyTier> tiers) {
        return new PlatformProfile.VmSpec(
                0,
                1000.0,
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
                tiers);
    }

    private static PlatformProfile.VmSpec v1Spec(List<PlatformProfile.FrequencyTier> tiers) {
        if (tiers != null && !tiers.isEmpty()) {
            throw new IllegalArgumentException("v1Spec is the tierless face; use spec() for tier tables");
        }
        return new PlatformProfile.VmSpec(
                0,
                1000.0,
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
                tiers);
    }

    private interface Thunk {

        void run();
    }

    private static void mustCite(Thunk thunk, String needle, String what) {
        IllegalArgumentException rejection =
                assertThrows(IllegalArgumentException.class, thunk::run, what + " must be rejected");
        String message = rejection.getMessage().toLowerCase();
        boolean cites =
                java.util.Arrays.stream(needle.toLowerCase().split("[| ]")).anyMatch(message::contains);
        assertTrue(cites, what + " rejection must cite the field [" + needle + "]: " + message);

        assertTrue(
                message.contains("required") || message.contains("descending") || message.contains("source of truth"),
                what + " rejection must state the violated domain rule: " + message);
    }
}
