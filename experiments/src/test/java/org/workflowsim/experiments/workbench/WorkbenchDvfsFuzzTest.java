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

        Path raw = directory.resolve("nan-raw.json");
        String template = json(directory.resolve("nan-template.json"), nanTier).toString();
        String mutated = template.replaceFirst(
                "\"platform\"\\s*:\\s*\\{",
                "\"platform\": {\"frequencyTiers\": [{\"mips\": NaN,\"idlePowerWatts\": 1,\"maxPowerWatts\": 2}],");
        Files.write(raw, mutated.getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException syntax = assertThrows(
                IllegalArgumentException.class, () -> WorkbenchConfig.read(raw), "NaN literal must not parse");
        assertTrue(
                syntax.getMessage() != null && !syntax.getMessage().isEmpty(),
                "syntax-layer rejection must carry a diagnostic: " + syntax.getMessage());

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
