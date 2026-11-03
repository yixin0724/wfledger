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

/**
 * BAT-V1 (2026-10-29 test-engineer adversarial round, DVFS round 5): adversarial fuzzing
 * of the frequency-tier input surface -- expected values from three sources: the D1 data
 * tier contract (mips&gt;0, idle>=0, max>=idle, all finite, strictly descending table
 * order) + single-source-of-truth mutual exclusion (BAT-D1) + v1 degenerate identity
 * (empty/null table = no tier = v1, DVFS_DIMENSION_DESIGN.md section 2.4). The
 * quality-of-rejection check: a rejection message must name the field (a broad match is
 * distinguishable from "silently swallowed"), not merely the exception class -- the
 * red-to-green discipline of the mirror carbon dimension T series (CarbonInputFuzzTest).
 *
 * <p>Honest disclosure (the measured conclusion of this attack class, not an
 * implementation transcription): <b>a single-element tier table and a null/empty Java
 * table are legal surface</b> -- the design anchors "single tier" as the exact
 * degeneration of the v1 linear law (section 2.4 "a single-tier chain (or an empty one)
 * degenerates semantically to the v1 linear law"); the declared-but-empty rejection of a
 * "declared boundary" happens at the <b>two entries constrained by declared
 * semantics</b>: workbench JSON (empty array) and the evidence manifest (Python auditor
 * BAT-D3 clause); an empty table inside the Java process = v1 degenerate mode is designed
 * behavior (WorkbenchDvfsFuzzTest/BAT-V1 locks the JSON face in the same round).</p>
 */
class FrequencyTierInputFuzzTest {

    // ------------------------------------------------------------------
    // Numeric domain: NaN/+/-Inf per field, injected independently into the three fields
    // (the existing FrequencyTierTest anchors only four points -- mips=0/negative idle/
    // max&lt;idle/NaN mips; this class completes every Inf combination and the remaining
    // fields)
    // ------------------------------------------------------------------

    @Test
    void nanAndInfinityOnEveryFieldAreRejectedWithFieldNaming() {
        // mips field: NaN / +Inf / -Inf (+Inf passes mips>0, so the finite clause must stop it).
        mustCite(() -> new PlatformProfile.FrequencyTier(Double.NaN, 100.0, 200.0), "mips", "NaN mips");
        mustCite(() -> new PlatformProfile.FrequencyTier(Double.POSITIVE_INFINITY, 100.0, 200.0), "mips", "+Inf mips");
        mustCite(() -> new PlatformProfile.FrequencyTier(Double.NEGATIVE_INFINITY, 100.0, 200.0), "mips", "-Inf mips");
        // idle field: NaN / +Inf / -Inf (-Inf is stopped by idle>=0, +Inf by finite).
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, Double.NaN, 200.0), "idle", "NaN idle");
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, Double.POSITIVE_INFINITY, 200.0), "idle", "+Inf idle");
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, Double.NEGATIVE_INFINITY, 200.0), "idle", "-Inf idle");
        // max field: NaN / +Inf / -Inf (-Inf is stopped by max>=idle, +Inf by finite).
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, 100.0, Double.NaN), "max", "NaN max");
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, 100.0, Double.POSITIVE_INFINITY), "max", "+Inf max");
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, 100.0, Double.NEGATIVE_INFINITY), "max", "-Inf max");
        // Finite-clause naming: the message must cite the domain rule (mips>0, idle>=0,
        // max>=idle, all finite).
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
        // mips=0 (zero-speed tier: a meaningless P-state) and negative mips.
        mustCite(() -> new PlatformProfile.FrequencyTier(0.0, 100.0, 200.0), "mips", "zero mips");
        mustCite(() -> new PlatformProfile.FrequencyTier(-1000.0, 100.0, 200.0), "mips", "negative mips");
        // Negative idle power.
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, -0.5, 200.0), "idle", "negative idle");
        // Inverted max<idle.
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, 250.0, 200.0), "max", "reversed max<idle");
        // max==idle (legal boundary: zero dynamic-headroom tier) -- must not be rejected
        // (boundary semantics are covered by the Boundary class).
        assertEquals(
                250.0,
                new PlatformProfile.FrequencyTier(1000.0, 250.0, 250.0).getMaxPowerWatts(),
                0.0,
                "max==idle is the degenerate zero-dynamic tier, legal by contract");
    }

    // ------------------------------------------------------------------
    // Table level: a two-tier table with exactly equal mips (duplicate tier) must be
    // stopped by the "strictly descending" clause -- the existing test anchors only
    // ascending (500,1000); a "duplicate" is the boundary twin of ascending (comparing
    // >= instead of >)
    // ------------------------------------------------------------------

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
        // A duplicate in the middle of a three-tier table (first and last differ; the
        // middle tier has the same mips as the tier before it).

        IllegalArgumentException middle = assertThrows(
                IllegalArgumentException.class,
                () -> spec(Arrays.asList(
                        new PlatformProfile.FrequencyTier(2000.0, 250.0, 400.0),
                        new PlatformProfile.FrequencyTier(1000.0, 220.0, 280.0),
                        new PlatformProfile.FrequencyTier(1000.0, 210.0, 240.0))),
                "middle duplicate must be rejected too");
        assertTrue(middle.getMessage().contains("descending"), "same clause: " + middle.getMessage());
    }

    // ------------------------------------------------------------------
    // Single source of truth: a tier table and flat power together must be rejected, and
    // both faces must be cited (D1 clause)
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // The v1 platform stays legal under every Java constructor input (absent means v1):
    // null table / empty List / 13-arg and 12/11-arg delegation -- each VM has no tier,
    // no frequency-tier serialization, and energy follows the v1 law
    // ------------------------------------------------------------------

    @Test
    void v1TierlessInputsRemainLegalOnEveryConstructorPath() {
        for (PlatformProfile.VmSpec spec : Arrays.asList(
                v1Spec(null),
                v1Spec(new ArrayList<PlatformProfile.FrequencyTier>()),
                // 12-arg/11-arg delegation: absent tiers=null -> v1
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
            // The v1 face has no default tier: the accessor is allowed only on a platform where
            // hasFrequencyTiers() is true.
            // (The contract of defaultFrequencyTier() is "the caller must verify
            // hasFrequencyTiers first".)
        }
        try {
            v1Spec(null).defaultFrequencyTier();
            org.junit.jupiter.api.Assertions.fail(
                    "tierless VmSpec.defaultFrequencyTier() must fail loudly, not fabricate a tier");
        } catch (IndexOutOfBoundsException | IllegalStateException expected) {
            // Explicit failure (never fabricate a tier when none exists); not swallowing a
            // silent value is the contract.
        }
    }

    /**
     * A single-element tier table is legal = v1 degeneration (D1 identity), and the
     * default tier is that only tier.
     */
    @Test
    void singleElementTierListIsTheLegalV1DegenerationNotARejection() {
        PlatformProfile.VmSpec single =
                spec(Collections.singletonList(new PlatformProfile.FrequencyTier(1000.0, 250.0, 400.0)));
        assertTrue(single.hasFrequencyTiers(), "single-tier table IS DVFS mode (evidence face: tierMips recorded)");
        assertEquals(1000.0, single.defaultFrequencyTier().getMips(), 0.0, "the only tier is the default tier");
        assertEquals(0.0, single.getIdlePowerWatts(), 0.0, "flat power fields stay zero (one source of truth)");
    }

    // ---- helpers ----

    /** Tier-table construction face: the flat power fields are constantly 0 (single source of truth). */
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

    /** v1 tierless face: flat power 250/400 (a real power declaration) + null/empty tier table. */
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
        // Broad-domain face: the D1 value message contains the domain list (required: ...),
        // which indexes the fix path.
        assertTrue(
                message.contains("required") || message.contains("descending") || message.contains("source of truth"),
                what + " rejection must state the violated domain rule: " + message);
    }
}
