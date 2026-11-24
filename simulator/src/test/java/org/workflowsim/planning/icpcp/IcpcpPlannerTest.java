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

    /**
     * FGCS-R3 (2026-11-24) golden (b), fully hand-derived because the worked example of Abrishami et al.
     * (FGCS 29(1), 2013) is not obtainable here. The critical parent of a task is its unassigned parent
     * with the latest data arrival time {@code EFT(p) + TT(p,t)}, not the parent with the smallest slack.
     *
     * <p>Graph: roots A=1 (w=1, TT(A,X)=3) and B=2 (w=5, TT(B,X)=0) feed exit X=3 (w=1); identical
     * durations on VM0 (rate 2/s) and VM1 (rate 1/s); deadline 10; billing period 1 s. Initially
     * {@code EFT(A)=1}, {@code EFT(B)=5}; arrivals at X are A: 1+3=4, B: 5+0=5, so the critical parent is
     * B and the first partial critical path is [B, X]. (Smallest slack {@code LFT-EST} would pick A:
     * {@code LFT(A)=10-1-3=6} versus {@code LFT(B)=9}.)
     *
     * <p>Path [B, X]: VM0 costs 6 s x 2 = 12, VM1 costs 6 s x 1 = 6, so VM1: B [0,5], X [5,6]. Path [A]:
     * on VM1 before B, A [0,1], B [1,6], X [6,7]; LFTs are A=min(10-1-0, 9-5)=4, B=9, X=10, all met;
     * VM1 span 7 gives delta cost 1. On VM1 between B and X the delta is also 1 (tie, the earlier
     * position wins); after X it would contradict A->X. On VM0 alone A costs 1 x 2 = 2. Result: all on
     * VM1, makespan 7, cost 7.
     */
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

    /**
     * FGCS-R3 (2026-11-24) M2: a path is applicable on an instance only if every task already on that
     * instance still finishes by its LFT. Hand derivation: independent exits X=1 and Y=2 (w=4 on both VMs),
     * deadline 5, VM0 rate 1/s and VM1 rate 2/s. END's parents tie on arrival 4, so X goes first and takes
     * VM0 [0,4] (cost 4 versus 8). Y on VM0 would finish at 8 either before X (pushing X to 8 > LFT 5) or
     * after it; both are inapplicable, so Y must use VM1 [0,4]: total cost 4 + 8 = 12.
     */
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
