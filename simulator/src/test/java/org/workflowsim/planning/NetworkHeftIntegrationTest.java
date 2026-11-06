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

/** Real PlanningContext integration with file-carrying Network-Aware HEFT tasks. */
class NetworkHeftIntegrationTest {

    @Test
    void fileBytesDriveTheNetworkAwarePlan() {
        Task first = new Task(1, 1000L);
        Task second = new Task(2, 1000L);
        Task third = new Task(3, 1000L);
        first.getFileList().add(file("shared.bin", 100.0, Parameters.FileType.OUTPUT));
        second.getFileList().add(file("shared.bin", 100.0, Parameters.FileType.INPUT));
        third.getFileList().add(file("shared.bin", 100.0, Parameters.FileType.INPUT));
        PlanningRegressionFixtures.connect(first, second);
        PlanningRegressionFixtures.connect(first, third);
        List<Task> tasks = Arrays.asList(first, second, third);
        List<CondorVM> vms = PlanningRegressionFixtures.vms(1000.0, 1, 1000.0, 1);
        PlatformProfile platform = PlanningRegressionFixtures.platform(vms);

        try (SimulationSession session = SimulationSession.open(PlanningRegressionFixtures.config(
                "network-heft.dax", Parameters.PlanningAlgorithm.NETWORK_HEFT, null, false))) {
            NetworkHeftPlanningAlgorithm planner = new NetworkHeftPlanningAlgorithm(new PlanningContext(
                    PlanningRegressionFixtures.config(
                            "network-heft.dax", Parameters.PlanningAlgorithm.NETWORK_HEFT, null, false),
                    platform));
            planner.setTaskList(tasks);
            planner.setVmList(vms);
            planner.run();

            assertEquals(3, planner.getLastSchedule().getTaskPlans().size());
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
