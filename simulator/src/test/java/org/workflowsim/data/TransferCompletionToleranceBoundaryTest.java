package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * M5 killer (review engineering Phase 3: targeted regression for a surviving mutation
 * pilot).
 *
 * <p>Mutation-pilot evidence: after widening the byte tolerance of the
 * {@code TransferContentionEngine} completion verdict from {@code 1e-9*bytes} to
 * {@code 1e-6*bytes} (a factor of 1000), <b>none of the 1024 existing tests
 * failed</b> -- the completion-verdict boundary was previously a test blind spot.</p>
 *
 * <p>Contract anchoring (no mirroring of the implementation): the completion verdict must
 * treat a flow with "significant bytes remaining" as incomplete; a premature completion
 * verdict compresses the FCT and steals contention capacity. This test uses a 1e6-byte
 * flow and derives the boundary: contract tolerance = max(1e-9 x bytes, 4 x ulp(bytes))
 * = 1e-3 bytes; advanced to the point where <b>0.5 bytes remain</b> (between 1e-3 and
 * the mutation tolerance 1.0): a legal implementation must still report incomplete and
 * settle at the next advance, while the mutant reports completion early -- the two paths
 * are observably separated in time.</p>
 */
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

        // Advance until exactly 0.5 bytes remain (> the contract tolerance 1e-3, < the mutation tolerance 1.0).
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
        // Small-payload flow: the bytes*0.5 branch dominates the tolerance (min(0.05, max(1e-4,4ulp)) = 1e-4).
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
