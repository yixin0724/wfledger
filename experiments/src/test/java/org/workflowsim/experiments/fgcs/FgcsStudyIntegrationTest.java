package org.workflowsim.experiments.fgcs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

class FgcsStudyIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void researchDriversProduceTheirDeclaredMatrices() throws Exception {
        org.workflowsim.experiments.fgcs.differential.UpstreamDifferentialExporter.main(
                new String[] {directory.resolve("differential/current.json").toString()});
        assertRows(12, "differential/current.json");

        org.workflowsim.experiments.fgcs.sensitivity.SensitivityStudy.main(
                new String[] {directory.resolve("sensitivity/results.json").toString()});
        assertRows(216, "sensitivity/results.json");

        org.workflowsim.experiments.fgcs.energy.EnergySensitivityStudy.main(
                new String[] {directory.resolve("energy/results.json").toString()});
        assertRows(36, "energy/results.json");

        org.workflowsim.experiments.fgcs.ablation.CommunicationStorageAblation.main(
                new String[] {directory.resolve("ablation/results.json").toString()});
        assertRows(18, "ablation/results.json");

        org.workflowsim.experiments.fgcs.recovery.FailureRecoveryStudy.main(
                new String[] {directory.resolve("recovery/results.json").toString()});
        assertRows(12, "recovery/results.json");

        org.workflowsim.experiments.fgcs.scalability.EvidenceOverheadStudy.main(
                new String[] {directory.resolve("overhead/results.json").toString(), "1"});
        assertRows(9, "overhead/results.json");
    }

    private void assertRows(int expected, String relativePath) throws Exception {
        Path path = directory.resolve(relativePath);
        JsonArray rows = JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                .getAsJsonArray();
        assertEquals(expected, rows.size(), relativePath);
    }
}
