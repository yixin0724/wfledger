package org.workflowsim.experiments.fgcs.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class AdversarialEvidenceStudyTest {

    private static final String JAVA = "JAVA_EXPERIMENT_ARTIFACT_VALIDATOR";
    private static final String PYTHON = "PYTHON_VERIFY_NETWORK_LEDGER";

    @TempDir
    static Path directory;

    private static JsonObject report;

    @BeforeAll
    static void runStudy() throws Exception {
        report = AdversarialEvidenceStudy.run(directory);
    }

    @Test
    void genuineV2AndV3ArtifactsPassBothIndependentValidators() {
        JsonObject rq1 = report.getAsJsonObject("rq1");
        assertEquals(2, rq1.get("genuineCases").getAsInt());
        assertEquals(4, rq1.get("validatorObservations").getAsInt());
        assertEquals(4, rq1.get("actualAcceptances").getAsInt());
        assertEquals(0, rq1.get("actualRejections").getAsInt());
        for (String id : new String[] {"genuine-v2", "genuine-v3"}) {
            assertOutcome(id, JAVA, "ACCEPT");
            assertOutcome(id, PYTHON, "ACCEPT");
        }
    }

    @Test
    void corruptedArtifactsMatchEveryHandDerivedOutcomeAndReason() {
        JsonObject rq2 = report.getAsJsonObject("rq2");
        assertEquals(9, rq2.get("corruptedCases").getAsInt());
        assertEquals(18, rq2.get("validatorObservations").getAsInt());
        assertEquals(13, rq2.get("expectedRejections").getAsInt());
        assertEquals(13, rq2.get("actualRejections").getAsInt());
        assertEquals(5, rq2.get("expectedAcceptances").getAsInt());
        assertEquals(5, rq2.get("actualAcceptances").getAsInt());
        assertEquals(8, rq2.get("casesRejectedByAtLeastOneValidator").getAsInt());
        assertEquals(0, rq2.get("unexpectedOutcomes").getAsInt());
        assertEquals(0, rq2.get("reasonMismatches").getAsInt());

        for (JsonElement element : report.getAsJsonArray("cases")) {
            JsonObject testCase = element.getAsJsonObject();
            if (!"CORRUPTED".equals(testCase.get("kind").getAsString())) {
                continue;
            }
            assertTrue(
                    testCase.get("allArtifactHashesRecomputed").getAsBoolean(),
                    testCase.get("id").getAsString());
            for (JsonElement item : testCase.getAsJsonArray("observations")) {
                JsonObject observation = item.getAsJsonObject();
                String label = testCase.get("id").getAsString() + "/"
                        + observation.get("validator").getAsString();
                assertEquals(
                        observation.get("expected").getAsString(),
                        observation.get("actual").getAsString(),
                        label);
                assertFalse(observation.get("expectedReason").getAsString().isEmpty(), label);
                assertFalse(observation.get("actualReason").getAsString().isEmpty(), label);
                if ("REJECT".equals(observation.get("expected").getAsString())) {
                    assertTrue(observation.get("reasonMatched").getAsBoolean(), label);
                }
            }
        }
    }

    @Test
    void causalIdentityProvenanceAndDrainRejectionsNameTheViolatedInvariant() {
        assertRejectionReason("v2-parent-before-child-request", JAVA, "Control parent");
        assertRejectionReason("v2-parent-before-child-request", PYTHON, "control-parent");
        assertRejectionReason("v2-task-identity-mismatch", JAVA, "Task timing");
        assertRejectionReason("v2-task-identity-mismatch", PYTHON, "Task result");
        assertRejectionReason("v2-source-provenance-mismatch", JAVA, "provenance");
        assertRejectionReason("v2-source-provenance-mismatch", PYTHON, "provenance");
        assertRejectionReason("v3-source-provenance-mismatch", JAVA, "provenance");
        assertRejectionReason("v3-source-provenance-mismatch", PYTHON, "provenance");
        assertRejectionReason("v3-output-settlement-missing", JAVA, "quiescent");
        assertRejectionReason("v3-output-settlement-missing", PYTHON, "quiescent");
    }

    @Test
    void coverageBoundariesAreMeasuredRatherThanClaimedAsUniformDetection() {
        assertOutcome("v2-reordered-decision-dispatch", JAVA, "ACCEPT");
        assertRejectionReason("v2-reordered-decision-dispatch", PYTHON, "sequence causality");

        assertRejectionReason("v2-missing-simulation-terminal", JAVA, "SIMULATION_TERMINATED");
        assertOutcome("v2-missing-simulation-terminal", PYTHON, "ACCEPT");

        assertRejectionReason("v2-makespan-metric-mismatch", JAVA, "makespanSeconds");
        assertOutcome("v2-makespan-metric-mismatch", PYTHON, "ACCEPT");

        assertOutcome("v2-energy-metric-out-of-scope", JAVA, "ACCEPT");
        assertOutcome("v2-energy-metric-out-of-scope", PYTHON, "ACCEPT");
        assertTrue(report.getAsJsonArray("coverageBoundaries").size() >= 4);
    }

    @Test
    void resultsWriterProducesTheSameMachineReadableEvidence() throws Exception {
        JsonObject disk = JsonParser.parseString(
                        new String(Files.readAllBytes(directory.resolve("results.json")), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("FGCS_ADVERSARIAL_EVIDENCE_V1", disk.get("protocol").getAsString());
        assertEquals(report.getAsJsonObject("rq1"), disk.getAsJsonObject("rq1"));
        assertEquals(report.getAsJsonObject("rq2"), disk.getAsJsonObject("rq2"));
        assertEquals(report.getAsJsonArray("cases"), disk.getAsJsonArray("cases"));
    }

    private static void assertOutcome(String caseId, String validator, String expected) {
        JsonObject observation = observation(caseId, validator);
        assertEquals(expected, observation.get("expected").getAsString(), caseId + " expectation");
        assertEquals(expected, observation.get("actual").getAsString(), caseId + " observation");
    }

    private static void assertRejectionReason(String caseId, String validator, String reasonFragment) {
        assertOutcome(caseId, validator, "REJECT");
        JsonObject observation = observation(caseId, validator);
        assertTrue(
                observation.get("actualReason").getAsString().contains(reasonFragment),
                caseId + " must report the semantic reason: "
                        + observation.get("actualReason").getAsString());
        assertFalse(
                observation.get("actualReason").getAsString().contains("SHA-256 mismatch"),
                caseId + " must not be rejected only because of a stale hash");
    }

    private static JsonObject observation(String caseId, String validator) {
        for (JsonElement element : report.getAsJsonArray("cases")) {
            JsonObject testCase = element.getAsJsonObject();
            if (!caseId.equals(testCase.get("id").getAsString())) {
                continue;
            }
            JsonArray observations = testCase.getAsJsonArray("observations");
            for (JsonElement item : observations) {
                JsonObject observation = item.getAsJsonObject();
                if (validator.equals(observation.get("validator").getAsString())) {
                    return observation;
                }
            }
        }
        throw new AssertionError("Missing observation " + caseId + "/" + validator);
    }
}
