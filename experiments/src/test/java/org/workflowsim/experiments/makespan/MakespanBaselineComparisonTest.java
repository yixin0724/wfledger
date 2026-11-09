package org.workflowsim.experiments.makespan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Full real-DAX makespan comparison and completion/metric sanity checks. */
class MakespanBaselineComparisonTest {

    @TempDir
    Path directory;

    @Test
    void writesCompleteJsonResults() throws Exception {
        Path output = directory.resolve("results.json");
        MakespanBaselineComparison.main(new String[] {output.toString()});
        String json = new String(Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("Montage_50.dax"));
        assertTrue(json.contains("READY_BATCH_MINMIN"));
        assertTrue(json.contains("meanWaitingSeconds"));
    }

    @Test
    void allScientificWorkflowAndAlgorithmRunsComplete() throws Exception {
        List<MakespanBaselineComparison.Result> results = MakespanBaselineComparison.run();
        assertEquals(42, results.size());
        for (MakespanBaselineComparison.Result result : results) {
            assertEquals(
                    result.getLogicalTasks(),
                    result.getCompletedTasks(),
                    result.getDataset() + " " + result.getAlgorithm());
            assertTrue(result.getMakespanSeconds() > 0.0, result.getDataset());
            assertTrue(result.getMeanWaitingSeconds() >= 0.0, result.getDataset());
            assertTrue(result.getMeanTrueSlowdown() >= 1.0, result.getDataset());
            assertTrue(result.getMeanReadyToDecisionSeconds() >= 0.0, result.getDataset());
            assertTrue(result.getMeanDecisionToStartSeconds() >= 0.0, result.getDataset());
            assertTrue(result.getMeanRunTimeSeconds() >= 0.0, result.getDataset());
            assertTrue(result.getMeanResponseSeconds() >= 0.0, result.getDataset());
            assertTrue(result.getThroughputJobsPerSecond() > 0.0, result.getDataset());
            assertTrue(result.getBusyIntervalSeconds() >= 0.0, result.getDataset());
            assertTrue(
                    result.getMeanVmUtilization() >= 0.0 && result.getMeanVmUtilization() <= 1.0, result.getDataset());
            assertTrue(result.getEnergyJoules() >= 0.0, result.getDataset());
            assertTrue(result.getModeledProcessingCost() >= 0.0, result.getDataset());
            System.out.println("BASELINE " + result.getDataset() + " " + result.getAlgorithm()
                    + " makespan=" + result.getMakespanSeconds()
                    + " wait=" + result.getMeanWaitingSeconds()
                    + " slowdown=" + result.getMeanTrueSlowdown()
                    + " readyDecision=" + result.getMeanReadyToDecisionSeconds()
                    + " decisionStart=" + result.getMeanDecisionToStartSeconds()
                    + " run=" + result.getMeanRunTimeSeconds()
                    + " response=" + result.getMeanResponseSeconds()
                    + " throughput=" + result.getThroughputJobsPerSecond()
                    + " busy=" + result.getBusyIntervalSeconds()
                    + " util=" + result.getMeanVmUtilization()
                    + " energy=" + result.getEnergyJoules()
                    + " cost=" + result.getModeledProcessingCost());
        }
    }
}
