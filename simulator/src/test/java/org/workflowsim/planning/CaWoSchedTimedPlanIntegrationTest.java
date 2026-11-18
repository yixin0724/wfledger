package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.planning.cawosched.CaWoSchedAdapter;
import org.workflowsim.planning.cawosched.CaWoSchedVariant;
import org.workflowsim.planning.timed.CarbonExcessEvaluator;
import org.workflowsim.planning.timed.PlanEvaluation;
import org.workflowsim.planning.timed.TimedPlan;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationSession;

class CaWoSchedTimedPlanIntegrationTest {

    @Test
    void sharedHeftTimedPlanSupportsAllSixteenVariants() {
        Task first = new Task(1, 4L);
        Task second = new Task(2, 2L);
        PlanningRegressionFixtures.connect(first, second);
        List<Task> tasks = Arrays.asList(first, second);
        List<CondorVM> vms = PlanningRegressionFixtures.vms(2.0, 1, 1.0, 1);
        PlatformProfile platform = PlanningRegressionFixtures.platform(vms);
        List<PlatformProfile.CarbonWindow> windows = Arrays.asList(
                new PlatformProfile.CarbonWindow(0.0, 5.0, 0.0),
                new PlatformProfile.CarbonWindow(5.0, 10.0, 100.0),
                new PlatformProfile.CarbonWindow(10.0, 20.0, 0.0));
        try (SimulationSession session = SimulationSession.open(PlanningRegressionFixtures.config(
                "cawosched-timed-integration.dax", Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT, null, false))) {
            PlanningContext context = new PlanningContext(
                    PlanningRegressionFixtures.config(
                            "cawosched-timed-integration.dax",
                            Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT,
                            null,
                            false),
                    platform);
            SharedStorageHeftPlanningAlgorithm heft = new SharedStorageHeftPlanningAlgorithm(context);
            heft.setTaskList(tasks);
            heft.setVmList(vms);
            heft.run();
            TimedPlan initial =
                    CaWoSchedAdapter.toTimedPlan(tasks, vms, platform, heft.getLastPlanTrace(), windows, 20.0);
            for (CaWoSchedVariant variant : CaWoSchedVariant.values()) {
                TimedPlan optimized = variant.optimizer().optimize(initial);
                PlanEvaluation evaluation = new CarbonExcessEvaluator().evaluate(optimized);
                assertTrue(evaluation.getCarbonExcessWattSeconds() >= 0.0, variant.name());
                assertTrue(optimized.getMakespanSeconds() <= 20.0, variant.name());
                assertTrue(
                        optimized.getActivity(1).getFinishSeconds()
                                <= optimized.getActivity(2).getStartSeconds(),
                        variant.name());
            }
        }
    }
}
