package org.workflowsim.experiments.energy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnergyBaselineComparisonIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void energyDriverWritesAllThirtyFiveRuns() throws Exception {
        Path output = directory.resolve("energy-results.json");
        EnergyBaselineComparison.main(new String[] {output.toString()});
        String json = new String(Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8);
        int runs = json.split("\"dataset\"", -1).length - 1;
        assertTrue(runs == 35);
        assertTrue(json.contains("GREEN_HEFT"));
        assertTrue(json.contains("HEFT_EDP_HEURISTIC"));
        assertTrue(json.contains("energyDelayProduct"));
    }
}
