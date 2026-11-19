package org.workflowsim.experiments.fgcs.ablation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiments.fgcs.FgcStudySupport;

class CommunicationStorageAblationTest {

    @TempDir
    Path directory;

    @Test
    void communicationAndStorageAblationsProduceCompleteEvidence() throws Exception {
        List<FgcStudySupport.Measurement> results = CommunicationStorageAblation.run(directory);
        assertEquals(18, results.size());
        for (FgcStudySupport.Measurement result : results) {
            assertTrue(result.getReport().isWorkflowCompletedSuccessfully(), result.getLabel());
            assertEquals(
                    result.getReport().getMetrics().getLogicalTaskCount(),
                    result.getReport().getMetrics().getSuccessfullyCompletedLogicalTaskCount());
            assertEquals(0.0, result.getAudit().getEnergyResidualJoules(), 1.0e-5, result.getLabel());
            assertEquals(0.0, result.getAudit().getCostResidual(), 1.0e-6, result.getLabel());
            assertEquals(0.0, result.getAudit().getDependencyResidualSeconds(), 1.0e-6, result.getLabel());
            assertTrue(result.getArtifactBytes() > 0L);
        }
        for (FgcStudySupport.Measurement result : results) {
            if (result.getLabel().startsWith("coherent-")) {
                assertTrue(result.getReport().getFileLifecycleEvidence() != null, result.getLabel());
            } else if (result.getLabel().startsWith("storage-")) {
                assertTrue(result.getReport().getStorageLifecycleEvidence() != null, result.getLabel());
            } else if (result.getLabel().contains("PRE_EXECUTION_TRANSFER_DELAY_V1")) {
                assertTrue(result.getReport().getNetworkEvidence() == null, result.getLabel());
            } else {
                assertTrue(result.getReport().getNetworkEvidence() != null, result.getLabel());
            }
        }
    }
}
