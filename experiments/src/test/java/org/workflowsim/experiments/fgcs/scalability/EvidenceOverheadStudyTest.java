package org.workflowsim.experiments.fgcs.scalability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiments.fgcs.FgcDatasets;
import org.workflowsim.experiments.fgcs.FgcStudySupport;

class EvidenceOverheadStudyTest {

    @TempDir
    Path directory;

    @Test
    void evidenceOffEventsAndLifecycleRunsMeasureSeparatedCosts() throws Exception {
        List<FgcStudySupport.Measurement> results = EvidenceOverheadStudy.run(directory, 2);
        assertEquals(18, results.size());
        for (FgcStudySupport.Measurement result : results) {
            assertTrue(result.getReport().isWorkflowCompletedSuccessfully(), result.getLabel());
            assertEquals(
                    result.getReport().getMetrics().getLogicalTaskCount(),
                    result.getReport().getMetrics().getSuccessfullyCompletedLogicalTaskCount());
            assertEquals(0.0, result.getAudit().getEnergyResidualJoules(), 1.0e-5);
            assertEquals(0.0, result.getAudit().getCostResidual(), 1.0e-6);
            assertTrue(result.getSimulationNanos() > 0L);
            assertTrue(result.getAuditNanos() > 0L);
            if (result.getLabel().contains("-off-")) {
                assertTrue(result.getReport().getEvents().isEmpty());
                assertEquals(0L, result.getArtifactBytes());
            } else {
                assertTrue(result.getReport().getEvents().size() > 0);
                assertTrue(result.getArtifactWriteNanos() > 0L);
                assertTrue(result.getArtifactBytes() > 0L);
            }
            if (result.getLabel().contains("-lifecycle-")) {
                assertTrue(result.getReport().getStorageLifecycleEvidence() != null);
            } else {
                assertTrue(result.getReport().getStorageLifecycleEvidence() == null);
            }
            assertEquals(
                    org.workflowsim.data.DataMovementModel.Kind.COHERENT_STORAGE_DATAFLOW_V3,
                    result.getReport().getConfig().getDataMovementModel().getKind());
            assertEquals(
                    org.workflowsim.utils.Parameters.PlanningAlgorithm.RANDOM,
                    result.getReport().getConfig().getPlanningAlgorithm());
            assertEquals(
                    org.workflowsim.utils.ReplicaCatalog.FileSystem.LOCAL,
                    result.getReport().getConfig().getFileSystem());
            assertTrue(result.toJson().get("peakHeapBytes").getAsLong() > 0L);
            assertTrue(result.toJson().get("threadAllocatedBytes").getAsLong() > 0L);
        }
    }

    @Test
    void independentMainEntryRunsWarmupAndOneMeasuredScenario() throws Exception {
        for (String mode : new String[] {"off", "events", "lifecycle"}) {
            Path output = directory.resolve("main-" + mode + ".json");
            EvidenceOverheadStudy.main(new String[] {
                output.toString(),
                FgcDatasets.MONTAGE_50,
                mode,
                "0",
                directory.resolve("main-evidence-" + mode).toString()
            });
            com.google.gson.JsonArray rows = com.google.gson.JsonParser.parseString(new String(
                            java.nio.file.Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonArray();
            assertEquals(1, rows.size());
            assertTrue(rows.get(0).getAsJsonObject().get("completed").getAsBoolean());
        }
    }

    @Test
    void defaultMainRunsDeclaredMatrixAndWritesAllModes() throws Exception {
        Path output = directory.resolve("default-main.json");
        EvidenceOverheadStudy.main(new String[] {output.toString(), "1"});
        com.google.gson.JsonArray rows = com.google.gson.JsonParser.parseString(
                        new String(java.nio.file.Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonArray();
        assertEquals(9, rows.size());
        for (com.google.gson.JsonElement row : rows) {
            assertTrue(row.getAsJsonObject().get("completed").getAsBoolean());
        }
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
        java.util.Map<String, String> inputSizes = new java.util.HashMap<String, String>();
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
