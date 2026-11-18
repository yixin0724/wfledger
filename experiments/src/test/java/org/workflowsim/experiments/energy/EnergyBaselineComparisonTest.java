package org.workflowsim.experiments.energy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class EnergyBaselineComparisonTest {

    @Test
    void allEnergyRunsCompleteAndExposeTradeoffs() throws Exception {
        List<EnergyBaselineComparison.Result> results = EnergyBaselineComparison.run();
        assertEquals(35, results.size());
        for (EnergyBaselineComparison.Result result : results) {
            assertEquals(result.getLogicalTasks(), result.getCompletedTasks(), result.getDataset());
            assertTrue(result.getMakespanSeconds() > 0.0, result.getDataset());
            assertTrue(result.getEnergyJoules() >= 0.0, result.getDataset());
            assertTrue(result.getEnergyDelayProduct() >= 0.0, result.getDataset());
            assertTrue(result.getMeanWaitingSeconds() >= 0.0, result.getDataset());
            assertTrue(result.getMeanTrueSlowdown() >= 1.0, result.getDataset());
            assertTrue(result.getThroughputJobsPerSecond() > 0.0, result.getDataset());
            assertTrue(
                    result.getMeanVmUtilization() >= 0.0 && result.getMeanVmUtilization() <= 1.0, result.getDataset());
            assertTrue(result.getModeledProcessingCost() >= 0.0, result.getDataset());
            System.out.println("ENERGY " + result.getGroup() + " " + result.getDataset() + " " + result.getAlgorithm()
                    + " makespan=" + result.getMakespanSeconds() + " energy=" + result.getEnergyJoules()
                    + " edp=" + result.getEnergyDelayProduct() + " wait=" + result.getMeanWaitingSeconds()
                    + " slowdown=" + result.getMeanTrueSlowdown() + " util=" + result.getMeanVmUtilization()
                    + " cost=" + result.getModeledProcessingCost());
        }
    }
}
