package org.workflowsim.planning.moheft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.workflowsim.planning.greenheft.GreenHeftProblem;
import org.workflowsim.planning.greenheft.GreenHeftTask;

class MoHeftPlannerTest {

    @Test
    void removesDominatedCandidateAndKeepsBothParetoExtremes() {
        GreenHeftTask task = new GreenHeftTask(
                1,
                values(2.0, 4.0, 3.0),
                values(10.0, 8.0, 12.0),
                Collections.<Integer>emptyList(),
                Collections.emptyMap());

        MoHeftResult result = new MoHeftPlanner(4).schedule(new GreenHeftProblem(Collections.singletonList(task)));

        assertEquals(2, result.getParetoFront().size());
        assertEquals(0, result.getBalancedSchedule().getTaskPlan(1).getVmId());
        assertEquals(10.0, result.getBalancedSchedule().getTotalEnergyJoules(), 1.0e-9);
    }

    @Test
    void crowdingTruncationKeepsMakespanAndEnergyExtremes() {
        GreenHeftTask task = new GreenHeftTask(
                1,
                values(1.0, 2.0, 3.0),
                values(3.0, 2.0, 1.0),
                Collections.<Integer>emptyList(),
                Collections.emptyMap());

        MoHeftResult result = new MoHeftPlanner(2).schedule(new GreenHeftProblem(Collections.singletonList(task)));

        assertEquals(2, result.getParetoFront().size());
        assertEquals(0, result.getParetoFront().get(0).getTaskPlan(1).getVmId());
        assertEquals(2, result.getParetoFront().get(1).getTaskPlan(1).getVmId());
        assertEquals(0, result.getBalancedSchedule().getTaskPlan(1).getVmId());
    }

    @Test
    void paretoExpansionIncludesEarlierGapInsertion() {
        GreenHeftTask parent = new GreenHeftTask(
                1,
                values(5.0, 5.0),
                values(50.0, 5.0),
                Collections.<Integer>emptyList(),
                Collections.singletonMap(2, 5.0));
        GreenHeftTask child = new GreenHeftTask(
                2, values(5.0, 5.0), values(1.0, 100.0), Collections.singletonList(1), Collections.emptyMap());
        GreenHeftTask independent = new GreenHeftTask(
                3, values(2.0, 2.0), values(1.0, 100.0), Collections.<Integer>emptyList(), Collections.emptyMap());

        MoHeftResult result =
                new MoHeftPlanner(8).schedule(new GreenHeftProblem(Arrays.asList(parent, child, independent)));

        boolean found = false;
        for (MoHeftSchedule schedule : result.getParetoFront()) {
            if (Math.abs(schedule.getTotalEnergyJoules() - 7.0) < 1.0e-9
                    && Math.abs(schedule.getMakespanSeconds() - 15.0) < 1.0e-9) {
                found = true;
            }
        }
        assertTrue(found, "The unique 7 J candidate must use the legal earlier gap");
    }

    @Test
    void beamIsRefilledFromLaterFrontsWhenTheFirstFrontIsSmallerThanTheBeam() {
        GreenHeftTask first = new GreenHeftTask(
                1, values(1.0, 1.5), values(1.0, 1.5), Collections.<Integer>emptyList(), Collections.emptyMap());
        GreenHeftTask second = new GreenHeftTask(
                2, values(1.0, 1.5), values(1.0, 5.0), Collections.<Integer>emptyList(), Collections.emptyMap());

        MoHeftResult result = new MoHeftPlanner(2).schedule(new GreenHeftProblem(Arrays.asList(first, second)));

        assertEquals(2, result.getParetoFront().size());
        boolean hasA = false;
        boolean hasC = false;
        for (MoHeftSchedule schedule : result.getParetoFront()) {
            hasA |= schedule.getMakespanSeconds() == 2.0 && schedule.getTotalEnergyJoules() == 2.0;
            hasC |= schedule.getMakespanSeconds() == 1.5 && schedule.getTotalEnergyJoules() == 2.5;
        }
        assertTrue(hasA && hasC, "front must be {(2 s, 2 J), (1.5 s, 2.5 J)}");
        assertEquals(1, result.getBalancedSchedule().getTaskPlan(1).getVmId());
        assertEquals(0, result.getBalancedSchedule().getTaskPlan(2).getVmId());
    }

    @Test
    void insertionDoesNotAdmitATaskThatOverrunsTheGapByLessThanTheOldTolerance() {
        double overrun = 10.0 + 5.0e-10;
        GreenHeftTask parent = new GreenHeftTask(
                1,
                values(5.0, 5.0),
                values(50.0, 5.0),
                Collections.<Integer>emptyList(),
                Collections.singletonMap(2, 5.0));
        GreenHeftTask child = new GreenHeftTask(
                2, values(12.0, 12.0), values(1.0, 100.0), Collections.singletonList(1), Collections.emptyMap());
        GreenHeftTask independent = new GreenHeftTask(
                3,
                values(overrun, overrun),
                values(1.0, 100.0),
                Collections.<Integer>emptyList(),
                Collections.emptyMap());

        MoHeftResult result =
                new MoHeftPlanner(8).schedule(new GreenHeftProblem(Arrays.asList(parent, child, independent)));

        MoHeftSchedule minimumEnergy = null;
        for (MoHeftSchedule schedule : result.getParetoFront()) {
            if (minimumEnergy == null || schedule.getTotalEnergyJoules() < minimumEnergy.getTotalEnergyJoules()) {
                minimumEnergy = schedule;
            }
        }
        assertEquals(7.0, minimumEnergy.getTotalEnergyJoules(), 0.0);
        assertEquals(22.0, minimumEnergy.getTaskPlan(3).getStartSeconds(), 0.0);
    }

    private static Map<Integer, Double> values(double... numbers) {
        Map<Integer, Double> result = new LinkedHashMap<Integer, Double>();
        for (int index = 0; index < numbers.length; index++) {
            result.put(index, numbers[index]);
        }
        return result;
    }
}
