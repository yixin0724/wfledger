package org.workflowsim.experiments.makespan;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Failsafe coverage for the full real-scientific-workflow comparison driver. */
class MakespanBaselineComparisonIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void fullComparisonWritesEveryDatasetAndAlgorithm() throws Exception {
        Path output = directory.resolve("results.json");
        MakespanBaselineComparison.main(new String[] {output.toString()});
        String json = new String(Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8);
        int runs = json.split("\"dataset\"", -1).length - 1;
        assertTrue(runs == 42);
        assertTrue(json.contains("CyberShake_100.dax"));
        assertTrue(json.contains("Epigenomics_46.dax"));
        assertTrue(json.contains("meanTrueSlowdown"));
    }
}
