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
    void staticHeftUsesExactlyTheOriginalBaselinePlatform() throws Exception {
        FgcStudySupport.Measurement staticResult =
                StaticPlannerBaselineStudy.run().get(0);
        java.util.List<org.workflowsim.experiments.makespan.MakespanBaselineComparison.Result> baseline =
                org.workflowsim.experiments.makespan.MakespanBaselineComparison.runDataset(
                        workflow(org.workflowsim.experiments.fgcs.FgcDatasets.MONTAGE_50),
                        org.workflowsim.experiments.fgcs.FgcDatasets.MONTAGE_50);
        assertEquals(
                baseline.get(0).toJson().get("makespanSeconds").getAsDouble(),
                staticResult.toJson().get("makespanSeconds").getAsDouble(),
                1e-9);
        assertEquals(
                baseline.get(0).toJson().get("modeledProcessingCost").getAsDouble(),
                staticResult.toJson().get("modeledProcessingCost").getAsDouble(),
                1e-9);
    }

    private static java.nio.file.Path workflow(String relative) {
        java.nio.file.Path current = java.nio.file.Paths.get("").toAbsolutePath();
        while (current != null && !java.nio.file.Files.isDirectory(current.resolve("datasets"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate datasets directory");
        }
        return current.resolve(relative);
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
