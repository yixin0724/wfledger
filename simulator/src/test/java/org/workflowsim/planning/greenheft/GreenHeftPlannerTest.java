package org.workflowsim.planning.greenheft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Hand-derived GreenHEFT energy-first mapping oracle. */
class GreenHeftPlannerTest {

    @Test
    void choosesMinimumEnergyThenFinishTime() {
        GreenHeftTask first = new GreenHeftTask(
                1, values(2.0, 4.0), values(10.0, 8.0), Collections.<Integer>emptyList(), Collections.emptyMap());
        GreenHeftTask second = new GreenHeftTask(
                2, values(3.0, 1.0), values(6.0, 2.0), Collections.<Integer>emptyList(), Collections.emptyMap());

        GreenHeftSchedule schedule =
                new GreenHeftPlanner().schedule(new GreenHeftProblem(Arrays.asList(first, second)));

        assertEquals(1, schedule.getTaskPlan(1).getVmId());
        assertEquals(1, schedule.getTaskPlan(2).getVmId());
        assertEquals(10.0, schedule.getTotalEnergyJoules(), 1.0e-9);
        assertEquals(5.0, schedule.getMakespanSeconds(), 1.0e-9);
    }

    @Test
    void energyTieUsesFinishTime() {
        GreenHeftTask task = new GreenHeftTask(
                1, values(2.0, 1.0), values(1.0, 1.0), Collections.<Integer>emptyList(), Collections.emptyMap());
        GreenHeftSchedule schedule =
                new GreenHeftPlanner().schedule(new GreenHeftProblem(Collections.singletonList(task)));
        assertEquals(1, schedule.getTaskPlan(1).getVmId());
        assertEquals(1.0, schedule.getMakespanSeconds(), 1.0e-9);
    }

    @Test
    void dependencyCommunicationDelaysTheChild() {
        GreenHeftTask parent = new GreenHeftTask(
                1,
                values(1.0, 2.0),
                values(1.0, 2.0),
                Collections.<Integer>emptyList(),
                Collections.singletonMap(2, 0.5));
        GreenHeftTask child = new GreenHeftTask(
                2, values(2.0, 1.0), values(1.0, 1.0), Collections.singletonList(1), Collections.emptyMap());
        GreenHeftSchedule schedule =
                new GreenHeftPlanner().schedule(new GreenHeftProblem(Arrays.asList(parent, child)));
        assertEquals(0, schedule.getTaskPlan(1).getVmId());
        assertEquals(1, schedule.getTaskPlan(2).getVmId());
        assertEquals(1.5, schedule.getTaskPlan(2).getStartSeconds(), 1.0e-9);
        assertEquals(2.5, schedule.getMakespanSeconds(), 1.0e-9);
    }

    @Test
    void rejectsACycleInsteadOfReturningAnInvalidSchedule() {
        GreenHeftTask first = new GreenHeftTask(
                1, values(1.0), values(1.0), Collections.singletonList(2), Collections.singletonMap(2, 0.0));
        GreenHeftTask second = new GreenHeftTask(
                2, values(1.0), values(1.0), Collections.singletonList(1), Collections.singletonMap(1, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new GreenHeftPlanner()
                .schedule(new GreenHeftProblem(Arrays.asList(first, second))));
    }

    private static Map<Integer, Double> values(double... numbers) {
        Map<Integer, Double> result = new LinkedHashMap<Integer, Double>();
        for (int index = 0; index < numbers.length; index++) {
            result.put(index, numbers[index]);
        }
        return result;
    }
}
