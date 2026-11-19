package org.workflowsim.experiments.fgcs.differential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiments.fgcs.FgcStudySupport;

class UpstreamDifferentialStudyTest {

    @TempDir
    Path directory;

    @Test
    void realAndSmallDagDifferentialMatrixClosesIndependentInvariants() throws Exception {
        List<FgcStudySupport.Measurement> results = UpstreamDifferentialStudy.run(directory);
        assertEquals(24, results.size());
        for (FgcStudySupport.Measurement result : results) {
            assertTrue(result.getReport().isWorkflowCompletedSuccessfully(), result.getLabel());
            assertEquals(
                    result.getReport().getMetrics().getLogicalTaskCount(),
                    result.getReport().getMetrics().getSuccessfullyCompletedLogicalTaskCount());
            assertEquals(0.0, result.getAudit().getEnergyResidualJoules(), 1.0e-5, result.getLabel());
            assertEquals(
                    0.0,
                    result.getAudit().getPeakResidualWatts(),
                    1.0e-5,
                    result.getLabel() + " peak="
                            + result.getReport().getEnergyAccounting().getPeakPowerWatts());
            assertEquals(0.0, result.getAudit().getCostResidual(), 1.0e-6);
            assertEquals(0.0, result.getAudit().getDependencyResidualSeconds(), 1.0e-6, result.getLabel());
        }
        FgcStudySupport.writeResults(directory.resolve("results.json"), results);
        assertTrue(java.nio.file.Files.isRegularFile(directory.resolve("results.json")));
    }
}
