package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

/** Real IC-PCP PlanningContext integration with a positive deadline and VM costs. */
class IcpcpIntegrationTest {

    @Test
    void platformChoosesTheLowestCostDeadlineFeasibleVm() {
        Task first = new Task(1, 1000L);
        Task second = new Task(2, 2000L);
        PlanningRegressionFixtures.connect(first, second);
        List<Task> tasks = Arrays.asList(first, second);
        List<CondorVM> vms = PlanningRegressionFixtures.vms(1000.0, 1, 1000.0, 1);
        PlatformProfile.Builder builder = PlatformProfile.builder("icpcp");
        for (int hostId = 0; hostId < 2; hostId++) {
            builder.addHost(new PlatformProfile.HostSpec(hostId, 1, 1000.0, 4096, 10000L, 100000L));
        }
        builder.addVm(new PlatformProfile.VmSpec(
                3,
                1000.0,
                1,
                4096,
                10000L,
                1000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                new PlatformProfile.CostSpec(10.0, 0.0, 0.0, 0.0),
                0.0,
                0.0,
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
                new PlatformProfile.CostSpec(1.0, 0.0, 0.0, 0.0),
                0.0,
                0.0,
                null,
                Collections.emptyList()));
        PlatformProfile platform = builder.build();
        SimulationConfig config = SimulationConfig.builder("icpcp.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.IC_PCP)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .deadline(4L)
                .runtimeReferenceMips(1.0)
                .randomSeed(7L)
                .build();

        try (SimulationSession session = SimulationSession.open(config)) {
            IcpcpPlanningAlgorithm planner = new IcpcpPlanningAlgorithm(new PlanningContext(config, platform));
            planner.setTaskList(tasks);
            planner.setVmList(vms);
            planner.run();

            assertEquals(8, planner.getLastSchedule().getTaskPlan(1).getVmId());
            assertEquals(8, planner.getLastSchedule().getTaskPlan(2).getVmId());
            assertEquals(3.0, planner.getLastSchedule().getTotalCost(), 1.0e-9);
            // The abstract three-second chain starts after the 0.21-second SHARED bootstrap.
            assertEquals(0.21, planner.getLastSchedule().getTaskPlan(1).getStartSeconds(), 1.0e-9);
            assertEquals(3.21, planner.getLastSchedule().getMakespanSeconds(), 1.0e-9);
        }
    }
}
