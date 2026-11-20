package org.workflowsim.experiments.fgcs.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        boolean anyExercised = false;
        for (FgcStudySupport.Measurement result : results) {
            assertTrue(result.getReport().isWorkflowCompletedSuccessfully(), result.getLabel());
            assertEquals(
                    result.getReport().getMetrics().getLogicalTaskCount(),
                    result.getReport().getMetrics().getSuccessfullyCompletedLogicalTaskCount());
            assertEquals(0.0, result.getAudit().getEnergyResidualJoules(), 1.0e-5, result.getLabel());
            assertEquals(0.0, result.getAudit().getCostResidual(), 1.0e-6, result.getLabel());
            assertEquals(0.0, result.getAudit().getDependencyResidualSeconds(), 1.0e-6, result.getLabel());
            int failedJobs = result.getReport().getFailedJobs();
            int retries = result.getReport().getMetrics().getRetryJobCreatedCount();
            if (result.getLabel().endsWith("-crch-failure")) {
                assertTrue(failedJobs > 0, result.getLabel());
                assertTrue(retries > 0, result.getLabel());
                assertEquals(failedJobs, retries, result.getLabel());
                assertTrue(result.getReport().getJobs().size()
                        > result.getReport().getMetrics().getLogicalTaskCount());
                anyExercised = true;
            } else if (result.getLabel().endsWith("-crch-no-injection-control")) {
                assertTrue(result.getReport().getConfig().getFailureModel().isEnabled(), result.getLabel());
                assertEquals(0, failedJobs, result.getLabel());
                assertEquals(0, retries, result.getLabel());
            } else {
                assertFalse(result.getReport().getConfig().getFailureModel().isEnabled(), result.getLabel());
                assertEquals(0, failedJobs, result.getLabel());
                assertEquals(0, retries, result.getLabel());
            }
        }
        assertTrue(anyExercised, "The recovery matrix must retain at least one genuinely exercised row");
    }

    @Test
    void mainWritesAllRecoveryRows() throws Exception {
        Path output = directory.resolve("main-results.json");
        FailureRecoveryStudy.main(new String[] {output.toString()});
        com.google.gson.JsonArray rows = com.google.gson.JsonParser.parseString(
                        new String(java.nio.file.Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonArray();
        assertEquals(12, rows.size());
        boolean anyExercised = false;
        for (com.google.gson.JsonElement row : rows) {
            com.google.gson.JsonObject value = row.getAsJsonObject();
            assertTrue(value.get("completed").getAsBoolean());
            String classification = value.get("recoveryClassification").getAsString();
            int failedJobs = value.get("failedJobs").getAsInt();
            int retries = value.get("retryJobsCreated").getAsInt();
            if ("EXERCISED_FAILURE_RETRY".equals(classification)) {
                assertTrue(failedJobs > 0 && retries > 0);
                anyExercised = true;
            } else if ("ENABLED_NO_INJECTION_CONTROL".equals(classification)) {
                assertTrue(value.get("failureModelEnabled").getAsBoolean());
                assertEquals(0, failedJobs);
                assertEquals(0, retries);
            } else {
                assertEquals("FAILURE_DISABLED_CONTROL", classification);
                assertFalse(value.get("failureModelEnabled").getAsBoolean());
                assertEquals(0, failedJobs);
                assertEquals(0, retries);
            }
        }
        assertTrue(anyExercised);
    }
}
