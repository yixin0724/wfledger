package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

public class TransferContentionEngineForkTraceTest {

    @Test
    public void forkAfterEveryMutationKeepsTheTraceByteIdentical() {
        TransferTraceSnapshot unforked = unforkedScenario();
        TransferTraceSnapshot forked = forkedScenario();

        assertEquals(unforked.getStatus(), forked.getStatus());
        assertEquals(unforked.getDroppedCount(), forked.getDroppedCount());
        assertEquals(
                Double.doubleToLongBits(unforked.getEngineTime()), Double.doubleToLongBits(forked.getEngineTime()));

        assertEquals(new Gson().toJson(unforked.getEvents()), new Gson().toJson(forked.getEvents()));

        Set<TransferTraceEvent.Type> kinds = new HashSet<TransferTraceEvent.Type>();
        for (TransferTraceEvent event : unforked.getEvents()) {
            kinds.add(event.getType());
        }
        assertEquals(EnumSet.allOf(TransferTraceEvent.Type.class), kinds);
        assertTrue(unforked.getEvents().size() >= 10);
    }

    @Test
    public void budgetOverflowOnAForkDoesNotLeakIntoTheParent() {
        TransferContentionEngine parent = new TransferContentionEngine(3);
        parent.setEndpointCapacity("VM:0", 100.0);
        parent.addTransfer(1L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);
        parent.advance(4.0);
        assertEquals(
                TransferTraceSnapshot.Status.COMPLETE, parent.getTraceSnapshot().getStatus());
        assertEquals(3, parent.getTraceSnapshot().getEvents().size());

        TransferContentionEngine child = parent.fork();

        child.addTransfer(2L, 100L, "SOURCE", "VM:0", 100.0, 4.0);
        TransferTraceSnapshot childTrace = child.getTraceSnapshot();
        assertEquals(TransferTraceSnapshot.Status.TRUNCATED, childTrace.getStatus());
        assertEquals(2L, childTrace.getDroppedCount());
        assertEquals(3, childTrace.getEvents().size());

        TransferTraceSnapshot parentTrace = parent.getTraceSnapshot();
        assertEquals(TransferTraceSnapshot.Status.COMPLETE, parentTrace.getStatus());
        assertEquals(0L, parentTrace.getDroppedCount());
        assertEquals(3, parentTrace.getEvents().size());
    }

    @Test
    public void forkSharesTheRetainedTracePrefixWithoutCopyingIt() {
        TransferContentionEngine engine = new TransferContentionEngine(8);
        engine.setEndpointCapacity("VM:0", 100.0);
        engine.addTransfer(1L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);
        Object prefixAtFork = engine.traceHeadIdentity();
        TransferContentionEngine fork = engine.fork();

        assertSame(prefixAtFork, fork.traceHeadIdentity());

        engine.advance(4.0);
        assertSame(prefixAtFork, fork.traceHeadIdentity());
        Object parentHeadAfterAppend = engine.traceHeadIdentity();
        assertNotSame(prefixAtFork, parentHeadAfterAppend);

        fork.advance(4.0);
        assertSame(parentHeadAfterAppend, engine.traceHeadIdentity());
        assertNotSame(parentHeadAfterAppend, fork.traceHeadIdentity());

        assertSame(fork.traceHeadIdentity(), fork.fork().traceHeadIdentity());
    }

    @Test
    public void repeatedForksOfALargeRetainedHistoryShareTheSamePrefix() {
        TransferContentionEngine engine = new TransferContentionEngine(3000);
        engine.setEndpointCapacity("VM:0", 100.0);
        for (long id = 0; id < 2900; id++) {

            engine.addTransfer(id, 1L, "SOURCE", "DST:" + id, 1.0, 0.0);
        }
        assertEquals(2901, engine.getTraceSnapshot().getEvents().size());
        Object head = engine.traceHeadIdentity();
        for (int i = 0; i < 600; i++) {
            assertSame(head, engine.fork().traceHeadIdentity());
        }
    }

    private static TransferTraceSnapshot unforkedScenario() {
        TransferContentionEngine engine = new TransferContentionEngine(64);
        engine.setEndpointCapacity("VM:0", 100.0);
        engine.addTransfer(1L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);
        engine.advance(4.0);
        engine.addTransfer(2L, 500L, "SOURCE", "VM:0", 100.0, 4.0);
        engine.advance(12.0);
        engine.advance(16.0);
        engine.setEndpointCapacity("VM:0", 200.0);
        return engine.getTraceSnapshot();
    }

    private static TransferTraceSnapshot forkedScenario() {
        TransferContentionEngine engine = new TransferContentionEngine(64);
        engine.setEndpointCapacity("VM:0", 100.0);
        engine = engine.fork();
        engine.addTransfer(1L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);
        engine = engine.fork();
        engine.advance(4.0);
        engine = engine.fork();
        engine.addTransfer(2L, 500L, "SOURCE", "VM:0", 100.0, 4.0);
        engine = engine.fork();
        engine.advance(12.0);
        engine = engine.fork();
        engine.advance(16.0);
        engine = engine.fork();
        engine.setEndpointCapacity("VM:0", 200.0);
        return engine.getTraceSnapshot();
    }
}
