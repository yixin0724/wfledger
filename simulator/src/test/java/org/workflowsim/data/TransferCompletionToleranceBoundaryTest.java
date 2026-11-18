package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class TransferCompletionToleranceBoundaryTest {

    @Test
    void halfByteRemainingIsNotCompleteButOneFullMillibyteToleranceIs() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 1000.0);
        double bytes = 1_000_000.0;
        double rate = 1000.0;

        TransferContentionEngine.AdvanceResult admit = engine.addTransfer(1L, bytes, Arrays.asList("VM:0"), rate, 0.0);
        assertTrue(
                admit.getCompletedTransferIds().isEmpty(),
                "a single flow at full capacity must not complete at admission time");

        double partialSeconds = (bytes - 0.5) / rate;
        TransferContentionEngine.AdvanceResult partial = engine.advance(partialSeconds);
        assertTrue(
                partial.getCompletedTransferIds().isEmpty(),
                "0.5 bytes remaining far exceeds the contract tolerance "
                        + "max(1e-9*bytes, 4*ulp) = 1e-3 bytes; completion must not be "
                        + "declared early (mutation M5 would declare it here)");
        Double next = partial.getNextCompletionTime();
        assertNotNull(next, "an incomplete flow must report its next completion time");
        assertTrue(next > partialSeconds, "the next completion time must be later than the current advance point");

        TransferContentionEngine.AdvanceResult settled = engine.advance(next);
        assertFalse(
                settled.getCompletedTransferIds().isEmpty(),
                "settlement must occur once the true completion time is reached");
    }

    @Test
    void completionJudgeNeverTreatsAnUntouchedPositiveFlowAsComplete() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 1.0);

        double bytes = 10.0;
        TransferContentionEngine.AdvanceResult admit = engine.addTransfer(2L, bytes, Arrays.asList("VM:0"), 1.0, 0.0);
        assertTrue(
                admit.getCompletedTransferIds().isEmpty(),
                "a positive-payload flow that has never been served must not be judged "
                        + "complete (the tolerance is a relative rounding guard, not a "
                        + "free allowance)");
        Double next = admit.getNextCompletionTime();
        assertNotNull(next);
        assertTrue(next > 0.0, "10 bytes at 1 B/s must complete at a positive time");
    }
}
