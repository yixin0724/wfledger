package org.workflowsim.planning.dheft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DheftPlannerTest {

    @Test
    void usesMinimumPairwiseBandwidthForCrossVmCommunication() {
        DheftTask first = new DheftTask(
                1,
                values(1.0, 2.0),
                Collections.<Integer>emptyList(),
                Collections.singletonMap(2, new DheftEdge(2, 100.0)));
        DheftTask second = new DheftTask(2, values(5.0, 1.0), Collections.singletonList(1), Collections.emptyMap());
        Map<Integer, Double> bandwidth = new LinkedHashMap<Integer, Double>();
        bandwidth.put(0, 100.0);
        bandwidth.put(1, 50.0);

        DheftSchedule schedule = new DheftPlanner().schedule(new DheftProblem(Arrays.asList(first, second), bandwidth));

        assertEquals(0, schedule.getVmId(1));
        assertEquals(1, schedule.getVmId(2));
        assertEquals(3.0, schedule.getTaskPlan(2).getStartSeconds(), 1.0e-9);
        assertEquals(4.0, schedule.getMakespanSeconds(), 1.0e-9);
    }

    @Test
    void schedulesTheWholeCurrentReadyWaveBeforeReleasingItsChildren() {
        DheftTask first = new DheftTask(
                1,
                values(1.0, 100.0),
                Collections.<Integer>emptyList(),
                Collections.singletonMap(2, new DheftEdge(2, 0.0)));
        DheftTask child = new DheftTask(2, values(10.0, 11.0), Collections.singletonList(1), Collections.emptyMap());
        DheftTask independent =
                new DheftTask(3, values(10.0, 15.0), Collections.<Integer>emptyList(), Collections.emptyMap());
        Map<Integer, Double> bandwidthBytesPerSecond = new LinkedHashMap<Integer, Double>();
        bandwidthBytesPerSecond.put(0, 1.0);
        bandwidthBytesPerSecond.put(1, 1.0);

        DheftSchedule schedule = new DheftPlanner()
                .schedule(new DheftProblem(Arrays.asList(first, child, independent), bandwidthBytesPerSecond));

        assertEquals(0, schedule.getVmId(3));
        assertEquals(1.0, schedule.getTaskPlan(3).getStartSeconds(), 1.0e-9);
        assertEquals(1, schedule.getVmId(2));
        assertEquals(1.0, schedule.getTaskPlan(2).getStartSeconds(), 1.0e-9);
        assertEquals(12.0, schedule.getMakespanSeconds(), 1.0e-9);
    }

    @Test
    void rejectsCycle() {
        DheftTask first = new DheftTask(
                1, values(1.0), Collections.singletonList(2), Collections.singletonMap(2, new DheftEdge(2, 0.0)));
        DheftTask second = new DheftTask(
                2, values(1.0), Collections.singletonList(1), Collections.singletonMap(1, new DheftEdge(1, 0.0)));
        Map<Integer, Double> bandwidth = Collections.singletonMap(0, 100.0);
        assertThrows(IllegalArgumentException.class, () -> new DheftPlanner()
                .schedule(new DheftProblem(Arrays.asList(first, second), bandwidth)));
    }

    private static Map<Integer, Double> values(double... numbers) {
        Map<Integer, Double> result = new LinkedHashMap<Integer, Double>();
        for (int index = 0; index < numbers.length; index++) {
            result.put(index, numbers[index]);
        }
        return result;
    }
}
