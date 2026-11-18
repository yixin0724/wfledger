package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationSession;

class MoHeftIntegrationTest {

    @Test
    void platformProducesTradeoffFrontAndBalancedSchedule() {
        Task first = new Task(1, 4L);
        Task second = new Task(2, 2L);
        List<Task> tasks = Arrays.asList(first, second);
        List<CondorVM> vms = PlanningRegressionFixtures.vms(2000.0, 1, 1000.0, 1);
        PlatformProfile.Builder builder = PlatformProfile.builder("moheft");
        for (int hostId = 0; hostId < 2; hostId++) {
            builder.addHost(new PlatformProfile.HostSpec(hostId, 1, 2000.0, 4096, 10000L, 100000L));
        }
        builder.addVm(new PlatformProfile.VmSpec(
                3,
                2000.0,
                1,
                4096,
                10000L,
                1000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                20.0,
                200.0,
                null,
                Collections.emptyList()));
        builder.addVm(new PlatformProfile.VmSpec(
                8,
                1000.0,
                1,
                4096,
                10000L,
                1000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                10.0,
                50.0,
                null,
                Collections.emptyList()));
        PlatformProfile platform = builder.build();

        try (SimulationSession session = SimulationSession.open(
                PlanningRegressionFixtures.config("moheft.dax", Parameters.PlanningAlgorithm.MO_HEFT, null, false))) {
            MoHeftPlanningAlgorithm planner = new MoHeftPlanningAlgorithm(new PlanningContext(
                    PlanningRegressionFixtures.config("moheft.dax", Parameters.PlanningAlgorithm.MO_HEFT, null, false),
                    platform));
            planner.setTaskList(tasks);
            planner.setVmList(vms);
            planner.run();

            assertEquals(3, planner.getLastResult().getParetoFront().size());
            assertEquals(
                    8,
                    planner.getLastResult().getBalancedSchedule().getTaskPlan(1).getVmId());
            assertEquals(
                    3,
                    planner.getLastResult().getBalancedSchedule().getTaskPlan(2).getVmId());
            assertEquals(0.4, planner.getLastResult().getBalancedSchedule().getTotalEnergyJoules(), 1.0e-9);
        }
    }
}
