package org.workflowsim.experiments.fgcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiments.fgcs.baseline.StaticPlannerBaselineStudy;
import org.workflowsim.experiments.fgcs.contracts.SemanticContractBenchmarks;
import org.workflowsim.experiments.fgcs.energy.EnergyDeadlineStudy;
import org.workflowsim.experiments.fgcs.recovery.FailureRecoveryStudy;
import org.workflowsim.experiments.fgcs.rerun.ManifestGuidedRerunStudy;
import org.workflowsim.experiments.fgcs.scalability.EvidenceOverheadStudy;

import com.google.gson.JsonObject;

class RoundTwoEvidenceIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void manifestGuidedRerunExecutesEveryVerdictThroughTheRealExecutor() throws Exception {
        JsonObject report = ManifestGuidedRerunStudy.run(directory.resolve("rerun"));
        assertEquals(9, report.getAsJsonArray("cases").size());
        assertEquals(
                0, report.getAsJsonObject("summary").get("unexpectedOutcomes").getAsInt());
    }

    @Test
    void semanticContractsAndClassifiedRecoveryUseActualFailures() throws Exception {
        SemanticContractBenchmarks.Suite suite = SemanticContractBenchmarks.run(directory.resolve("contracts"));
        assertEquals(3, suite.getDocument().getAsJsonArray("cases").size());
        List<FgcStudySupport.Measurement> recovery = FailureRecoveryStudy.run(directory.resolve("recovery-inputs"));
        assertEquals(12, recovery.size());
        assertTrue(recovery.stream().anyMatch(row -> row.getReport().getFailedJobs() > 0));
    }

    @Test
    void commonDeadlineAndFixedCapacityUseRealSimulationRuns() throws Exception {
        List<JsonObject> energy = EnergyDeadlineStudy.runWorkflow(FgcDatasets.MONTAGE_50);
        assertEquals(16, energy.size());
        org.workflowsim.utils.SimulationConfig config = FgcStudySupport.base(
                        FgcDatasets.real(FgcDatasets.MONTAGE_50), 1)
                .planningAlgorithm(org.workflowsim.utils.Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(org.workflowsim.utils.Parameters.SchedulingAlgorithm.STATIC)
                .build();
        FgcStudySupport.Measurement result =
                FgcStudySupport.run("fixed-capacity-integration", config, FgcPlatforms.fixedCapacity(1, 1, 1), null);
        assertTrue(result.getReport().isWorkflowCompletedSuccessfully());
    }

    @Test
    void overheadModesExecuteCommonBundleAndSerializedValidators() throws Exception {
        for (String mode : EvidenceOverheadStudy.modes()) {
            Path output = directory.resolve(mode + ".json");
            EvidenceOverheadStudy.main(new String[] {
                output.toString(),
                FgcDatasets.MONTAGE_50,
                mode,
                "0",
                directory.resolve("evidence").toString()
            });
            assertTrue(java.nio.file.Files.size(output) > 0);
        }
    }

    @Test
    void staticBaselineUsesTheOriginalPlatform() throws Exception {
        List<FgcStudySupport.Measurement> results = StaticPlannerBaselineStudy.run();
        assertEquals(21, results.size());
        assertTrue(results.get(0).getReport().isWorkflowCompletedSuccessfully());
    }
}
