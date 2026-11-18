package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationSession;

class DheftIntegrationTest {

    @Test
    void localDheftProducesAValidFileAwareSchedule() {
        Task first = new Task(1, 1000L);
        Task second = new Task(2, 1000L);
        first.getFileList().add(file("x", 100.0, Parameters.FileType.OUTPUT));
        second.getFileList().add(file("x", 100.0, Parameters.FileType.INPUT));
        PlanningRegressionFixtures.connect(first, second);
        List<Task> tasks = Arrays.asList(first, second);
        List<CondorVM> vms = PlanningRegressionFixtures.vms(1000.0, 1, 1000.0, 1);
        PlatformProfile platform = PlanningRegressionFixtures.platform(vms);

        try (SimulationSession session = SimulationSession.open(
                PlanningRegressionFixtures.config("dheft.dax", Parameters.PlanningAlgorithm.DHEFT, null, true))) {
            DheftPlanningAlgorithm planner = new DheftPlanningAlgorithm(new PlanningContext(
                    PlanningRegressionFixtures.config("dheft.dax", Parameters.PlanningAlgorithm.DHEFT, null, true),
                    platform));
            planner.setTaskList(tasks);
            planner.setVmList(vms);
            planner.run();

            assertEquals(2, planner.getLastSchedule().getTaskPlans().size());
            assertTrue(planner.getLastSchedule().getMakespanSeconds() > 0.0);
            assertTrue(planner.getLastSchedule().getTaskPlan(2).getStartSeconds()
                    >= planner.getLastSchedule().getTaskPlan(1).getFinishSeconds());
        }
    }

    private static FileItem file(String name, double size, Parameters.FileType type) {
        FileItem item = new FileItem(name, size);
        item.setType(type);
        return item;
    }
}
