package org.workflowsim.experiments.makespan;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Failsafe coverage of the full scale driver. */
class MakespanScaleComparisonIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void scaleDriverWritesAllThirtySixRuns() throws Exception {
        Path output = directory.resolve("scale-results.json");
        MakespanScaleComparison.main(new String[] {output.toString()});
        String json = new String(Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8);
        int runs = json.split("\"dataset\"", -1).length - 1;
        assertTrue(runs == 36);
        assertTrue(json.contains("#1000"));
        assertTrue(json.contains("#5000"));
    }
}
