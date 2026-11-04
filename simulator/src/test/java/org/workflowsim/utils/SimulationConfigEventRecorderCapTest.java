package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.workflowsim.experiment.SimulationEventRecorder;

/**
 * AUD-F07 follow-up (2026-11-04, post-completion review): the event-recorder retention cap is
 * run configuration, not a Java-only constructor argument. A config that leaves the cap unset
 * keeps the recorder default; a declared cap round-trips through copies; a non-positive cap is
 * rejected at build time instead of failing mid-run.
 */
class SimulationConfigEventRecorderCapTest {

    @Test
    void unsetCapKeepsTheRecorderDefault() {
        assertEquals(
                SimulationEventRecorder.DEFAULT_MAX_EVENTS,
                SimulationConfig.builder("workflow.dax", 1).build().getEventRecorderCap());
        // Drift lock: the two constants must stay the same number, or the config outlet
        // silently changes the recorded behavior of every run that leaves the cap unset.
        assertEquals(SimulationEventRecorder.DEFAULT_MAX_EVENTS, SimulationConfig.DEFAULT_EVENT_RECORDER_CAP);
    }

    @Test
    void declaredCapRoundTripsThroughCopies() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 1)
                .eventRecorderCap(50000)
                .build();
        assertEquals(50000, config.getEventRecorderCap());
        assertEquals(50000, config.withRandomSeed(7L).getEventRecorderCap());
        assertEquals(50000, config.toBuilder().build().getEventRecorderCap());
    }

    @Test
    void nonPositiveCapIsRejectedAtBuildTime() {
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder("workflow.dax", 1)
                .eventRecorderCap(0)
                .build());
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder("workflow.dax", 1)
                .eventRecorderCap(-5)
                .build());
    }
}
