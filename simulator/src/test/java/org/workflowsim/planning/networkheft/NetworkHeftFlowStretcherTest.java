package org.workflowsim.planning.networkheft;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Degenerate-flow checks derived from the edge's declared nominal duration. */
class NetworkHeftFlowStretcherTest {

    @Test
    void zeroBytesWithPositiveNominalDurationUsesTheSuppliedCapacityNotAHardcodedRate() {
        NetworkHeftEdge edge = new NetworkHeftEdge(2, 0.0, 4.0, "shared-bottleneck");
        NetworkHeftTask parent = new NetworkHeftTask(
                1,
                Collections.singletonMap(0, 1.0),
                Collections.<Integer>emptyList(),
                Collections.singletonMap(2, edge));
        NetworkHeftTask child = new NetworkHeftTask(
                2,
                Collections.singletonMap(1, 1.0),
                Collections.singletonList(1),
                Collections.<Integer, NetworkHeftEdge>emptyMap());
        List<NetworkHeftTask> tasks = Arrays.asList(parent, child);
        Map<Integer, Integer> mapping = new LinkedHashMap<Integer, Integer>();
        mapping.put(1, 0);
        mapping.put(2, 1);
        Map<Integer, NetworkHeftSchedule.Entry> entries = new LinkedHashMap<Integer, NetworkHeftSchedule.Entry>();
        entries.put(1, new NetworkHeftSchedule.Entry(1, 0, 0.0, 2.0));
        entries.put(2, new NetworkHeftSchedule.Entry(2, 1, 6.0, 7.0));

        Map<String, Double> stretched = new NetworkHeftFlowStretcher().stretch(tasks, mapping, entries, 50.0);

        // One uncontended flow must retain its declared 4-second duration. Deriving 400
        // bytes from nominalSeconds*100 and then dividing by the supplied 50 B/s yields 8.
        assertEquals(4.0, stretched.get("1->2"), 1.0e-9);
    }
}
