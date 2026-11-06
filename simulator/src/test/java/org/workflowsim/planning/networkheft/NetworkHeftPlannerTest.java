package org.workflowsim.planning.networkheft;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Paper Figure 4/6/8 Network-Aware HEFT oracle: nominal 25, stretched 32. */
class NetworkHeftPlannerTest {

    @Test
    void paperExampleStretchesTheSharedBottleneckFrom25To32Seconds() {
        NetworkHeftProblem problem = paperProblem();
        NetworkHeftSchedule schedule = new NetworkHeftPlanner().schedule(problem, 1);

        assertEquals(32.0, schedule.getMakespanSeconds(), 1.0e-9);
        assertEquals(0, schedule.getVmId(1));
        assertEquals(2, schedule.getVmId(2));
        assertEquals(0, schedule.getVmId(3));
        assertEquals(0, schedule.getVmId(4));
        assertEquals(1, schedule.getVmId(5));
        assertEquals(1, schedule.getVmId(6));
    }

    private static NetworkHeftProblem paperProblem() {
        NetworkHeftTask first = task(
                1,
                values(3.0, 5.0, 7.0),
                Collections.<Integer>emptyList(),
                edges(edge(2, 300.0, 3.0), edge(3, 700.0, 7.0), edge(4, 500.0, 5.0)));
        NetworkHeftTask second =
                task(2, values(4.0, 8.0, 3.0), Collections.singletonList(1), edges(edge(5, 500.0, 5.0)));
        NetworkHeftTask third =
                task(3, values(3.0, 4.0, 11.0), Collections.singletonList(1), edges(edge(5, 700.0, 7.0)));
        NetworkHeftTask fourth =
                task(4, values(5.0, 8.0, 2.0), Collections.singletonList(1), edges(edge(6, 300.0, 3.0)));
        NetworkHeftTask fifth = task(5, values(10.0, 3.0, 5.0), Arrays.asList(2, 3), edges(edge(6, 700.0, 7.0)));
        NetworkHeftTask sixth = task(6, values(5.0, 8.0, 8.0), Arrays.asList(4, 5), Collections.emptyMap());
        return new NetworkHeftProblem(Arrays.asList(first, second, third, fourth, fifth, sixth), 100.0);
    }

    private static NetworkHeftTask task(
            int id, Map<Integer, Double> compute, List<Integer> parents, Map<Integer, NetworkHeftEdge> edges) {
        return new NetworkHeftTask(id, compute, parents, edges);
    }

    private static Map<Integer, NetworkHeftEdge> edges(NetworkHeftEdge... edges) {
        Map<Integer, NetworkHeftEdge> result = new LinkedHashMap<Integer, NetworkHeftEdge>();
        for (NetworkHeftEdge edge : edges) {
            result.put(edge.getChildTaskId(), edge);
        }
        return result;
    }

    private static NetworkHeftEdge edge(int child, double bytes, double seconds) {
        return new NetworkHeftEdge(child, bytes, seconds, "shared-bottleneck");
    }

    private static Map<Integer, Double> values(double... numbers) {
        Map<Integer, Double> result = new LinkedHashMap<Integer, Double>();
        for (int index = 0; index < numbers.length; index++) {
            result.put(index, numbers[index]);
        }
        return result;
    }
}
