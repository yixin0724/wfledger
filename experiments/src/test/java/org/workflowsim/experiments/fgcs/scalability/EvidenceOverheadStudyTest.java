package org.workflowsim.experiments.fgcs.scalability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiments.fgcs.FgcDatasets;
import org.workflowsim.experiments.fgcs.FgcStudySupport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class EvidenceOverheadStudyTest {

    @TempDir
    Path directory;

    /**
     * R-round2 regression: OFF must export the same bundle roles with an empty event stream. Serialized
     * validation, in-memory auditing, and serialization are distinct measured operations; pool peaks are not
     * presented as a simultaneous heap measurement. Event-dependent OFF metrics are unavailable, not zero.
     */
    @Test
    void allModesWriteBundlesAndSeparateSerializedValidationCosts() throws Exception {
        List<FgcStudySupport.Measurement> results = EvidenceOverheadStudy.run(directory, 1);
        assertEquals(9, results.size());
        Map<String, Map<String, JsonObject>> trials = new HashMap<String, Map<String, JsonObject>>();
        for (FgcStudySupport.Measurement result : results) {
            assertTrue(result.getReport().isWorkflowCompletedSuccessfully(), result.getLabel());
            assertEquals(
                    result.getReport().getMetrics().getLogicalTaskCount(),
                    result.getReport().getMetrics().getSuccessfullyCompletedLogicalTaskCount());
            assertEquals(0.0, result.getAudit().getEnergyResidualJoules(), 1.0e-5);
            assertEquals(0.0, result.getAudit().getCostResidual(), 1.0e-6);
            assertTrue(result.getSimulationNanos() > 0L);
            assertTrue(result.getAuditNanos() > 0L);
            JsonObject json = result.toJson();
            assertTrue(json.get("serializationAndSizeAccountingNanos").getAsLong() > 0L);
            assertEquals(
                    json.get("artifactWriteNanos").getAsLong(),
                    json.get("serializationAndSizeAccountingNanos").getAsLong());
            assertTrue(json.get("artifactBytes").getAsLong() > 0L, result.getLabel());
            assertTrue(json.get("manifestBytes").getAsLong() > 0L, result.getLabel());
            assertTrue(json.get("metricsBytes").getAsLong() > 0L, result.getLabel());
            assertTrue(json.get("javaValidationNanos").getAsLong() > 0L, result.getLabel());
            assertEquals("VALID", json.get("javaValidationStatus").getAsString());
            assertTrue(json.get("sampledTotalHeapPeakBytes").getAsLong() > 0L);
            assertTrue(json.get("heapSampleCount").getAsLong() > 0L);
            assertTrue(json.get("heapPoolPeakSumBytes").getAsLong() > 0L);
            assertFalse(json.has("peakHeapBytes"));
            assertTrue(json.get("threadAllocatedBytes").getAsLong() > 0L);

            String mode = json.get("mode").getAsString();
            if ("off".equals(mode)) {
                assertTrue(result.getReport().getEvents().isEmpty());
                assertEquals(0L, json.get("eventStreamBytes").getAsLong());
                assertFalse(json.get("eventRecordingEnabled").getAsBoolean());
                assertTrue(json.get("meanWaitingSeconds").isJsonNull());
                assertTrue(json.get("meanTrueSlowdown").isJsonNull());
                assertFalse(json.get("meanWaitingSecondsAvailable").getAsBoolean());
                assertFalse(json.get("meanTrueSlowdownAvailable").getAsBoolean());
                assertEquals("DISABLED", json.get("pythonValidationStatus").getAsString());
                assertTrue(json.get("pythonValidationNanos").getAsLong() > 0L);
            } else {
                assertTrue(result.getReport().getEvents().size() > 0);
                assertTrue(json.get("eventStreamBytes").getAsLong() > 0L);
                assertTrue(json.get("eventRecordingEnabled").getAsBoolean());
                assertFalse(json.get("meanWaitingSeconds").isJsonNull());
                assertFalse(json.get("meanTrueSlowdown").isJsonNull());
                if ("events".equals(mode)) {
                    assertTrue(json.get("pythonValidationNanos").isJsonNull());
                    assertEquals("NA", json.get("pythonValidationStatus").getAsString());
                    assertFalse(json.get("pythonValidationApplicable").getAsBoolean());
                    assertEquals(
                            "NOT_APPLICABLE_EVENT_ONLY_BUNDLE_WITHOUT_LIFECYCLE_CAPTURE",
                            json.get("pythonValidationScope").getAsString());
                    assertEquals(0L, json.get("lifecycleEvidenceBytes").getAsLong());
                } else {
                    assertEquals(
                            "VALID_COMPLETE", json.get("pythonValidationStatus").getAsString());
                    assertTrue(json.get("pythonValidationNanos").getAsLong() > 0L);
                    assertTrue(json.get("lifecycleEvidenceBytes").getAsLong() > 0L);
                }
            }
            assertEquals("lifecycle".equals(mode), result.getReport().getStorageLifecycleEvidence() != null);
            assertEquals(
                    org.workflowsim.data.DataMovementModel.Kind.COHERENT_STORAGE_DATAFLOW_V3,
                    result.getReport().getConfig().getDataMovementModel().getKind());
            assertEquals(
                    org.workflowsim.utils.Parameters.PlanningAlgorithm.RANDOM,
                    result.getReport().getConfig().getPlanningAlgorithm());
            assertEquals(
                    org.workflowsim.utils.ReplicaCatalog.FileSystem.LOCAL,
                    result.getReport().getConfig().getFileSystem());

            String key = json.get("workflow").getAsString() + "-r"
                    + json.get("repetition").getAsInt();
            trials.computeIfAbsent(key, ignored -> new HashMap<String, JsonObject>())
                    .put(mode, json);
        }
        for (Map<String, JsonObject> trial : trials.values()) {
            assertEquals(trial.keySet(), new java.util.HashSet<String>(EvidenceOverheadStudy.modes()));
            JsonObject off = trial.get("off");
            JsonObject events = trial.get("events");
            JsonObject lifecycle = trial.get("lifecycle");
            for (String field : new String[] {"meanWaitingSeconds", "meanTrueSlowdown"}) {
                assertTrue(off.get(field).isJsonNull());
                assertEquals(
                        events.get(field).getAsDouble(), lifecycle.get(field).getAsDouble(), 0.0, field);
            }
            for (String field : new String[] {
                "normalizedInput", "dataMovementModel", "planningAlgorithm", "schedulingAlgorithm", "fileSystem",
                "randomSeed", "vmCount", "eventRecorderCap", "platformProfile", "makespanSeconds"
            }) {
                assertEquals(off.get(field), events.get(field), field);
                assertEquals(off.get(field), lifecycle.get(field), field);
            }
        }
    }

    /** The Latin-square rotation balances every mode position over three workflows and five repetitions. */
    @Test
    void modeOrderIsDeterministicAndGloballyBalanced() {
        assertEquals(
                java.util.Arrays.asList("off", "events", "lifecycle"),
                EvidenceOverheadStudy.modeOrder(FgcDatasets.MONTAGE_50, 0));
        assertEquals(
                java.util.Arrays.asList("events", "lifecycle", "off"),
                EvidenceOverheadStudy.modeOrder(FgcDatasets.MONTAGE_50, 1));
        assertEquals(
                java.util.Arrays.asList("lifecycle", "off", "events"),
                EvidenceOverheadStudy.modeOrder(FgcDatasets.MONTAGE_50, 2));
        Map<String, int[]> positions = new HashMap<String, int[]>();
        for (String mode : EvidenceOverheadStudy.modes()) {
            positions.put(mode, new int[3]);
        }
        for (String workflow : EvidenceOverheadStudy.workflows()) {
            for (int repetition = 0; repetition < 5; repetition++) {
                List<String> order = EvidenceOverheadStudy.modeOrder(workflow, repetition);
                assertEquals(3, new java.util.HashSet<String>(order).size());
                for (int position = 0; position < order.size(); position++) {
                    positions.get(order.get(position))[position]++;
                }
            }
        }
        for (int[] counts : positions.values()) {
            assertEquals(5, counts[0]);
            assertEquals(5, counts[1]);
            assertEquals(5, counts[2]);
        }
    }

    @Test
    void independentMainEntryRunsOneUnreportedWarmupAndOneMeasuredScenario() throws Exception {
        for (String mode : EvidenceOverheadStudy.modes()) {
            Path output = directory.resolve("main-" + mode + ".json");
            EvidenceOverheadStudy.main(new String[] {
                output.toString(),
                FgcDatasets.MONTAGE_50,
                mode,
                "0",
                directory.resolve("main-evidence-" + mode).toString()
            });
            JsonArray rows = JsonParser.parseString(new String(
                            java.nio.file.Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonArray();
            assertEquals(1, rows.size());
            JsonObject row = rows.get(0).getAsJsonObject();
            assertTrue(row.get("completed").getAsBoolean());
            assertTrue(row.get("warmupCompleted").getAsBoolean());
            assertEquals(EvidenceOverheadStudy.PROTOCOL, row.get("protocol").getAsString());
            assertEquals(0, row.get("repetition").getAsInt());
            assertEquals(
                    mode,
                    row.get("modeOrder")
                            .getAsJsonArray()
                            .get(row.get("modeOrderIndex").getAsInt())
                            .getAsString());
        }
    }

    @Test
    void inProcessBatchIsExplicitlyNotTheFreshJvmPerformanceProtocol() throws Exception {
        Path output = directory.resolve("functional-results.json");
        EvidenceOverheadStudy.main(new String[] {output.toString(), "1"});
        JsonArray rows = JsonParser.parseString(
                        new String(java.nio.file.Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonArray();
        assertEquals(9, rows.size());
        for (JsonElement row : rows) {
            assertFalse(row.getAsJsonObject().get("warmupCompleted").getAsBoolean());
            assertEquals(
                    EvidenceOverheadStudy.FUNCTIONAL_PROTOCOL,
                    row.getAsJsonObject().get("protocol").getAsString());
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> EvidenceOverheadStudy.run(FgcDatasets.MONTAGE_50, "invalid", 0, directory));
    }

    @Test
    void normalizedInputPreservesSmallDagStructureAndRemovesDeclarationConflicts() throws Exception {
        Path input = FgcDatasets.smallDag(directory, "fan");
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        org.w3c.dom.Document document = factory.newDocumentBuilder().parse(input.toFile());
        org.w3c.dom.NodeList jobs = document.getElementsByTagName("job");
        org.w3c.dom.NodeList children = document.getElementsByTagName("child");
        assertEquals(4, jobs.getLength());
        assertEquals(3, children.getLength());
        Map<String, String> inputSizes = new HashMap<String, String>();
        org.w3c.dom.NodeList uses = document.getElementsByTagName("uses");
        for (int index = 0; index < uses.getLength(); index++) {
            org.w3c.dom.Element use = (org.w3c.dom.Element) uses.item(index);
            if ("input".equals(use.getAttribute("link"))) {
                String previous = inputSizes.put(use.getAttribute("name"), use.getAttribute("size"));
                assertTrue(previous == null || previous.equals(use.getAttribute("size")));
            }
        }
    }
}
