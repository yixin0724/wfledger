package org.workflowsim.planning.cawosched;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.platform.PlatformProfile;

class CaWoSchedPlannerTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void singleTaskSlackSelectsGreenWindowAndMatchesHandComputedCost() {
        CaWoSchedTask task = new CaWoSchedTask(1, 0, 0, 4.0, 10.0, 30.0, Collections.<Integer>emptyList());
        List<PlatformProfile.CarbonWindow> windows = Arrays.asList(
                new PlatformProfile.CarbonWindow(0.0, 5.0, 0.0),
                new PlatformProfile.CarbonWindow(5.0, 10.0, 100.0),
                new PlatformProfile.CarbonWindow(10.0, 15.0, 0.0));
        CaWoSchedPlanner planner = new CaWoSchedPlanner(Collections.singletonList(task), windows, 15.0);

        CaWoSchedPlan asap = planner.planAsap();
        CaWoSchedPlan slack = planner.plan(CaWoSchedPlanner.Score.SLACK);
        CaWoSchedPlan pressure = planner.plan(CaWoSchedPlanner.Score.PRESSURE);

        assertEquals(0.0, asap.getTaskPlan(1).getStartSeconds(), EPSILON);
        assertEquals(180.0, asap.getCarbonExcessWattSeconds(), EPSILON);
        assertEquals(5.0, slack.getTaskPlan(1).getStartSeconds(), EPSILON);
        assertEquals(9.0, slack.getMakespanSeconds(), EPSILON);
        assertEquals(100.0, slack.getCarbonExcessWattSeconds(), EPSILON);
        assertEquals(
                slack.getTaskPlan(1).getStartSeconds(), pressure.getTaskPlan(1).getStartSeconds(), EPSILON);
        assertTrue(slack.getCarbonExcessWattSeconds() < asap.getCarbonExcessWattSeconds());
    }

    @Test
    void pressureUsesPaperRecurrenceForDecisionOrder() {
        CaWoSchedTask first = new CaWoSchedTask(1, 0, 0, 2.0, 0.0, 10.0, Collections.<Integer>emptyList());
        CaWoSchedTask second = new CaWoSchedTask(2, 1, 0, 6.0, 0.0, 10.0, Collections.<Integer>emptyList());
        List<PlatformProfile.CarbonWindow> windows =
                Collections.singletonList(new PlatformProfile.CarbonWindow(0.0, 20.0, 0.0));
        CaWoSchedPlanner planner = new CaWoSchedPlanner(Arrays.asList(first, second), windows, 20.0);

        CaWoSchedPlan pressure = planner.plan(CaWoSchedPlanner.Score.PRESSURE);

        assertEquals(Arrays.asList(2, 1), pressure.getDecisionOrderTaskIds());
        assertEquals(6.0, pressure.getMakespanSeconds(), EPSILON);
        assertEquals(80.0, pressure.getCarbonExcessWattSeconds(), EPSILON);
    }

    @Test
    void greedyKeepsDependenciesAndFixedPerVmOrder() {
        CaWoSchedTask first = new CaWoSchedTask(1, 0, 0, 2.0, 10.0, 30.0, Collections.<Integer>emptyList());
        CaWoSchedTask second = new CaWoSchedTask(2, 0, 1, 3.0, 10.0, 30.0, Collections.singletonList(1));
        List<PlatformProfile.CarbonWindow> windows = Arrays.asList(
                new PlatformProfile.CarbonWindow(0.0, 4.0, 0.0),
                new PlatformProfile.CarbonWindow(4.0, 9.0, 100.0),
                new PlatformProfile.CarbonWindow(9.0, 14.0, 0.0));
        CaWoSchedPlanner planner = new CaWoSchedPlanner(Arrays.asList(first, second), windows, 14.0);

        CaWoSchedPlan plan = planner.plan(CaWoSchedPlanner.Score.PRESSURE);

        assertEquals(Arrays.asList(2, 1), plan.getDecisionOrderTaskIds());
        assertEquals(0.0, plan.getTaskPlan(1).getStartSeconds(), EPSILON);
        assertEquals(2.0, plan.getTaskPlan(1).getFinishSeconds(), EPSILON);
        assertEquals(4.0, plan.getTaskPlan(2).getStartSeconds(), EPSILON);
        assertTrue(plan.getTaskPlan(1).getFinishSeconds() <= plan.getTaskPlan(2).getStartSeconds());
    }

    @Test
    void infeasibleDeadlineAndIncompleteHorizonFailFast() {
        CaWoSchedTask task = new CaWoSchedTask(1, 0, 0, 6.0, 10.0, 30.0, Collections.<Integer>emptyList());
        List<PlatformProfile.CarbonWindow> windows =
                Collections.singletonList(new PlatformProfile.CarbonWindow(0.0, 5.0, 100.0));
        CaWoSchedPlanner infeasible = new CaWoSchedPlanner(Collections.singletonList(task), windows, 5.0);
        assertThrows(IllegalArgumentException.class, infeasible::planAsap);

        CaWoSchedTask shortTask = new CaWoSchedTask(1, 0, 0, 1.0, 10.0, 30.0, Collections.<Integer>emptyList());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CaWoSchedPlanner(Collections.singletonList(shortTask), windows, 6.0));
    }

    @Test
    void cyclicFixedPlanIsRejectedBeforeScheduling() {
        CaWoSchedTask first = new CaWoSchedTask(1, 0, 0, 1.0, 0.0, 10.0, Collections.singletonList(2));
        CaWoSchedTask second = new CaWoSchedTask(2, 1, 0, 1.0, 0.0, 10.0, Collections.singletonList(1));
        List<PlatformProfile.CarbonWindow> windows =
                Collections.singletonList(new PlatformProfile.CarbonWindow(0.0, 5.0, 0.0));
        assertThrows(
                IllegalArgumentException.class, () -> new CaWoSchedPlanner(Arrays.asList(first, second), windows, 5.0));
    }
}
