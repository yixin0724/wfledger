package org.workflowsim.planning.timed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.planning.cawosched.CaWoSchedVariant;
import org.workflowsim.platform.PlatformProfile;

class CommunicationEnhancedPlanTest {

    @Test
    void crossVmDependencyBecomesOrderedCommunicationActivity() {
        TimedActivity first = new TimedActivity(
                1, 1, TimedActivityType.COMPUTE, 0, 0, 2.0, 0.0, 10.0, 30.0, Collections.<Integer>emptyList());
        TimedActivity second = new TimedActivity(
                2, 2, TimedActivityType.COMPUTE, 1, 0, 3.0, 2.0, 10.0, 30.0, Collections.singletonList(1));
        List<PlatformProfile.CarbonWindow> windows = Arrays.asList(
                new PlatformProfile.CarbonWindow(0.0, 5.0, 0.0),
                new PlatformProfile.CarbonWindow(5.0, 10.0, 100.0),
                new PlatformProfile.CarbonWindow(10.0, 15.0, 0.0));
        TimedPlan computePlan = new TimedPlan(Arrays.asList(first, second), windows, 15.0, Collections.emptyMap());
        CaWoSchedCommunicationSpec link =
                new CaWoSchedCommunicationSpec(0, 1, 99, 2.0, 12.0, Collections.singletonMap("1->2", 2.0));

        TimedPlan enhanced = CommunicationEnhancedPlan.fromComputePlan(computePlan, Collections.singletonList(link));
        TimedActivity communication = enhanced.getActivity(3);

        assertEquals(TimedActivityType.COMMUNICATION, communication.getType());
        assertEquals(99, communication.getResourceId());
        assertEquals(2.0, communication.getDurationSeconds(), 1.0e-9);
        assertEquals(Collections.singletonList(1), communication.getPredecessorActivityIds());
        assertEquals(Collections.singletonList(3), enhanced.getActivity(2).getPredecessorActivityIds());
        assertEquals(22.0, enhanced.getIdleWattsSum(), 1.0e-9);

        TimedPlan optimized = CaWoSchedVariant.SLACK.optimizer().optimize(enhanced);
        assertTrue(optimized.getActivity(1).getFinishSeconds()
                <= optimized.getActivity(3).getStartSeconds());
        assertTrue(optimized.getActivity(3).getFinishSeconds()
                <= optimized.getActivity(2).getStartSeconds());
        assertTrue(new CarbonExcessEvaluator().evaluate(optimized).getCarbonExcessWattSeconds() >= 0.0);
    }

    @Test
    void missingCommunicationLinkFailsFast() {
        TimedActivity first = new TimedActivity(
                1, 1, TimedActivityType.COMPUTE, 0, 0, 1.0, 0.0, 10.0, 30.0, Collections.<Integer>emptyList());
        TimedActivity second = new TimedActivity(
                2, 2, TimedActivityType.COMPUTE, 1, 0, 1.0, 1.0, 10.0, 30.0, Collections.singletonList(1));
        TimedPlan computePlan = new TimedPlan(
                Arrays.asList(first, second),
                Collections.singletonList(new PlatformProfile.CarbonWindow(0.0, 5.0, 0.0)),
                5.0,
                Collections.emptyMap());
        assertThrows(
                IllegalArgumentException.class,
                () -> CommunicationEnhancedPlan.fromComputePlan(computePlan, Collections.emptyList()));
    }
}
