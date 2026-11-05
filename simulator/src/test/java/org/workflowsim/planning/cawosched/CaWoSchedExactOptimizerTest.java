package org.workflowsim.planning.cawosched;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.planning.timed.CarbonExcessEvaluator;
import org.workflowsim.planning.timed.TimedActivity;
import org.workflowsim.planning.timed.TimedActivityType;
import org.workflowsim.planning.timed.TimedPlan;
import org.workflowsim.platform.PlatformProfile;

/** Exact single-processor DP versus independent integer-start enumeration (R-PAPER-01D). */
class CaWoSchedExactOptimizerTest {

    @Test
    void exactSingleProcessorPlanMatchesIndependentEnumeration() {
        CaWoSchedTask first = new CaWoSchedTask(1, 0, 0, 2.0, 10.0, 30.0, Collections.<Integer>emptyList());
        CaWoSchedTask second = new CaWoSchedTask(2, 0, 1, 1.0, 10.0, 30.0, Collections.singletonList(1));
        List<PlatformProfile.CarbonWindow> windows = Arrays.asList(
                new PlatformProfile.CarbonWindow(0.0, 3.0, 0.0),
                new PlatformProfile.CarbonWindow(3.0, 6.0, 100.0),
                new PlatformProfile.CarbonWindow(6.0, 9.0, 0.0),
                new PlatformProfile.CarbonWindow(9.0, 12.0, 100.0),
                new PlatformProfile.CarbonWindow(12.0, 15.0, 0.0));
        CaWoSchedPlanner planner = new CaWoSchedPlanner(Arrays.asList(first, second), windows, 15.0);

        CaWoSchedPlan exact = planner.planExactSingleProcessor();
        double expected = enumerateOptimalCost(first, second, windows);

        assertEquals(expected, exact.getCarbonExcessWattSeconds(), 1.0e-9);
        assertTrue(
                exact.getTaskPlan(1).getFinishSeconds() <= exact.getTaskPlan(2).getStartSeconds());
    }

    private static double enumerateOptimalCost(
            CaWoSchedTask first, CaWoSchedTask second, List<PlatformProfile.CarbonWindow> windows) {
        double best = Double.POSITIVE_INFINITY;
        for (int firstStart = 0; firstStart <= 13; firstStart++) {
            for (int secondStart = firstStart + 2; secondStart <= 14; secondStart++) {
                TimedActivity a = new TimedActivity(
                        1,
                        1,
                        TimedActivityType.COMPUTE,
                        0,
                        0,
                        2.0,
                        firstStart,
                        10.0,
                        30.0,
                        Collections.<Integer>emptyList());
                TimedActivity b = new TimedActivity(
                        2,
                        2,
                        TimedActivityType.COMPUTE,
                        0,
                        1,
                        1.0,
                        secondStart,
                        10.0,
                        30.0,
                        Collections.singletonList(1));
                TimedPlan plan = new TimedPlan(Arrays.asList(a, b), windows, 15.0, Collections.emptyMap());
                best = Math.min(best, new CarbonExcessEvaluator().evaluate(plan).getCarbonExcessWattSeconds());
            }
        }
        return best;
    }
}
