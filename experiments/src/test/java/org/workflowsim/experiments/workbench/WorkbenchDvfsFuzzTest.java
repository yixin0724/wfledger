package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.configuration;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.json;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.workflow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * BAT-V1 (2026-10-29 adversarial test-engineer round, DVFS fifth round): malicious
 * fuzzing of the workbench JSON frequency-tier input surface -- declared-but-empty,
 * "non-array" shapes, string tiers, the unknown policy label (SPEED_RUNNER), and the NaN
 * constant of strict JSON; the v1 platform stays legal under every input. Quality of
 * rejection: the message must name the field (mirroring the discipline of the existing
 * BAT-D4 rejection surface).
 *
 * <p>Honest disclosure (measured semantics pinned by this class, not transcribed):
 * <ul>
 *   <li><b>empty array (declared-but-empty) -> rejected</b> (BAT-D4: "must be a non-empty
 *       array", which gives the shortest repair path: "delete the key, i.e. v1");</li>
 *   <li><b>single-element tier table -> legal</b> (the workbench face of the v1
 *       degenerate identity; MIN_POWER is usable);</li>
 *   <li><b>the NaN/Inf family dies at the strict syntax layer on the JSON face</b>
 *       (an RFC 8259 illegal constant, before any domain validation -- the same
 *       semantics as the Python auditor's _constant clause); there is no "field-level"
 *       receipt; the domain-level finite message ("must be finite") is reserved for the
 *       domain layer (large but finite out-of-domain values still get the domain-layer
 *       message).</li>
 * </ul>
 * (The positive/negative/inverted frequency-tier numeric domain and the table-order
 * message surface are already pinned by WorkbenchConfigTest.
 * platformFrequencyTiersRoundTripAndValidate; this class does not repeat them.)</p>
 */
class WorkbenchDvfsFuzzTest {

    @TempDir
    Path directory;

    private boolean loggingWasDisabled;

    @BeforeEach
    void silenceSimulationLogging() {
        loggingWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restoreSimulationLogging() {
        Log.setDisabled(loggingWasDisabled);
    }

    @Test
    void declaredButEmptyTierArrayIsRejectedCitingTheV1EscapeHatch() throws Exception {
        Path workflow = workflow(directory, "flow.dax", false);
        JsonObject empty = configuration(workflow.toString(), 2);
        empty.getAsJsonObject("platform").add("frequencyTiers", new JsonArray());
        IllegalArgumentException rejection = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("empty-tiers.json"), empty)));
        assertTrue(
                rejection.getMessage().contains("frequencyTiers")
                        && rejection.getMessage().contains("non-empty"),
                "declared-but-empty must be rejected citing the field and the v1 escape hatch: "
                        + rejection.getMessage());
    }

    @Test
    void nonArrayTiersShapeIsRejectedCitingTheField() throws Exception {
        Path workflow = workflow(directory, "flow.dax", false);
        JsonObject notArray = configuration(workflow.toString(), 2);
        notArray.getAsJsonObject("platform").add("frequencyTiers", new JsonPrimitive("not-an-array"));
        IllegalArgumentException rejection = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("string-tiers.json"), notArray)));
        assertTrue(
                rejection.getMessage().contains("frequencyTiers")
                        && rejection.getMessage().contains("array"),
                "string-shaped tiers must be rejected citing the field: " + rejection.getMessage());
    }

    @Test
    void nonObjectTierEntriesAndUnknownTierFieldsAreRejected() throws Exception {
        Path workflow = workflow(directory, "flow.dax", false);
        JsonObject stringEntry = configuration(workflow.toString(), 2);
        JsonArray entries = new JsonArray();
        entries.add(new JsonPrimitive("not-an-object"));
        stringEntry.getAsJsonObject("platform").add("frequencyTiers", entries);
        IllegalArgumentException shape = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("entry-shape.json"), stringEntry)));
        assertTrue(
                shape.getMessage().contains("frequencyTiers")
                        && shape.getMessage().contains("objects"),
                "entry-shape rejection must cite the field: " + shape.getMessage());

        JsonObject extraField = configuration(workflow.toString(), 2);
        extraField.getAsJsonObject("platform").add("frequencyTiers", tiers(2000.0, 250.0, 400.0));
        extraField
                .getAsJsonObject("platform")
                .get("frequencyTiers")
                .getAsJsonArray()
                .get(0)
                .getAsJsonObject()
                .addProperty("turboBoost", 1.5);
        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("extra-field.json"), extraField)));
        assertTrue(
                unknown.getMessage().contains("unknown field")
                        && unknown.getMessage().contains("turboBoost"),
                "extra tier field must be rejected citing it (closed schema): " + unknown.getMessage());
    }

    @Test
    void unknownPolicyLabelSPEED_RUNNERIsRejectedEvenOnTierlessPlatforms() throws Exception {
        Path workflow = workflow(directory, "flow.dax", false);
        // Tiers present + unknown label -> rejected, citing frequencyPolicy and the legal label set.
        JsonObject tiered = configuration(workflow.toString(), 2);
        tiered.getAsJsonObject("platform").add("frequencyTiers", tiers(2000.0, 250.0, 400.0));
        tiered.getAsJsonObject("platform").addProperty("frequencyPolicy", "SPEED_RUNNER");
        IllegalArgumentException tieredRejection = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("unknown-tiered.json"), tiered)));
        assertTrue(
                tieredRejection.getMessage().contains("frequencyPolicy")
                        && tieredRejection.getMessage().contains("SPEED_RUNNER"),
                "unknown policy must cite the field and the given label: " + tieredRejection.getMessage());
        // No tiers + unknown label -> rejected the same way (no silent fallback to DEFAULT):
        // field-level ordering (valueOf precedes the policy x table composite check).
        JsonObject tierless = configuration(workflow.toString(), 2);
        tierless.getAsJsonObject("platform").addProperty("frequencyPolicy", "SPEED_RUNNER");
        IllegalArgumentException tierlessRejection = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("unknown-tierless.json"), tierless)));
        assertTrue(
                tierlessRejection.getMessage().contains("frequencyPolicy"),
                "unknown label is rejected even before platform-policy composition: " + tierlessRejection.getMessage());
    }

    @Test
    void rawNanTierValueDiesAtTheStrictJsonSyntaxLayer() throws Exception {
        Path workflow = workflow(directory, "flow.dax", false);
        JsonObject nanTier = configuration(workflow.toString(), 2);
        // NaN cannot be constructed as a legal JSON constant (Gson JsonPrimitive likewise
        // rejects the JSON serialization semantics of non-finite doubles) -- the raw document
        // is hand-written so the illegal constant is fed straight into the strict reader.
        Path raw = directory.resolve("nan-raw.json");
        String template = json(directory.resolve("nan-template.json"), nanTier).toString();
        String mutated = template.replaceFirst(
                "\"platform\"\\s*:\\s*\\{",
                "\"platform\": {\"frequencyTiers\": [{\"mips\": NaN,\"idlePowerWatts\": 1,\"maxPowerWatts\": 2}],");
        Files.write(raw, mutated.getBytes(StandardCharsets.UTF_8));
        // The strict reader maps a non-standard constant to IllegalArgumentException ("the
        // configuration must be a strict JSON object", BAM-D4/Wso strict face) -- the
        // syntax-layer kill happens before any domain validation (the same semantics as the
        // Python auditor's _study_audit._constant clause); there is no field-level receipt
        // and no silent fallback to DEFAULT.
        IllegalArgumentException syntax = assertThrows(
                IllegalArgumentException.class, () -> WorkbenchConfig.read(raw), "NaN literal must not parse");
        assertTrue(
                syntax.getMessage() != null && !syntax.getMessage().isEmpty(),
                "syntax-layer rejection must carry a diagnostic: " + syntax.getMessage());
        // Large but finite out-of-domain value (negative mips) -> domain-layer field message (no fallback).
        JsonObject negative = configuration(workflow.toString(), 2);
        negative.getAsJsonObject("platform").add("frequencyTiers", tiers(-5.0, 1.0, 2.0));
        IllegalArgumentException domain = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("negative.json"), negative)));
        assertTrue(domain.getMessage().contains("mips"), "domain rejection must cite mips: " + domain.getMessage());
    }

    @Test
    void singleElementTierTableIsLegalV1DegenerationOnTheWorkbenchFace() throws Exception {
        Path workflow = workflow(directory, "flow.dax", false);
        JsonObject single = configuration(workflow.toString(), 2);
        single.getAsJsonObject("platform").add("frequencyTiers", tiers(1000.0, 250.0, 400.0));
        single.getAsJsonObject("platform").addProperty("frequencyPolicy", "MIN_POWER");
        WorkbenchConfig loaded = WorkbenchConfig.read(json(directory.resolve("single-tier.json"), single));
        for (PlatformProfile.VmSpec spec : loaded.getPlatform().getVms()) {
            assertTrue(spec.hasFrequencyTiers(), "single-tier declaration IS DVFS mode");
            assertEquals(1, spec.getFrequencyTiers().size(), "one tier");
            assertEquals(1000.0, spec.defaultFrequencyTier().getMips(), 0.0, "the default tier");
        }
        for (WorkbenchConfig.Candidate candidate : loaded.getCandidates()) {
            assertEquals(
                    SimulationConfig.FrequencyPolicy.MIN_POWER,
                    candidate.config.getFrequencyPolicy(),
                    "MIN_POWER is legal on a single-tier platform (it picks the only tier)");
        }
    }

    @Test
    void v1ConfigurationWithoutAnyTierKeysStaysLegalWithDefaultPolicy() throws Exception {
        Path workflow = workflow(directory, "flow.dax", false);
        WorkbenchConfig loaded =
                WorkbenchConfig.read(json(directory.resolve("v1.json"), configuration(workflow.toString(), 2)));
        for (PlatformProfile.VmSpec spec : loaded.getPlatform().getVms()) {
            assertFalse(spec.hasFrequencyTiers(), "no frequencyTiers key -> v1 single-tier platform");
            assertFalse(spec.hasPowerModel(), "usable unpowered v1 shape (energy face absent)");
        }
        for (WorkbenchConfig.Candidate candidate : loaded.getCandidates()) {
            assertEquals(
                    SimulationConfig.FrequencyPolicy.DEFAULT,
                    candidate.config.getFrequencyPolicy(),
                    "absent policy resolution is DEFAULT (v1)");
        }
    }

    private static JsonArray tiers(double... mipsIdleMax) {
        JsonArray table = new JsonArray();
        for (int i = 0; i < mipsIdleMax.length; i += 3) {
            JsonObject tier = new JsonObject();
            tier.addProperty("mips", mipsIdleMax[i]);
            tier.addProperty("idlePowerWatts", mipsIdleMax[i + 1]);
            tier.addProperty("maxPowerWatts", mipsIdleMax[i + 2]);
            table.add(tier);
        }
        return table;
    }
}
