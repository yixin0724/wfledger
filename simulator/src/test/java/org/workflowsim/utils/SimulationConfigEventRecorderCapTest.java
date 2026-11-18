package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.workflowsim.experiment.SimulationEventRecorder;

class SimulationConfigEventRecorderCapTest {

    @Test
    void unsetCapKeepsTheRecorderDefault() {
        assertEquals(
                SimulationEventRecorder.DEFAULT_MAX_EVENTS,
                SimulationConfig.builder("workflow.dax", 1).build().getEventRecorderCap());

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
