package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** Integration-level evidence chain and tamper guard for Q02 (2026-11-04). */
class ExperimentCampaignSummaryVerificationIntegrationTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void validatesAndRejectsEditedSummaryAtTheEvidenceBoundary(@TempDir Path output) throws Exception {
        String workflow = resourcePath("/dax/reproducibility-workflow.dax");
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("campaign-it-platform", 2);
        SeedPlan seeds = SeedPlan.deterministic(20260902L);
        ExperimentPlan plan = ExperimentPlan.builder("campaign-summary-it")
                .addCell(cell("fcfs", "baseline", true, workflow, platform, seeds, Parameters.SchedulingAlgorithm.FCFS))
                .addCell(cell(
                        "mct",
                        "ready-batch-mct",
                        false,
                        workflow,
                        platform,
                        seeds,
                        Parameters.SchedulingAlgorithm.READY_BATCH_MCT))
                .build();
        Log.disable();
        ExperimentCampaignResult result = new ExperimentCampaignExecutor().execute(plan);
        ExperimentCampaignArtifactWriter.CampaignArtifacts artifacts =
                ExperimentCampaignArtifactWriter.write(result, output);
        assertEquals(
                2, ExperimentCampaignValidator.validate(artifacts.getIndex()).getRunCount());

        com.google.gson.JsonObject index = com.google.gson.JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getIndex()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        index.getAsJsonObject("summary")
                .getAsJsonArray("comparisons")
                .get(0)
                .getAsJsonObject()
                .addProperty("meanMakespanRatio", 2.0);
        Files.write(
                artifacts.getIndex(),
                new com.google.gson.GsonBuilder()
                        .serializeNulls()
                        .create()
                        .toJson(index)
                        .getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> ExperimentCampaignValidator.validate(artifacts.getIndex()));
    }

    private static ExperimentPlan.Cell cell(
            String id,
            String candidate,
            boolean baseline,
            String workflow,
            PlatformProfile platform,
            SeedPlan seeds,
            Parameters.SchedulingAlgorithm algorithm) {
        SimulationConfig config = SimulationConfig.builder(workflow, 2)
                .schedulingAlgorithm(algorithm)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .build();
        return new ExperimentPlan.Cell(
                id,
                "same-input",
                candidate,
                baseline,
                config,
                platform,
                seeds,
                Collections.singletonMap("purpose", "integration-summary-verification"));
    }

    private static String resourcePath(String resource) throws Exception {
        URL url = ExperimentCampaignSummaryVerificationIntegrationTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
