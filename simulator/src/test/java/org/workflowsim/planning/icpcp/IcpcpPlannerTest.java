package org.workflowsim.planning.icpcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class IcpcpPlannerTest {

    @Test
    void choosesLowestCostFeasibleResourceForTheCriticalPath() {
        IcpcpTask first =
                new IcpcpTask(1, values(1.0, 2.0), Collections.<Integer>emptyList(), Collections.singletonMap(2, 0.0));
        IcpcpTask second = new IcpcpTask(2, values(2.0, 1.0), Collections.singletonList(1), Collections.emptyMap());
        Map<Integer, Double> costs = new LinkedHashMap<Integer, Double>();
        costs.put(0, 10.0);
        costs.put(1, 1.0);

        IcpcpSchedule schedule =
                new IcpcpPlanner(new IcpcpProblem(Arrays.asList(first, second), 3.0, 1.0, costs)).schedule();

        assertEquals(1, schedule.getTaskPlan(1).getVmId());
        assertEquals(1, schedule.getTaskPlan(2).getVmId());
        assertEquals(3.0, schedule.getMakespanSeconds(), 1.0e-9);
        assertEquals(3.0, schedule.getTotalCost(), 1.0e-9);
        assertEquals(1, schedule.getAssignedPaths().size());
        assertEquals(Arrays.asList(1, 2), schedule.getAssignedPaths().get(0));
        assertEquals(0.0, schedule.getMinimumSlackSeconds(), 1.0e-9);
    }

    @Test
    void criticalParentIsTheLatestDataArrivalNotTheSmallestSlack() {
        IcpcpTask a =
                new IcpcpTask(1, values(1.0, 1.0), Collections.<Integer>emptyList(), Collections.singletonMap(3, 3.0));
        IcpcpTask b =
                new IcpcpTask(2, values(5.0, 5.0), Collections.<Integer>emptyList(), Collections.singletonMap(3, 0.0));
        IcpcpTask x = new IcpcpTask(3, values(1.0, 1.0), Arrays.asList(1, 2), Collections.<Integer, Double>emptyMap());
        Map<Integer, Double> costs = new LinkedHashMap<Integer, Double>();
        costs.put(0, 2.0);
        costs.put(1, 1.0);

        IcpcpSchedule schedule =
                new IcpcpPlanner(new IcpcpProblem(Arrays.asList(a, b, x), 10.0, 1.0, costs)).schedule();

        assertEquals(Arrays.asList(2, 3), schedule.getAssignedPaths().get(0));
        assertEquals(Arrays.asList(1), schedule.getAssignedPaths().get(1));
        for (int taskId = 1; taskId <= 3; taskId++) {
            assertEquals(1, schedule.getTaskPlan(taskId).getVmId());
        }
        assertEquals(0.0, schedule.getTaskPlan(1).getStartSeconds(), 1.0e-9);
        assertEquals(1.0, schedule.getTaskPlan(2).getStartSeconds(), 1.0e-9);
        assertEquals(6.0, schedule.getTaskPlan(3).getStartSeconds(), 1.0e-9);
        assertEquals(7.0, schedule.getMakespanSeconds(), 1.0e-9);
        assertEquals(7.0, schedule.getTotalCost(), 1.0e-9);
    }

    @Test
    void pathIsRejectedOnAnInstanceWhoseAlreadyPlacedTasksWouldMissTheirLatestFinishTime() {
        IcpcpTask x = new IcpcpTask(
                1, values(4.0, 4.0), Collections.<Integer>emptyList(), Collections.<Integer, Double>emptyMap());
        IcpcpTask y = new IcpcpTask(
                2, values(4.0, 4.0), Collections.<Integer>emptyList(), Collections.<Integer, Double>emptyMap());
        Map<Integer, Double> costs = new LinkedHashMap<Integer, Double>();
        costs.put(0, 1.0);
        costs.put(1, 2.0);

        IcpcpSchedule schedule = new IcpcpPlanner(new IcpcpProblem(Arrays.asList(x, y), 5.0, 1.0, costs)).schedule();

        assertEquals(0, schedule.getTaskPlan(1).getVmId());
        assertEquals(1, schedule.getTaskPlan(2).getVmId());
        assertEquals(0.0, schedule.getTaskPlan(2).getStartSeconds(), 1.0e-9);
        assertEquals(12.0, schedule.getTotalCost(), 1.0e-9);
    }

    @Test
    void failsExplicitlyWhenNoResourceCanMeetTheDeadline() {
        IcpcpTask first =
                new IcpcpTask(1, values(1.0, 2.0), Collections.<Integer>emptyList(), Collections.singletonMap(2, 0.0));
        IcpcpTask second = new IcpcpTask(2, values(2.0, 1.0), Collections.singletonList(1), Collections.emptyMap());
        Map<Integer, Double> costs = new LinkedHashMap<Integer, Double>();
        costs.put(0, 10.0);
        costs.put(1, 1.0);

        assertThrows(
                IllegalArgumentException.class,
                () -> new IcpcpPlanner(new IcpcpProblem(Arrays.asList(first, second), 2.0, 1.0, costs)).schedule());
    }

    @Test
    void schedulesForkJoinAsMultipleCriticalPaths() {
        IcpcpTask first =
                new IcpcpTask(1, values(1.0), Collections.<Integer>emptyList(), new LinkedHashMap<Integer, Double>() {
                    {
                        put(2, 0.0);
                        put(3, 0.0);
                    }
                });
        IcpcpTask second =
                new IcpcpTask(2, values(2.0), Collections.singletonList(1), Collections.singletonMap(4, 0.0));
        IcpcpTask third = new IcpcpTask(3, values(1.0), Collections.singletonList(1), Collections.singletonMap(4, 0.0));
        IcpcpTask fourth = new IcpcpTask(4, values(1.0), Arrays.asList(2, 3), Collections.emptyMap());
        Map<Integer, Double> costs = new LinkedHashMap<Integer, Double>();
        costs.put(0, 1.0);

        IcpcpSchedule schedule = new IcpcpPlanner(
                        new IcpcpProblem(Arrays.asList(first, second, third, fourth), 6.0, 1.0, costs))
                .schedule();

        assertEquals(5.0, schedule.getMakespanSeconds(), 1.0e-9);
        assertEquals(5.0, schedule.getTotalCost(), 1.0e-9);
        assertTrue(schedule.getAssignedPaths().size() >= 2);
        assertEquals(1.0, schedule.getMinimumSlackSeconds(), 1.0e-9);
    }

    @Test
    void fanInWithSharedParentInputRemainsFeasible() {
        Map<Integer, Double> childrenA = new LinkedHashMap<Integer, Double>();
        childrenA.put(2, 0.0133333333333);
        childrenA.put(3, 0.0133333333333);
        IcpcpTask first = new IcpcpTask(1, values(1.0, 2.0), Collections.<Integer>emptyList(), childrenA);
        IcpcpTask second = new IcpcpTask(
                2,
                values(2.0666666667, 4.0666666667),
                Collections.singletonList(1),
                Collections.singletonMap(4, 0.0133333333333));
        IcpcpTask third = new IcpcpTask(
                3,
                values(3.0666666667, 6.0666666667),
                Collections.singletonList(1),
                Collections.singletonMap(4, 0.0133333333333));
        IcpcpTask fourth =
                new IcpcpTask(4, values(1.0666666667, 2.0666666667), Arrays.asList(2, 3), Collections.emptyMap());
        Map<Integer, Double> costs = new LinkedHashMap<Integer, Double>();
        costs.put(0, 1.0);
        costs.put(1, 1.0);

        IcpcpSchedule schedule = new IcpcpPlanner(
                        new IcpcpProblem(Arrays.asList(first, second, third, fourth), 1000.0, 1.0, costs, 0.21))
                .schedule();

        assertTrue(schedule.getMakespanSeconds() < 20.0);
        assertTrue(schedule.getMinimumSlackSeconds() >= 0.0);
    }

    @Test
    void billingPeriodCostUsesTheDeclaredRatePerSecondForTheWholeRoundedPeriod() {
        IcpcpTask only = new IcpcpTask(
                1,
                Collections.singletonMap(0, 2.0),
                Collections.<Integer>emptyList(),
                Collections.<Integer, Double>emptyMap());

        IcpcpSchedule schedule = new IcpcpPlanner(
                        new IcpcpProblem(Collections.singletonList(only), 2.0, 10.0, Collections.singletonMap(0, 3.0)))
                .schedule();

        assertEquals(30.0, schedule.getTotalCost(), 1.0e-9);
    }

    @Test
    void rejectsCycleInsteadOfReturningPartialSchedule() {
        IcpcpTask first = new IcpcpTask(1, values(1.0), Collections.singletonList(2), Collections.singletonMap(2, 0.0));
        IcpcpTask second =
                new IcpcpTask(2, values(1.0), Collections.singletonList(1), Collections.singletonMap(1, 0.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IcpcpPlanner(
                        new IcpcpProblem(Arrays.asList(first, second), 5.0, 1.0, Collections.singletonMap(0, 1.0))));
    }

    private static Map<Integer, Double> values(double... numbers) {
        Map<Integer, Double> result = new LinkedHashMap<Integer, Double>();
        for (int index = 0; index < numbers.length; index++) {
            result.put(index, numbers[index]);
        }
        return result;
    }
}
