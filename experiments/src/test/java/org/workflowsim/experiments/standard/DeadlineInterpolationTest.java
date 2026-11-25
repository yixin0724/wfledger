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

/** STD-V1 (2026-11-25): deadline interpolation checks. */
class DeadlineInterpolationTest {

    @TempDir
    Path directory;

    @Test
    void interpolationIsLinearAndUnrounded() {
        // MS_fast = 100, MS_slow = 260: D(0.1) = 116, D(0.5) = 180, D(0.9) = 244; 100.3 + 0.3 * 0.5 = 100.45.
        assertEquals(116.0, DeadlineInterpolation.deadline(100.0, 260.0, 0.1), 1.0e-12);
        assertEquals(180.0, DeadlineInterpolation.deadline(100.0, 260.0, 0.5), 1.0e-12);
        assertEquals(244.0, DeadlineInterpolation.deadline(100.0, 260.0, 0.9), 1.0e-12);
        assertEquals(100.45, DeadlineInterpolation.deadline(100.3, 100.8, 0.3), 1.0e-12);
        assertThrows(IllegalArgumentException.class, () -> DeadlineInterpolation.deadline(100.0, 90.0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> DeadlineInterpolation.deadline(100.0, 200.0, 1.5));
    }

    /**
     * One 10 s task (10,000 MI at the 1000-MIPS runtime reference) without inputs on the standard 2-VM fleet
     * (500 and 2000 MIPS). HEFT places it on the 2000-MIPS VM: 10000 / 2000 = 5 s at the default tier. Under
     * MIN_POWER the Opteron level 5 runs at 2000 * 1.0 / 2.6 MIPS, so the Job length becomes
     * round(10000 * 2.6) = 26000 MI on the 2000-MIPS VM: 13 s. The model-generated stage-in carries no tier, so
     * both runs share the same release offset and MS_slow - MS_fast = 13 - 5 = 8 s.
     */
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
