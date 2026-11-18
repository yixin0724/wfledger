package org.workflowsim.experiments.makespan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MakespanScaleComparisonTest {

    @TempDir
    Path directory;

    @Test
    void allScaleRunsCompleteAndExposeWaitingMetrics() throws Exception {
        List<MakespanBaselineComparison.Result> results = MakespanScaleComparison.run();
        assertEquals(36, results.size());
        for (MakespanBaselineComparison.Result result : results) {
            assertEquals(result.getLogicalTasks(), result.getCompletedTasks(), result.getDataset());
            assertTrue(result.getMakespanSeconds() > 0.0, result.getDataset());
            assertTrue(result.getMeanWaitingSeconds() >= 0.0, result.getDataset());
            assertTrue(result.getMeanTrueSlowdown() >= 1.0, result.getDataset());
            assertTrue(result.getThroughputJobsPerSecond() > 0.0, result.getDataset());
            assertTrue(
                    result.getMeanVmUtilization() >= 0.0 && result.getMeanVmUtilization() <= 1.0, result.getDataset());
            System.out.println("SCALE " + result.getDataset() + " " + result.getAlgorithm()
                    + " makespan=" + result.getMakespanSeconds()
                    + " wait=" + result.getMeanWaitingSeconds()
                    + " slowdown=" + result.getMeanTrueSlowdown()
                    + " throughput=" + result.getThroughputJobsPerSecond()
                    + " util=" + result.getMeanVmUtilization()
                    + " energy=" + result.getEnergyJoules()
                    + " cost=" + result.getModeledProcessingCost());
        }
    }
}
