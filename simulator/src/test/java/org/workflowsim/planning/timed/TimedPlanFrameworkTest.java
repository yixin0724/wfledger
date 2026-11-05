package org.workflowsim.planning.timed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.planning.cawosched.CaWoSchedVariant;
import org.workflowsim.platform.PlatformProfile;

/** Contract checks for the generic timed-plan API (R-PAPER-01B). */
class TimedPlanFrameworkTest {

    @Test
    void carbonEvaluatorIntegratesTaskAndWindowBoundariesIndependently() {
        TimedActivity activity = new TimedActivity(
                1, 1, TimedActivityType.COMPUTE, 0, 0, 4.0, 5.0, 10.0, 30.0, Collections.<Integer>emptyList());
        List<PlatformProfile.CarbonWindow> windows = Arrays.asList(
                new PlatformProfile.CarbonWindow(0.0, 5.0, 0.0),
                new PlatformProfile.CarbonWindow(5.0, 10.0, 100.0),
                new PlatformProfile.CarbonWindow(10.0, 15.0, 0.0));
        TimedPlan plan = new TimedPlan(Collections.singletonList(activity), windows, 15.0, Collections.emptyMap());
        PlanEvaluation evaluation = new CarbonExcessEvaluator().evaluate(plan);
        assertEquals(100.0, evaluation.getCarbonExcessWattSeconds(), 1.0e-9);
        assertEquals(9.0, evaluation.getMakespanSeconds(), 1.0e-9);
    }

    @Test
    void allSixteenCaWoSchedVariantsUseTheSameGenericOptimizer() {
        TimedActivity activity = new TimedActivity(
                1, 1, TimedActivityType.COMPUTE, 0, 0, 4.0, 0.0, 10.0, 30.0, Collections.<Integer>emptyList());
        List<PlatformProfile.CarbonWindow> windows = Arrays.asList(
                new PlatformProfile.CarbonWindow(0.0, 5.0, 0.0),
                new PlatformProfile.CarbonWindow(5.0, 10.0, 100.0),
                new PlatformProfile.CarbonWindow(10.0, 15.0, 0.0));
        TimedPlan initial = new TimedPlan(Collections.singletonList(activity), windows, 15.0, Collections.emptyMap());
        assertEquals(16, CaWoSchedVariant.values().length);
        for (CaWoSchedVariant variant : CaWoSchedVariant.values()) {
            TimedPlan optimized = variant.optimizer().optimize(initial);
            PlanEvaluation evaluation = new CarbonExcessEvaluator().evaluate(optimized);
            assertEquals(100.0, evaluation.getCarbonExcessWattSeconds(), 1.0e-9, variant.name());
            assertEquals(5.0, optimized.getActivity(1).getStartSeconds(), 1.0e-9, variant.name());
        }
    }

    @Test
    void weightedAndLocalSearchVariantsKeepDeclaredDirections() {
        TimedActivity first = new TimedActivity(
                1, 1, TimedActivityType.COMPUTE, 0, 0, 2.0, 0.0, 0.0, 100.0, Collections.<Integer>emptyList());
        TimedActivity second = new TimedActivity(
                2, 2, TimedActivityType.COMPUTE, 1, 0, 6.0, 0.0, 0.0, 10.0, Collections.<Integer>emptyList());
        List<PlatformProfile.CarbonWindow> windows =
                Collections.singletonList(new PlatformProfile.CarbonWindow(0.0, 20.0, 0.0));
        TimedPlan initial = new TimedPlan(Arrays.asList(first, second), windows, 20.0, Collections.emptyMap());
        TimedPlan unweighted = CaWoSchedVariant.SLACK.optimizer().optimize(initial);
        TimedPlan weighted = CaWoSchedVariant.SLACK_W.optimizer().optimize(initial);
        assertEquals(Arrays.asList(2, 1), unweighted.getDecisionOrderActivityIds());
        assertEquals(Arrays.asList(1, 2), weighted.getDecisionOrderActivityIds());

        TimedPlan localSearch = CaWoSchedVariant.SLACK_LS.optimizer().optimize(initial);
        assertTrue(new CarbonExcessEvaluator().evaluate(localSearch).getCarbonExcessWattSeconds()
                <= new CarbonExcessEvaluator().evaluate(unweighted).getCarbonExcessWattSeconds() + 1.0e-9);
    }
}
