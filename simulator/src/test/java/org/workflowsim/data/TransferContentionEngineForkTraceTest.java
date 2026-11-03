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

/**
 * Trace behavior across engine forks (AUD-F04 pinning): a fork-storm run must keep the
 * captured trace byte-identical to an unforked run, and capture state must stay isolated
 * between parent and fork at the record budget.
 */
public class TransferContentionEngineForkTraceTest {

    /**
     * The retained trace of a fork-after-every-mutation run must serialize exactly like the
     * unforked run's trace: same status, dropped count, engine clock, and the same event list
     * field by field, so that sharing the retained prefix can never change the evidence.
     */
    @Test
    public void forkAfterEveryMutationKeepsTheTraceByteIdentical() {
        TransferTraceSnapshot unforked = unforkedScenario();
        TransferTraceSnapshot forked = forkedScenario();

        assertEquals(unforked.getStatus(), forked.getStatus());
        assertEquals(unforked.getDroppedCount(), forked.getDroppedCount());
        assertEquals(
                Double.doubleToLongBits(unforked.getEngineTime()), Double.doubleToLongBits(forked.getEngineTime()));
        // Full-content comparison: Gson walks every field of every event and its typed
        // payload, so equality of these strings is equality of the captured evidence.
        assertEquals(new Gson().toJson(unforked.getEvents()), new Gson().toJson(forked.getEvents()));

        // The pinned scenario must stay non-vacuous: all five event kinds really occur.
        Set<TransferTraceEvent.Type> kinds = new HashSet<TransferTraceEvent.Type>();
        for (TransferTraceEvent event : unforked.getEvents()) {
            kinds.add(event.getType());
        }
        assertEquals(EnumSet.allOf(TransferTraceEvent.Type.class), kinds);
        assertTrue(unforked.getEvents().size() >= 10);
    }

    /**
     * Overflow drops on a fork must stay local to the fork: the parent's COMPLETE status and
     * zero dropped count are untouched by the child exceeding the shared budget.
     */
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
        // The shared budget is already full, so the child's next admission drops both true
        // events it produces: the surviving flow's RATE_CHANGE (100 -> 50 after sharing) and
        // the new admission's START.
        child.addTransfer(2L, 100L, "SOURCE", "VM:0", 100.0, 4.0);
        TransferTraceSnapshot childTrace = child.getTraceSnapshot();
        assertEquals(TransferTraceSnapshot.Status.TRUNCATED, childTrace.getStatus());
        assertEquals(2L, childTrace.getDroppedCount());
        assertEquals(3, childTrace.getEvents().size());

        // The parent neither loses its prefix nor inherits the child's drop.
        TransferTraceSnapshot parentTrace = parent.getTraceSnapshot();
        assertEquals(TransferTraceSnapshot.Status.COMPLETE, parentTrace.getStatus());
        assertEquals(0L, parentTrace.getDroppedCount());
        assertEquals(3, parentTrace.getEvents().size());
    }

    /**
     * A fork must share the immutable retained prefix instead of copying it, and appends on
     * either side must leave the other side's prefix identity untouched. Sharing the prefix
     * is what keeps a fork independent of the retained history length (AUD-F04 complexity
     * assertion).
     */
    @Test
    public void forkSharesTheRetainedTracePrefixWithoutCopyingIt() {
        TransferContentionEngine engine = new TransferContentionEngine(8);
        engine.setEndpointCapacity("VM:0", 100.0);
        engine.addTransfer(1L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);
        Object prefixAtFork = engine.traceHeadIdentity();
        TransferContentionEngine fork = engine.fork();
        // The fork must share the immutable prefix: same identity, no copy of the history.
        assertSame(prefixAtFork, fork.traceHeadIdentity());
        // A parent append must not rewrite the prefix the fork shares.
        engine.advance(4.0);
        assertSame(prefixAtFork, fork.traceHeadIdentity());
        Object parentHeadAfterAppend = engine.traceHeadIdentity();
        assertNotSame(prefixAtFork, parentHeadAfterAppend);
        // A fork append must not touch the parent head either.
        fork.advance(4.0);
        assertSame(parentHeadAfterAppend, engine.traceHeadIdentity());
        assertNotSame(parentHeadAfterAppend, fork.traceHeadIdentity());
        // A fork of the fork shares the fork's current head.
        assertSame(fork.traceHeadIdentity(), fork.fork().traceHeadIdentity());
    }

    /**
     * A fork storm over a large retained history must share the same newest-record identity
     * every time: the per-fork cost may not grow with the retained event count.
     */
    @Test
    public void repeatedForksOfALargeRetainedHistoryShareTheSamePrefix() {
        TransferContentionEngine engine = new TransferContentionEngine(3000);
        engine.setEndpointCapacity("VM:0", 100.0);
        for (long id = 0; id < 2900; id++) {
            // Distinct unregistered destinations keep every flow uncontended, and admitting at
            // the engine clock (rather than at increasing times) settles no service interval,
            // so each admission contributes exactly one START to the retained history.
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
