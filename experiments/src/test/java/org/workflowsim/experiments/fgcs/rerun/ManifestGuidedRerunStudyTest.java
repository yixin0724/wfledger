package org.workflowsim.experiments.fgcs.rerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiments.rerun.RerunDiffExecutor;
import org.workflowsim.experiments.rerun.RerunVerdict;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class ManifestGuidedRerunStudyTest {
    @TempDir
    static Path directory;

    private static Path output;
    private static JsonObject report;

    @BeforeAll
    static void runRealBundleProtocol() throws Exception {
        output = directory.resolve("protocol");
        report = ManifestGuidedRerunStudy.run(output);
    }

    @Test
    void genuineV2AndV3ControlsReconstructAndResimulateIdentically() {
        assertCase("genuine-v2-control", RerunVerdict.IDENTICAL_CORE);
        assertCase("genuine-v3-control", RerunVerdict.IDENTICAL_CORE);
        assertEquals(2, report.getAsJsonObject("rq1").get("genuineControlCases").getAsInt());
        assertEquals(
                2,
                report.getAsJsonObject("rq1")
                        .get("genuineControlsIdenticalCore")
                        .getAsInt());
        for (String id : new String[] {"genuine-v2-control", "genuine-v3-control"}) {
            JsonObject testCase = testCase(id);
            assertEquals(0, testCase.getAsJsonArray("coreDivergences").size(), id);
            assertEquals(
                    2,
                    testCase.getAsJsonArray("inputResolution")
                            .get(0)
                            .getAsJsonObject()
                            .get("tier")
                            .getAsInt(),
                    id);
            assertTrue(testCase.getAsJsonObject("evidenceHashes").get("rerun").isJsonObject(), id);
        }
    }

    @Test
    void identityVerifiedRelocationOnlyNotesTheThreeWhitelistedPaths() {
        JsonObject testCase = assertCase("relocated-v2-input-path", RerunVerdict.IDENTICAL_CORE);
        JsonObject input = testCase.getAsJsonArray("inputResolution").get(0).getAsJsonObject();
        assertEquals(1, input.get("tier").getAsInt());
        assertTrue(input.get("verified").getAsBoolean());
        assertPointer(testCase.getAsJsonArray("volatileFieldsNoted"), "/configuration/workflowPaths/0");
        assertPointer(testCase.getAsJsonArray("volatileFieldsNoted"), "/inputs/0/path");
        assertPointer(testCase.getAsJsonArray("volatileFieldsNoted"), "/result/workflowOutcomes/0/path");
        assertEquals(0, testCase.getAsJsonArray("coreDivergences").size());
    }

    @Test
    void rootRuntimeAndProvenanceChangesAreVolatileButV3SidecarsRemainCore() {
        JsonObject testCase = assertCase("volatile-v3-manifest-metadata", RerunVerdict.IDENTICAL_CORE);
        assertPointer(testCase.getAsJsonArray("volatileFieldsNoted"), "/runtime/osName");
        assertPointer(testCase.getAsJsonArray("volatileFieldsNoted"), "/provenance/core/codeSource");
        assertEquals(0, testCase.getAsJsonArray("coreDivergences").size());
        assertTrue(testCase.get("applicableScope").getAsString().contains("FILE_STORAGE_LIFECYCLE_V3"));
    }

    @Test
    void handDerivedRuntimeScaleChangeProducesSemanticCoreDivergence() {
        JsonObject testCase = assertCase("semantic-v2-runtime-scale", RerunVerdict.DIVERGED);
        assertPointer(testCase.getAsJsonArray("coreDivergences"), "/result/tasks/0/effectiveExecutionLengthMi");
        assertTrue(testCase.getAsJsonArray("coreDivergences").size() > 0);
        assertTrue(testCase.getAsJsonObject("evidenceHashes").get("rerun").isJsonObject());
    }

    @Test
    void invalidEvidenceInputFailuresAndUnsupportedConfigKeepDistinctVerdicts() {
        JsonObject invalid = assertCase("evidence-invalid-v2-makespan", RerunVerdict.EVIDENCE_INVALID);
        assertTrue(invalid.get("failureReason").getAsString().contains("makespanSeconds"));
        assertTrue(
                invalid.getAsJsonObject("evidenceHashes")
                        .getAsJsonObject("original")
                        .get("allArtifactIntegrityDeclarationsMatch")
                        .getAsBoolean(),
                "The semantic invalidity must remain after complete integrity repair");

        JsonObject mismatch = assertCase("input-v2-hash-mismatch", RerunVerdict.INPUT_HASH_MISMATCH);
        assertTrue(mismatch.get("failureReason").getAsString().contains("sha256/sizeBytes mismatch"));
        assertDetail(mismatch, "expected sha256=");
        assertDetail(mismatch, "actual   sha256=");
        JsonObject hashes = mismatch.getAsJsonObject("evidenceHashes");
        String declared = hashes.getAsJsonObject("original")
                .getAsJsonArray("declaredInputIdentities")
                .get(0)
                .getAsJsonObject()
                .get("declaredSha256")
                .getAsString();
        JsonObject candidate = hashes.getAsJsonObject("mutatedInputCandidate");
        assertNotEquals(declared, candidate.get("sha256").getAsString());
        assertEquals(
                hashes.getAsJsonObject("original")
                        .getAsJsonArray("declaredInputIdentities")
                        .get(0)
                        .getAsJsonObject()
                        .get("declaredSizeBytes")
                        .getAsLong(),
                candidate.get("sizeBytes").getAsLong());

        JsonObject unresolved = assertCase("input-v3-unresolved", RerunVerdict.INPUT_UNRESOLVED);
        assertTrue(unresolved.get("failureReason").getAsString().contains("Input could not be located"));
        assertDetail(unresolved, "tier2:");

        JsonObject rejected =
                assertCase("reconstruction-v3-unsupported-vm-scheduler", RerunVerdict.RECONSTRUCTION_REJECTED);
        assertTrue(rejected.get("failureReason").getAsString().contains("NOT_A_SCHEDULER_MODE"));
    }

    @Test
    void classifiedFailuresDoNotEmitASuccessfulRerunBundle() {
        for (String id : new String[] {
            "evidence-invalid-v2-makespan",
            "input-v2-hash-mismatch",
            "input-v3-unresolved",
            "reconstruction-v3-unsupported-vm-scheduler"
        }) {
            JsonObject testCase = testCase(id);
            assertTrue(testCase.getAsJsonObject("evidenceHashes").get("rerun").isJsonNull(), id);
            Path rerunManifest = output.resolve("executor").resolve(id).resolve("rerun/result.manifest.json");
            assertFalse(Files.exists(rerunManifest), id);
        }
    }

    @Test
    void machineReadableResultsRetainHashesScopesAndEveryExpectation() throws Exception {
        assertEquals(
                ManifestGuidedRerunStudy.RESULTS_SCHEMA, report.get("schema").getAsString());
        assertEquals(ManifestGuidedRerunStudy.PROTOCOL, report.get("protocol").getAsString());
        assertEquals(RerunDiffExecutor.class.getName(), report.get("executor").getAsString());
        assertEquals(9, report.getAsJsonArray("cases").size());
        assertEquals(9, report.getAsJsonObject("summary").get("caseCount").getAsInt());
        assertEquals(
                0, report.getAsJsonObject("summary").get("unexpectedOutcomes").getAsInt());
        assertEquals(
                0, report.getAsJsonObject("summary").get("verdictMismatches").getAsInt());
        assertEquals(
                0, report.getAsJsonObject("summary").get("diagnosticMismatches").getAsInt());
        for (JsonElement element : report.getAsJsonArray("cases")) {
            JsonObject testCase = element.getAsJsonObject();
            String id = testCase.get("caseId").getAsString();
            assertTrue(testCase.get("expectationMet").getAsBoolean(), id + ": " + testCase);
            assertTrue(testCase.get("originalEvidenceUnchanged").getAsBoolean(), id);
            assertFalse(testCase.get("contractRationale").getAsString().isEmpty(), id);
            assertFalse(testCase.get("applicableScope").getAsString().isEmpty(), id);
            assertEquals(
                    testCase.get("expectedVerdict").getAsString(),
                    testCase.get("actualVerdict").getAsString(),
                    id);
            JsonObject original = testCase.getAsJsonObject("evidenceHashes").getAsJsonObject("original");
            assertHash(original.getAsJsonObject("manifest").get("sha256").getAsString(), id);
            for (JsonElement artifactElement : original.getAsJsonArray("artifacts")) {
                JsonObject artifact = artifactElement.getAsJsonObject();
                assertHash(artifact.get("sha256").getAsString(), id);
                assertHash(artifact.get("declaredSha256").getAsString(), id);
                assertTrue(artifact.get("integrityDeclarationMatches").getAsBoolean(), id);
            }
        }
        JsonObject disk = JsonParser.parseString(new String(
                        Files.readAllBytes(output.resolve(ManifestGuidedRerunStudy.RESULTS_NAME)),
                        StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(report, disk);
    }

    private static JsonObject assertCase(String id, RerunVerdict expected) {
        JsonObject testCase = testCase(id);
        assertEquals(expected.name(), testCase.get("expectedVerdict").getAsString(), id + " protocol expectation");
        assertEquals(expected.name(), testCase.get("actualVerdict").getAsString(), id + ": " + testCase);
        assertTrue(testCase.get("expectationMet").getAsBoolean(), id + ": " + testCase);
        return testCase;
    }

    private static JsonObject testCase(String id) {
        for (JsonElement element : report.getAsJsonArray("cases")) {
            JsonObject testCase = element.getAsJsonObject();
            if (id.equals(testCase.get("caseId").getAsString())) {
                return testCase;
            }
        }
        throw new AssertionError("Missing manifest-guided rerun case " + id);
    }

    private static void assertPointer(JsonArray entries, String pointer) {
        for (JsonElement element : entries) {
            if (pointer.equals(element.getAsJsonObject().get("pointer").getAsString())) {
                return;
            }
        }
        throw new AssertionError("Missing required pointer " + pointer + " in " + entries);
    }

    private static void assertDetail(JsonObject testCase, String fragment) {
        for (JsonElement element : testCase.getAsJsonArray("failureDetails")) {
            if (element.getAsString().contains(fragment)) {
                return;
            }
        }
        throw new AssertionError("Missing failure detail " + fragment + " in " + testCase);
    }

    private static void assertHash(String value, String subject) {
        assertTrue(value.matches("[0-9a-f]{64}"), subject + " has invalid SHA-256: " + value);
    }
}
