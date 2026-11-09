package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.WorkflowParser;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

/** Real IC-PCP PlanningContext integration with a positive deadline and VM costs. */
class IcpcpIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void fanInWithCostsAndPowerRemainsFeasible() {
        Task parent = new Task(1, 1000L);
        Task second = new Task(2, 2000L);
        Task third = new Task(3, 3000L);
        Task join = new Task(4, 1000L);
        org.workflowsim.FileItem output = PlanningRegressionFixtures.file("x", 1000000.0, Parameters.FileType.OUTPUT);
        parent.addFile(output);
        parent.addFile(output);
        for (Task child : Arrays.asList(second, third, join)) {
            child.addFile(PlanningRegressionFixtures.file("x", 1000000.0, Parameters.FileType.INPUT));
        }
        PlanningRegressionFixtures.connect(parent, second);
        PlanningRegressionFixtures.connect(parent, third);
        PlanningRegressionFixtures.connect(second, join);
        PlanningRegressionFixtures.connect(third, join);
        List<CondorVM> vms = PlanningRegressionFixtures.vms(1000.0, 1, 500.0, 1);
        PlatformProfile.Builder builder = PlatformProfile.builder("icpcp-fan");
        builder.addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 4096, 10000L, 100000L));
        builder.addHost(new PlatformProfile.HostSpec(1, 1, 1000.0, 4096, 10000L, 100000L));
        builder.addVm(new PlatformProfile.VmSpec(
                3,
                1000.0,
                1,
                4096,
                100L,
                1000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                new PlatformProfile.CostSpec(10.0, 0.001, 0.0, 0.0),
                10.0,
                200.0,
                null,
                Collections.emptyList()));
        builder.addVm(new PlatformProfile.VmSpec(
                8,
                500.0,
                1,
                4096,
                50L,
                1000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                new PlatformProfile.CostSpec(1.0, 0.001, 0.0, 0.0),
                5.0,
                50.0,
                null,
                Collections.emptyList()));
        PlatformProfile platform = builder.build();
        SimulationConfig config = SimulationConfig.builder("icpcp-fan.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.IC_PCP)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .deadline(1000L)
                .runtimeReferenceMips(1.0)
                .randomSeed(7L)
                .build();

        try (SimulationSession session = SimulationSession.open(config)) {
            IcpcpPlanningAlgorithm planner = new IcpcpPlanningAlgorithm(new PlanningContext(config, platform));
            planner.setTaskList(Arrays.asList(parent, second, third, join));
            planner.setVmList(vms);
            planner.run();
            assertEquals(4, planner.getLastSchedule().getTaskPlans().size());
        }
    }

    @Test
    void parsedFanInDaxMatchesDirectTaskInputs() throws Exception {
        Path workflow = directory.resolve("icpcp-fan.dax");
        java.nio.file.Files.write(
                workflow,
                ("<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"1000\">"
                                + "<uses file=\"x\" link=\"output\" size=\"1000000\"/>"
                                + "<uses file=\"x\" link=\"output\" size=\"1000000\"/></job>"
                                + "<job id=\"b\" name=\"b\" runtime=\"2000\">"
                                + "<uses file=\"x\" link=\"input\" size=\"1000000\"/></job>"
                                + "<job id=\"c\" name=\"c\" runtime=\"3000\">"
                                + "<uses file=\"x\" link=\"input\" size=\"1000000\"/></job>"
                                + "<job id=\"d\" name=\"d\" runtime=\"1000\">"
                                + "<uses file=\"x\" link=\"input\" size=\"1000000\"/></job>"
                                + "<child ref=\"b\"><parent ref=\"a\"/></child>"
                                + "<child ref=\"c\"><parent ref=\"a\"/></child>"
                                + "<child ref=\"d\"><parent ref=\"b\"/><parent ref=\"c\"/></child></adag>")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<CondorVM> vms = PlanningRegressionFixtures.vms(1000.0, 1, 500.0, 1);
        PlatformProfile.Builder builder = PlatformProfile.builder("icpcp-fan-dax");
        builder.addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 4096, 10000L, 100000L));
        builder.addHost(new PlatformProfile.HostSpec(1, 1, 1000.0, 4096, 10000L, 100000L));
        builder.addVm(new PlatformProfile.VmSpec(
                3,
                1000.0,
                1,
                4096,
                100L,
                1000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                new PlatformProfile.CostSpec(10.0, 0.001, 0.0, 0.0),
                10.0,
                200.0,
                null,
                Collections.emptyList()));
        builder.addVm(new PlatformProfile.VmSpec(
                8,
                500.0,
                1,
                4096,
                50L,
                1000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                new PlatformProfile.CostSpec(1.0, 0.001, 0.0, 0.0),
                5.0,
                50.0,
                null,
                Collections.emptyList()));
        PlatformProfile platform = builder.build();
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.IC_PCP)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .costModel(Parameters.CostModel.VM)
                .deadline(1000L)
                .runtimeReferenceMips(1.0)
                .randomSeed(7L)
                .build();
        try (SimulationSession session = SimulationSession.open(config)) {
            WorkflowParser parser = new WorkflowParser(0);
            parser.parse();
            List<Task> tasks = parser.getTaskList();
            IcpcpPlanningAlgorithm planner = new IcpcpPlanningAlgorithm(new PlanningContext(config, platform));
            planner.setTaskList(tasks);
            planner.setVmList(vms);
            planner.run();
            assertEquals(4, planner.getLastSchedule().getTaskPlans().size());
        }
    }

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
