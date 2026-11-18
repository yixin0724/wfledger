package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

public class SimulationEventRecorderTest {

    private static final SimulationEventType TYPE = SimulationEventType.SCHEDULING_CYCLE;

    @Test
    public void defaultCapTwiceTheMeasuredBenchmarkEnvelope() {
        assertEquals(20000, SimulationEventRecorder.DEFAULT_MAX_EVENTS);
    }

    @Test
    public void defaultRecorderCapsAtTheDefaultLimitAndExplainsTheExit() {
        SimulationEventRecorder recorder = new SimulationEventRecorder();
        for (int i = 0; i < SimulationEventRecorder.DEFAULT_MAX_EVENTS; i++) {
            recorder.record(TYPE, (double) i);
        }
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> recorder.record(TYPE, 99999.0));
        String message = error.getMessage();
        assertTrue(message.contains("capacity cap"));
        assertTrue(message.contains("20000"));
        assertTrue(message.contains("disabled()"));

        List<SimulationEvent> retained = recorder.snapshot();
        assertEquals(SimulationEventRecorder.DEFAULT_MAX_EVENTS, retained.size());
        assertEquals(0L, retained.get(0).getSequence());
        assertEquals(
                SimulationEventRecorder.DEFAULT_MAX_EVENTS - 1L,
                retained.get(retained.size() - 1).getSequence());
    }

    @Test
    public void explicitSmallCapFailsFastBeyondTheCapWithoutCorruptingThePrefix() {
        SimulationEventRecorder recorder = new SimulationEventRecorder(3);
        recorder.record(TYPE, 1.0);
        recorder.record(TYPE, 2.0);
        recorder.record(TYPE, 3.0);
        assertThrows(IllegalStateException.class, () -> recorder.record(TYPE, 4.0));

        assertThrows(IllegalStateException.class, () -> recorder.record(TYPE, 5.0));
        List<SimulationEvent> retained = recorder.snapshot();
        assertEquals(3, retained.size());
        assertEquals(0L, retained.get(0).getSequence());
        assertEquals(1.0, retained.get(0).getSimulationTime());
        assertEquals(2.0, retained.get(1).getSimulationTime());
        assertEquals(3.0, retained.get(2).getSimulationTime());
    }

    @Test
    public void nonPositiveCapIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SimulationEventRecorder(0));
        assertThrows(IllegalArgumentException.class, () -> new SimulationEventRecorder(-1));
    }

    @Test
    public void disabledRecorderAcceptsAnyVolumeWithoutRetainingEvents() {
        SimulationEventRecorder disabled = SimulationEventRecorder.disabled();
        for (int i = 0; i < 1000; i++) {
            disabled.record(TYPE, (double) i);
        }
        assertTrue(disabled.snapshot().isEmpty());
        assertFalse(disabled.isEnabled());
    }

    @Test
    public void validationStillRejectsNullTypeAndNonFiniteTime() {
        SimulationEventRecorder recorder = new SimulationEventRecorder(10);
        assertThrows(IllegalArgumentException.class, () -> recorder.record(null, 1.0));
        assertThrows(IllegalArgumentException.class, () -> recorder.record(TYPE, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> recorder.record(TYPE, Double.POSITIVE_INFINITY));
        assertTrue(recorder.snapshot().isEmpty());
    }

    @Test
    public void snapshotStaysIsolatedFromLaterRecording() {
        SimulationEventRecorder recorder = new SimulationEventRecorder(10);
        recorder.record(TYPE, 1.0);
        List<SimulationEvent> early = recorder.snapshot();
        recorder.record(TYPE, 2.0);
        assertEquals(1, early.size());
        assertEquals(2, recorder.snapshot().size());
    }
}
