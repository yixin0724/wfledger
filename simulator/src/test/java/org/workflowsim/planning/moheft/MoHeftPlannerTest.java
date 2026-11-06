package org.workflowsim.planning.moheft;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.workflowsim.planning.greenheft.GreenHeftProblem;
import org.workflowsim.planning.greenheft.GreenHeftTask;

/** Hand-derived MOHEFT Pareto, dominance, and balanced-selection checks. */
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

    private static Map<Integer, Double> values(double... numbers) {
        Map<Integer, Double> result = new LinkedHashMap<Integer, Double>();
        for (int index = 0; index < numbers.length; index++) {
            result.put(index, numbers[index]);
        }
        return result;
    }
}
