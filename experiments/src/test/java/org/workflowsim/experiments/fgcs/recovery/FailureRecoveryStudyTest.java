package org.workflowsim.experiments.fgcs.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiments.fgcs.FgcStudySupport;

class FailureRecoveryStudyTest {

    @TempDir
    Path directory;

    @Test
    void disabledAndSingleVmFailureCasesCloseRetryAndDependencyInvariants() throws Exception {
        List<FgcStudySupport.Measurement> results = FailureRecoveryStudy.run(directory);
        assertEquals(12, results.size());
        for (FgcStudySupport.Measurement result : results) {
            assertTrue(result.getReport().isWorkflowCompletedSuccessfully(), result.getLabel());
            assertEquals(
                    result.getReport().getMetrics().getLogicalTaskCount(),
                    result.getReport().getMetrics().getSuccessfullyCompletedLogicalTaskCount());
            assertEquals(0.0, result.getAudit().getEnergyResidualJoules(), 1.0e-5, result.getLabel());
            assertEquals(0.0, result.getAudit().getCostResidual(), 1.0e-6, result.getLabel());
            assertEquals(0.0, result.getAudit().getDependencyResidualSeconds(), 1.0e-6, result.getLabel());
            if (result.getLabel().contains("crch-failure")) {
                assertTrue(result.getReport().getJobs().size()
                        >= result.getReport().getMetrics().getLogicalTaskCount());
            }
        }
    }

    @Test
    void mainWritesAllRecoveryRows() throws Exception {
        Path output = directory.resolve("main-results.json");
        FailureRecoveryStudy.main(new String[] {output.toString()});
        com.google.gson.JsonArray rows = com.google.gson.JsonParser.parseString(
                        new String(java.nio.file.Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonArray();
        assertEquals(12, rows.size());
        for (com.google.gson.JsonElement row : rows) {
            assertTrue(row.getAsJsonObject().get("completed").getAsBoolean());
        }
    }
}
