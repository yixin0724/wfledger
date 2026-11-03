package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Semantic tests of the fluid link contention engine: fair-share rates, completion settlement, and
 * rate changes with concurrency.
 */
public class TransferContentionEngineTest {

    /** Without contention a transfer completes at its nominal rate. */
    @Test
    public void uncongestedTransferCompletesAtNominalRate() {
        TransferContentionEngine engine = new TransferContentionEngine();
        TransferContentionEngine.AdvanceResult result = engine.addTransfer(1L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);
        assertEquals(10.0, result.getNextCompletionTime(), 1.0e-9);
        TransferContentionEngine.AdvanceResult done = engine.advance(10.0);
        assertEquals(1, done.getCompletedTransferIds().size());
        assertEquals(Long.valueOf(1L), done.getCompletedTransferIds().get(0));
        assertNull(done.getNextCompletionTime());
        assertEquals(0, engine.activeTransferCount());
    }

    /**
     * Two concurrent transfers on the same destination endpoint fairly share capacity: each gets half
     * and the completion time doubles.
     */
    @Test
    public void twoConcurrentTransfersShareDestinationEndpoint() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 100.0);
        engine.addTransfer(1L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);
        TransferContentionEngine.AdvanceResult second = engine.addTransfer(2L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);
        // Nominal 10 seconds; after sharing each gets 50 bytes/second -> 20 seconds.
        assertEquals(20.0, second.getNextCompletionTime(), 1.0e-9);
        assertEquals(50.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        assertEquals(50.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        // Midway settlement: at t=5 each has transferred 250 bytes and neither is done; the rate is unchanged.
        TransferContentionEngine.AdvanceResult midway = engine.advance(5.0);
        assertTrue(midway.getCompletedTransferIds().isEmpty());
        assertEquals(20.0, midway.getNextCompletionTime(), 1.0e-9);
        // At t=20 both complete.
        TransferContentionEngine.AdvanceResult done = engine.advance(20.0);
        assertEquals(2, done.getCompletedTransferIds().size());
        assertNull(done.getNextCompletionTime());
    }

    /** After one transfer completes, the rate of the surviving transfer recovers to the full capacity. */
    @Test
    public void survivingTransferSpeedsUpWhenCompetitorFinishes() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 100.0);
        // Transfer 1 is small (500 bytes), transfer 2 is large (1000 bytes); both start sharing.
        engine.addTransfer(1L, 500L, "SOURCE", "VM:0", 100.0, 0.0);
        engine.addTransfer(2L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);
        // Shared 50 bytes/second: transfer 1 completes at t=10 (500/50) and transfer 2 has 500 bytes left.
        TransferContentionEngine.AdvanceResult done = engine.advance(10.0);
        assertEquals(1, done.getCompletedTransferIds().size());
        assertEquals(Long.valueOf(1L), done.getCompletedTransferIds().get(0));
        // Transfer 2 recovers to 100 bytes/second: 500 bytes left -> completes at t=15.
        assertEquals(100.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        assertEquals(15.0, done.getNextCompletionTime(), 1.0e-9);
        TransferContentionEngine.AdvanceResult finalDone = engine.advance(15.0);
        assertEquals(1, finalDone.getCompletedTransferIds().size());
        assertEquals(Long.valueOf(2L), finalDone.getCompletedTransferIds().get(0));
    }

    /**
     * A transfer loads both the source and the destination endpoint: a VM-to-VM transfer occupies
     * both, and the bottleneck endpoint sets the rate.
     */
    @Test
    public void vmToVmTransferLoadsBothEndpoints() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 200.0);
        engine.setEndpointCapacity("VM:1", 100.0);
        // VM:0->VM:1 transfer on its own: nominal 100, destination capacity 100 -> rate 100.
        engine.addTransfer(1L, 1000L, "VM:0", "VM:1", 100.0, 0.0);
        // Then transfer VM:0->VM:2 (VM:2 has no capacity cap): it occupies the VM:0 source -> VM:0 load 2.
        TransferContentionEngine.AdvanceResult second = engine.addTransfer(2L, 1000L, "VM:0", "VM:2", 100.0, 0.0);
        // Transfer 1: min(100, VM:0 share 100, VM:1 share 100) = 100.
        // Transfer 2: min(100, VM:0 share 100, infinity) = 100.
        assertEquals(100.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        assertEquals(100.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        assertEquals(10.0, second.getNextCompletionTime(), 1.0e-9);
    }

    /**
     * A new transfer joins in the middle of an existing transfer: the existing transfer slows down
     * and no spurious completion is produced.
     */
    @Test
    public void lateArrivalSlowsExistingTransfer() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 100.0);
        engine.addTransfer(1L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);
        // Transfer 2 joins at t=4: transfer 1 has moved 400 bytes and has 600 left.
        TransferContentionEngine.AdvanceResult second = engine.addTransfer(2L, 1000L, "SOURCE", "VM:0", 100.0, 4.0);
        assertTrue(second.getCompletedTransferIds().isEmpty());
        // Shared 50 bytes/second: transfer 1 has 600 left -> 12 more seconds (completes at t=16);
        // transfer 2 has 1000 left -> 20 more seconds (completes at t=24). The earliest prediction is 16.
        assertEquals(16.0, second.getNextCompletionTime(), 1.0e-9);
        TransferContentionEngine.AdvanceResult firstDone = engine.advance(16.0);
        assertEquals(1, firstDone.getCompletedTransferIds().size());
        assertEquals(Long.valueOf(1L), firstDone.getCompletedTransferIds().get(0));
        // Transfer 2: from t=4 to t=16 it moved 600 bytes at 50 and has 400 left; after recovering to 100 it
        // completes at t=20.
        assertEquals(100.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        assertEquals(20.0, firstDone.getNextCompletionTime(), 1.0e-9);
        TransferContentionEngine.AdvanceResult done = engine.advance(20.0);
        assertEquals(1, done.getCompletedTransferIds().size());
        assertEquals(Long.valueOf(2L), done.getCompletedTransferIds().get(0));
    }

    /**
     * Three transfers on the same endpoint: the capacity splits into thirds, and the rates and
     * completion time match the hand-derived values.
     */
    @Test
    public void threeConcurrentTransfersSplitCapacityEqually() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 90.0);
        engine.addTransfer(1L, 900L, "SOURCE", "VM:0", 90.0, 0.0);
        engine.addTransfer(2L, 900L, "SOURCE", "VM:0", 90.0, 0.0);
        TransferContentionEngine.AdvanceResult third = engine.addTransfer(3L, 900L, "SOURCE", "VM:0", 90.0, 0.0);
        // 30 bytes/second each -> 30 seconds to complete.
        assertEquals(30.0, third.getNextCompletionTime(), 1.0e-9);
        assertEquals(30.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        TransferContentionEngine.AdvanceResult done = engine.advance(30.0);
        assertEquals(3, done.getCompletedTransferIds().size());
    }

    /**
     * When the nominal rate is below the endpoint share, the transfer proceeds at its nominal rate
     * (capacity is not the bottleneck).
     */
    @Test
    public void nominalRateCapsTransferBelowEndpointShare() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 1000.0);
        engine.addTransfer(1L, 500L, "SOURCE", "VM:0", 50.0, 0.0);
        assertEquals(50.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
    }

    /** Resource-set overload: when two transfers share one link key, each gets half the capacity of that link. */
    @Test
    public void resourceSetTransfersShareSingleLink() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("LINK:CORE:0", 100.0);
        engine.setEndpointCapacity("VM:0", 1000.0);
        engine.setEndpointCapacity("VM:1", 1000.0);
        engine.addTransfer(1L, 1000L, java.util.Arrays.asList("VM:0", "VM:1", "LINK:CORE:0"), 100.0, 0.0);
        TransferContentionEngine.AdvanceResult second =
                engine.addTransfer(2L, 1000L, java.util.Arrays.asList("VM:2", "VM:3", "LINK:CORE:0"), 100.0, 0.0);
        // Link 100/2 = 50 bytes/second (an endpoint share of 1000 is not a bottleneck) -> 20 seconds each.
        assertEquals(50.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        assertEquals(50.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        assertEquals(20.0, second.getNextCompletionTime(), 1.0e-9);
    }

    /**
     * The resource-set rate is the minimum over the shares of all occupied resources: endpoints and
     * links constrain it together.
     */
    @Test
    public void resourceSetRateIsMinAcrossEndpointsAndLinks() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:1", 60.0);
        engine.setEndpointCapacity("LINK:CORE:0", 100.0);
        engine.addTransfer(1L, 600L, java.util.Arrays.asList("VM:0", "VM:1", "LINK:CORE:0"), 100.0, 0.0);
        // min(nominal 100, VM:0 infinity, VM:1 60, link 100) = 60 -> 10 seconds.
        assertEquals(60.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        assertEquals(10.0, engine.advance(0.0).getNextCompletionTime(), 1.0e-9);
    }

    /** Two transfers with disjoint resource sets do not affect each other (no blocking semantics). */
    @Test
    public void disjointResourceSetsDoNotInterfere() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("LINK:A", 100.0);
        engine.setEndpointCapacity("LINK:B", 100.0);
        engine.addTransfer(1L, 1000L, java.util.Collections.singletonList("LINK:A"), 100.0, 0.0);
        TransferContentionEngine.AdvanceResult second =
                engine.addTransfer(2L, 1000L, java.util.Collections.singletonList("LINK:B"), 100.0, 0.0);
        assertEquals(100.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        assertEquals(100.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        assertEquals(10.0, second.getNextCompletionTime(), 1.0e-9);
    }

    /**
     * Empty resource set plus unregistered resource key: no contention constraint, so the transfer
     * runs at its nominal rate; null lists/elements are rejected.
     */
    @Test
    public void emptyOrUnregisteredResourcesLeaveNominalRate() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.addTransfer(1L, 1000L, java.util.Collections.<String>emptyList(), 100.0, 0.0);
        engine.addTransfer(2L, 1000L, java.util.Collections.singletonList("LINK:UNREGISTERED"), 100.0, 0.0);
        assertEquals(100.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        assertEquals(100.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        assertThrows(
                IllegalArgumentException.class,
                () -> engine.addTransfer(3L, 1000L, (java.util.List<String>) null, 100.0, 0.0));
        IllegalArgumentException nullElement = assertThrows(
                IllegalArgumentException.class,
                () -> engine.addTransfer(4L, 1000L, java.util.Arrays.asList("VM:0", null), 100.0, 0.0));
        assertTrue(nullElement.getMessage().contains("cannot contain null"), nullElement.getMessage());
    }

    /**
     * Parameter validation: duplicate ID, non-positive bytes, time going backwards, non-positive
     * rate, non-positive capacity.
     */
    @Test
    public void validationRejectsInvalidArguments() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 100.0);
        engine.addTransfer(1L, 100L, "SOURCE", "VM:0", 100.0, 0.0);
        assertThrows(IllegalArgumentException.class, () -> engine.addTransfer(1L, 100L, "SOURCE", "VM:0", 100.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> engine.addTransfer(2L, 0L, "SOURCE", "VM:0", 100.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> engine.addTransfer(2L, 100L, "SOURCE", "VM:0", -1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> engine.advance(-1.0));
        assertThrows(IllegalArgumentException.class, () -> engine.setEndpointCapacity("VM:1", 0.0));
        assertThrows(IllegalArgumentException.class, () -> engine.currentRateBytesPerSecond(999L));
    }
}
