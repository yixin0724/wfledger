package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.planning.naco.NacoSchedule;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationSession;

/** Real LOCAL SimulationSession integration for the NACO hybrid GA/HEFT planner. */
class NacoGaHeftIntegrationTest {

    @Test
    void localNacoPlannerProducesAValidEliteSchedule() {
        Task first = new Task(1, 4L);
        Task second = new Task(2, 2L);
        PlanningRegressionFixtures.connect(first, second);
        List<Task> tasks = Arrays.asList(first, second);
        List<CondorVM> vms = PlanningRegressionFixtures.vms(2.0, 1, 1.0, 1);
        PlatformProfile platform = PlanningRegressionFixtures.platform(vms);
        try (SimulationSession session = SimulationSession.open(PlanningRegressionFixtures.config(
                "naco-integration.dax", Parameters.PlanningAlgorithm.NACO_GA_HEFT, null, true))) {
            NacoGaHeftPlanningAlgorithm planner = new NacoGaHeftPlanningAlgorithm(new PlanningContext(
                    PlanningRegressionFixtures.config(
                            "naco-integration.dax", Parameters.PlanningAlgorithm.NACO_GA_HEFT, null, true),
                    platform));
            planner.setTaskList(tasks);
            planner.setVmList(vms);
            planner.run();
            NacoSchedule schedule = planner.getLastSchedule();
            assertTrue(schedule.getMakespanSeconds() > 0.0);
            assertTrue(schedule.getTaskPlans().size() == 2);
            assertTrue(schedule.getTaskPlan(1).getFinishSeconds()
                    <= schedule.getTaskPlan(2).getStartSeconds());
        }
    }
}
