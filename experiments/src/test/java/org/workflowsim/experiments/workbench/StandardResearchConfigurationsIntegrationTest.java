package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.utils.Parameters;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** End-to-end acceptance of the declarative research configurations. */
class StandardResearchConfigurationsIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void standardStudiesExposeExpectedPerWorkflowRunCounts() throws Exception {
        Map<String, Integer> expected = new LinkedHashMap<String, Integer>();
        expected.put("makespan-static-baseline.json", 7);
        expected.put("makespan-online-baseline.json", 35);
        expected.put("makespan-static-scale.json", 6);
        expected.put("makespan-online-scale.json", 30);
        expected.put("energy-flat.json", 15);
        expected.put("energy-tiered.json", 20);
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            WorkbenchConfig config = WorkbenchConfig.read(Paths.get("configs").resolve(entry.getKey()));
            config.validateInputs();
            assertEquals(entry.getValue().intValue(), config.getRunCount(), entry.getKey());
            for (WorkbenchConfig.Candidate candidate : config.getCandidates()) {
                assertEquals(1, candidate.config.getWorkflowPaths().size());
                assertFalse(candidate.algorithmId.isEmpty());
                assertTrue(candidate.workflowPath != null);
                assertEquals(Parameters.CostModel.VM, candidate.config.getCostModel());
                assertEquals(200000, candidate.config.getEventRecorderCap());
            }
        }
        WorkbenchConfig tiered = WorkbenchConfig.read(Paths.get("configs", "energy-tiered.json"));
        for (WorkbenchConfig.Candidate candidate : tiered.getCandidates()) {
            String workflow = Paths.get(candidate.workflowPath).getFileName().toString();
            long expectedDeadline = "Montage_50.dax".equals(workflow)
                    ? 150L
                    : "Montage_100.dax".equals(workflow) ? 300L : "Inspiral_50.dax".equals(workflow) ? 3000L : 3500L;
            assertEquals(expectedDeadline, candidate.config.getDeadline());
        }
    }

    @Test
    void baselineStudyRunsThroughWorkbenchSummary() throws Exception {
        Path staticExperiment = Workbench.run(
                Paths.get("configs", "makespan-static-baseline.json"), directory.resolve("static-output"));
        Path onlineExperiment = Workbench.run(
                Paths.get("configs", "makespan-online-baseline.json"), directory.resolve("online-output"));
        assertSummary(staticExperiment, 7);
        assertSummary(onlineExperiment, 35);
    }

    @Test
    void scaleStudyRunsThroughWorkbenchSummary() throws Exception {
        Path staticExperiment = Workbench.run(
                Paths.get("configs", "makespan-static-scale.json"), directory.resolve("static-scale-output"));
        Path onlineExperiment = Workbench.run(
                Paths.get("configs", "makespan-online-scale.json"), directory.resolve("online-scale-output"));
        assertSummary(staticExperiment, 6);
        assertSummary(onlineExperiment, 30);
    }

    @Test
    void flatEnergyStudyRunsThroughWorkbenchSummary() throws Exception {
        Path experiment = Workbench.run(Paths.get("configs", "energy-flat.json"), directory.resolve("flat-output"));
        assertSummary(experiment, 15);
    }

    @Test
    void tieredEnergyStudyRunsThroughWorkbenchSummary() throws Exception {
        Path experiment = Workbench.run(Paths.get("configs", "energy-tiered.json"), directory.resolve("energy-output"));
        assertSummary(experiment, 20);
    }

    private static void assertSummary(Path experiment, int expectedRuns) throws Exception {
        JsonObject summary = JsonParser.parseString(
                        new String(Files.readAllBytes(experiment.resolve("summary.json")), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(expectedRuns, summary.get("runCount").getAsInt());
        assertEquals(expectedRuns, summary.get("successfulRuns").getAsInt());
        JsonArray runs = summary.getAsJsonArray("runs");
        assertEquals(expectedRuns, runs.size());
        for (JsonElement item : runs) {
            JsonObject run = item.getAsJsonObject();
            assertEquals("COMPLETED_SUCCESSFULLY", run.get("status").getAsString());
            assertEquals(
                    run.get("logicalTasks").getAsInt(),
                    run.get("completedTasks").getAsInt());
            assertTrue(run.get("makespanSeconds").getAsDouble() > 0.0);
            assertTrue(run.get("meanWaitingSeconds").getAsDouble() >= 0.0);
            assertTrue(run.get("energyJoules").getAsDouble() >= 0.0);
            assertTrue(run.get("energyDelayProduct").getAsDouble() >= 0.0);
        }
        assertTrue(Files.isRegularFile(experiment.resolve("summary.csv")));
        assertTrue(Files.isRegularFile(experiment.resolve("summary.html")));
    }
}
