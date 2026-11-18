package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TransferContentionEngineTest {

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

    @Test
    public void twoConcurrentTransfersShareDestinationEndpoint() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 100.0);
        engine.addTransfer(1L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);
        TransferContentionEngine.AdvanceResult second = engine.addTransfer(2L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);

        assertEquals(20.0, second.getNextCompletionTime(), 1.0e-9);
        assertEquals(50.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        assertEquals(50.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);

        TransferContentionEngine.AdvanceResult midway = engine.advance(5.0);
        assertTrue(midway.getCompletedTransferIds().isEmpty());
        assertEquals(20.0, midway.getNextCompletionTime(), 1.0e-9);

        TransferContentionEngine.AdvanceResult done = engine.advance(20.0);
        assertEquals(2, done.getCompletedTransferIds().size());
        assertNull(done.getNextCompletionTime());
    }

    @Test
    public void survivingTransferSpeedsUpWhenCompetitorFinishes() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 100.0);

        engine.addTransfer(1L, 500L, "SOURCE", "VM:0", 100.0, 0.0);
        engine.addTransfer(2L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);

        TransferContentionEngine.AdvanceResult done = engine.advance(10.0);
        assertEquals(1, done.getCompletedTransferIds().size());
        assertEquals(Long.valueOf(1L), done.getCompletedTransferIds().get(0));

        assertEquals(100.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        assertEquals(15.0, done.getNextCompletionTime(), 1.0e-9);
        TransferContentionEngine.AdvanceResult finalDone = engine.advance(15.0);
        assertEquals(1, finalDone.getCompletedTransferIds().size());
        assertEquals(Long.valueOf(2L), finalDone.getCompletedTransferIds().get(0));
    }

    @Test
    public void vmToVmTransferLoadsBothEndpoints() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 200.0);
        engine.setEndpointCapacity("VM:1", 100.0);

        engine.addTransfer(1L, 1000L, "VM:0", "VM:1", 100.0, 0.0);

        TransferContentionEngine.AdvanceResult second = engine.addTransfer(2L, 1000L, "VM:0", "VM:2", 100.0, 0.0);

        assertEquals(100.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        assertEquals(100.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        assertEquals(10.0, second.getNextCompletionTime(), 1.0e-9);
    }

    @Test
    public void lateArrivalSlowsExistingTransfer() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 100.0);
        engine.addTransfer(1L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);

        TransferContentionEngine.AdvanceResult second = engine.addTransfer(2L, 1000L, "SOURCE", "VM:0", 100.0, 4.0);
        assertTrue(second.getCompletedTransferIds().isEmpty());

        assertEquals(16.0, second.getNextCompletionTime(), 1.0e-9);
        TransferContentionEngine.AdvanceResult firstDone = engine.advance(16.0);
        assertEquals(1, firstDone.getCompletedTransferIds().size());
        assertEquals(Long.valueOf(1L), firstDone.getCompletedTransferIds().get(0));

        assertEquals(100.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        assertEquals(20.0, firstDone.getNextCompletionTime(), 1.0e-9);
        TransferContentionEngine.AdvanceResult done = engine.advance(20.0);
        assertEquals(1, done.getCompletedTransferIds().size());
        assertEquals(Long.valueOf(2L), done.getCompletedTransferIds().get(0));
    }

    @Test
    public void threeConcurrentTransfersSplitCapacityEqually() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 90.0);
        engine.addTransfer(1L, 900L, "SOURCE", "VM:0", 90.0, 0.0);
        engine.addTransfer(2L, 900L, "SOURCE", "VM:0", 90.0, 0.0);
        TransferContentionEngine.AdvanceResult third = engine.addTransfer(3L, 900L, "SOURCE", "VM:0", 90.0, 0.0);

        assertEquals(30.0, third.getNextCompletionTime(), 1.0e-9);
        assertEquals(30.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        TransferContentionEngine.AdvanceResult done = engine.advance(30.0);
        assertEquals(3, done.getCompletedTransferIds().size());
    }

    @Test
    public void nominalRateCapsTransferBelowEndpointShare() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 1000.0);
        engine.addTransfer(1L, 500L, "SOURCE", "VM:0", 50.0, 0.0);
        assertEquals(50.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
    }

    @Test
    public void resourceSetTransfersShareSingleLink() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("LINK:CORE:0", 100.0);
        engine.setEndpointCapacity("VM:0", 1000.0);
        engine.setEndpointCapacity("VM:1", 1000.0);
        engine.addTransfer(1L, 1000L, java.util.Arrays.asList("VM:0", "VM:1", "LINK:CORE:0"), 100.0, 0.0);
        TransferContentionEngine.AdvanceResult second =
                engine.addTransfer(2L, 1000L, java.util.Arrays.asList("VM:2", "VM:3", "LINK:CORE:0"), 100.0, 0.0);

        assertEquals(50.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        assertEquals(50.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        assertEquals(20.0, second.getNextCompletionTime(), 1.0e-9);
    }

    @Test
    public void resourceSetRateIsMinAcrossEndpointsAndLinks() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:1", 60.0);
        engine.setEndpointCapacity("LINK:CORE:0", 100.0);
        engine.addTransfer(1L, 600L, java.util.Arrays.asList("VM:0", "VM:1", "LINK:CORE:0"), 100.0, 0.0);

        assertEquals(60.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        assertEquals(10.0, engine.advance(0.0).getNextCompletionTime(), 1.0e-9);
    }

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
