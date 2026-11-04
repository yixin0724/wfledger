package org.cloudbus.cloudsim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

/**
 * CloudSim 3.0.3 reference semantics from commit 46c46604a021c4cbb54a4dac812e91f89d6c495c.
 * Expected values are derived from FutureQueue.addEvent, SimEvent.compareTo, and CloudSim.send,
 * not from an implementation-generated golden.
 */
class CloudSimKernelReferenceTest {

    @Test
    void sameTimeEventsUseInsertionSerialAndShortSendsRemainQueued() throws Exception {
        FutureQueue queue = new FutureQueue();
        SimEvent later = new SimEvent(SimEvent.SEND, 2.0, 0, 1, 20, null);
        SimEvent earlier = new SimEvent(SimEvent.SEND, 1.0, 0, 1, 10, null);
        SimEvent sameTime = new SimEvent(SimEvent.SEND, 2.0, 0, 1, 30, null);
        queue.addEvent(later);
        queue.addEvent(earlier);
        queue.addEvent(sameTime);
        List<Integer> tags = new ArrayList<Integer>();
        Iterator<SimEvent> iterator = queue.iterator();
        while (iterator.hasNext()) {
            tags.add(Integer.valueOf(iterator.next().getTag()));
        }
        assertEquals(Arrays.asList(10, 20, 30), tags);

        try (SimulationSession session = SimulationSession.open(
                SimulationConfig.builder("kernel-reference.dax", 1).build())) {
            session.initializeCloudSim(1, null, false);
            CloudSim.send(0, 1, 0.01, 42, null);
            SimEvent sent = CloudSim.future.iterator().next();
            assertEquals(0.01, sent.eventTime(), 0.0);
            assertEquals(42, sent.getTag());
        }
    }
}
