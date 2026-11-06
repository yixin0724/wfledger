package org.workflowsim.planning.naco;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Elite-GA sanity: the best result cannot be worse than the HEFT seed. */
class NacoGaHeftOptimizerTest {

    @Test
    void gaKeepsAnEliteSolutionAndProducesACompleteTopologicalSchedule() {
        NacoTask first = new NacoTask(
                1, 0.0, 0.0, compute(2.0, 4.0), Collections.<Integer>emptyList(), Collections.singletonMap(2, 0.0));
        NacoTask second =
                new NacoTask(2, 0.0, 0.0, compute(3.0, 1.0), Collections.singletonList(1), Collections.emptyMap());
        NacoDncProblem problem = new NacoDncProblem(Arrays.asList(first, second), speeds(), speeds());
        NacoSchedule heft = new NacoDncEvaluator().heft(problem);

        NacoSchedule best = new NacoGaHeftOptimizer(problem, 8, 5).optimize();

        assertTrue(best.getMakespanSeconds() <= heft.getMakespanSeconds() + 1.0e-9);
        assertTrue(best.getTaskPlans().size() == 2);
        assertTrue(best.getTaskPlan(1).getFinishSeconds() <= best.getTaskPlan(2).getStartSeconds());
    }

    private static Map<Integer, Double> compute(double... values) {
        Map<Integer, Double> result = new LinkedHashMap<Integer, Double>();
        for (int index = 0; index < values.length; index++) {
            result.put(index, values[index]);
        }
        return result;
    }

    private static Map<Integer, Double> speeds() {
        Map<Integer, Double> result = new LinkedHashMap<Integer, Double>();
        result.put(0, 100.0);
        result.put(1, 100.0);
        return result;
    }
}
