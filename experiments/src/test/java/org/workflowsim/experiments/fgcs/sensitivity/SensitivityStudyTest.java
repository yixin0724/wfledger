package org.workflowsim.experiments.fgcs.sensitivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.experiments.fgcs.FgcStudySupport;

class SensitivityStudyTest {

    @Test
    void completeSensitivityMatrixPreservesMetricsAndCompletion() throws Exception {
        List<FgcStudySupport.Measurement> results = SensitivityStudy.run();
        assertEquals(216, results.size());
        for (FgcStudySupport.Measurement result : results) {
            assertTrue(result.getReport().isWorkflowCompletedSuccessfully(), result.getLabel());
            assertEquals(
                    result.getReport().getMetrics().getLogicalTaskCount(),
                    result.getReport().getMetrics().getSuccessfullyCompletedLogicalTaskCount());
            assertEquals(0.0, result.getAudit().getEnergyResidualJoules(), 1.0e-5, result.getLabel());
            assertEquals(0.0, result.getAudit().getCostResidual(), 1.0e-6);
            assertEquals(0.0, result.getAudit().getDependencyResidualSeconds(), 1.0e-6);
        }
    }
}
