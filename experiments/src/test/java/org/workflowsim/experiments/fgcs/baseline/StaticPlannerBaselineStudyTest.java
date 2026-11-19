package org.workflowsim.experiments.fgcs.baseline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.experiments.fgcs.FgcStudySupport;

class StaticPlannerBaselineStudyTest {

    @Test
    void sameLayerStaticPlannersCompleteWithIndependentMetricChecks() throws Exception {
        List<FgcStudySupport.Measurement> results = StaticPlannerBaselineStudy.run();
        assertEquals(21, results.size());
        for (FgcStudySupport.Measurement result : results) {
            assertTrue(result.getReport().isWorkflowCompletedSuccessfully(), result.getLabel());
            assertEquals(
                    result.getReport().getMetrics().getLogicalTaskCount(),
                    result.getReport().getMetrics().getSuccessfullyCompletedLogicalTaskCount());
            assertEquals(0.0, result.getAudit().getEnergyResidualJoules(), 1.0e-5, result.getLabel());
            assertEquals(0.0, result.getAudit().getCostResidual(), 1.0e-6, result.getLabel());
            assertEquals(0.0, result.getAudit().getDependencyResidualSeconds(), 1.0e-6, result.getLabel());
        }
    }

    @Test
    void mainWritesTheCompleteStaticMatrix() throws Exception {
        java.nio.file.Path output =
                java.nio.file.Files.createTempDirectory("fgcs-static").resolve("results.json");
        StaticPlannerBaselineStudy.main(new String[] {output.toString()});
        com.google.gson.JsonArray rows = com.google.gson.JsonParser.parseString(
                        new String(java.nio.file.Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonArray();
        assertEquals(21, rows.size());
    }
}
