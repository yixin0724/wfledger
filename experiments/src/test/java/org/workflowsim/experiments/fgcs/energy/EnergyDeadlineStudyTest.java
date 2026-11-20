package org.workflowsim.experiments.fgcs.energy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.experiments.fgcs.FgcDatasets;

import com.google.gson.JsonObject;

class EnergyDeadlineStudyTest {

    @Test
    void deadlineUsesCeilingAndRejectsInvalidInputs() {
        assertEquals(371L, EnergyDeadlineStudy.deadline(308.84, 1.2));
        assertEquals(309L, EnergyDeadlineStudy.deadline(308.84, 1.0));
        assertThrows(IllegalArgumentException.class, () -> EnergyDeadlineStudy.deadline(0.0, 1.2));
        assertThrows(IllegalArgumentException.class, () -> EnergyDeadlineStudy.deadline(1.0, 0.9));
    }

    @Test
    void everyPolicyInADeadlineCellHasTheSameConstraint() throws Exception {
        List<JsonObject> rows = EnergyDeadlineStudy.runWorkflow(FgcDatasets.MONTAGE_50);
        assertEquals(16, rows.size());
        assertTrue(rows.get(0).get("deadlineSeconds").isJsonNull());
        assertTrue(rows.get(0).get("deadlineFeasible").isJsonNull());
        assertEquals("NOT_REQUESTED", rows.get(0).get("deadlineConstraint").getAsString());
        for (int index = 1; index < rows.size(); index += 3) {
            JsonObject minPower = rows.get(index);
            JsonObject edp = rows.get(index + 1);
            JsonObject dvfs = rows.get(index + 2);
            assertEquals(
                    minPower.get("deadlineSeconds").getAsLong(),
                    edp.get("deadlineSeconds").getAsLong());
            assertEquals(
                    minPower.get("deadlineSeconds").getAsLong(),
                    dvfs.get("deadlineSeconds").getAsLong());
            assertEquals(
                    minPower.get("deadlineFactor").getAsDouble(),
                    dvfs.get("deadlineFactor").getAsDouble(),
                    1e-12);
            for (JsonObject row : new JsonObject[] {minPower, edp, dvfs}) {
                assertEquals(
                        row.get("makespanSeconds").getAsDouble()
                                <= row.get("deadlineSeconds").getAsLong() + 1e-9,
                        row.get("deadlineFeasible").getAsBoolean());
                assertTrue(row.get("completed").getAsBoolean());
            }
        }
    }
}
