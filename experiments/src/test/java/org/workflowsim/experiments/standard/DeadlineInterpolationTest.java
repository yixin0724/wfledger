package org.workflowsim.experiments.standard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class DeadlineInterpolationTest {

    @TempDir
    Path directory;

    @Test
    void interpolationIsLinearAndUnrounded() {

        assertEquals(116.0, DeadlineInterpolation.deadline(100.0, 260.0, 0.1), 1.0e-12);
        assertEquals(180.0, DeadlineInterpolation.deadline(100.0, 260.0, 0.5), 1.0e-12);
        assertEquals(244.0, DeadlineInterpolation.deadline(100.0, 260.0, 0.9), 1.0e-12);
        assertEquals(100.45, DeadlineInterpolation.deadline(100.3, 100.8, 0.3), 1.0e-12);
        assertThrows(IllegalArgumentException.class, () -> DeadlineInterpolation.deadline(100.0, 90.0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> DeadlineInterpolation.deadline(100.0, 200.0, 1.5));
    }

    @Test
    void slowReferenceReusesTheMappingAtTheLowestTier() throws Exception {
        Log.disable();
        Path dax = directory.resolve("single.dax");
        Files.write(
                dax, "<adag version=\"3.3\"><job id=\"a\" runtime=\"10\"/></adag>".getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(dax.toString(), 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .cloudSimMinEventIntervalSeconds(0.001)
                .build();
        DeadlineInterpolation.References references =
                DeadlineInterpolation.measure(config, StandardPlatforms.tiered(2, 10.0, 0.6));
        assertEquals(8.0, references.getSlowMakespan() - references.getFastMakespan(), 1.0e-6);
        assertTrue(references.getFastMakespan() >= 5.0, String.valueOf(references.getFastMakespan()));
        assertEquals(
                references.getFastMakespan() + 4.0, references.deadline(0.5), 1.0e-6, "D(0.5) = MS_fast + 0.5 * 8");
    }
}
