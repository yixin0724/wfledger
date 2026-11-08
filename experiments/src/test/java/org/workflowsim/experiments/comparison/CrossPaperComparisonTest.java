package org.workflowsim.experiments.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.planning.IcpcpPlanningAlgorithm;
import org.workflowsim.planning.PlanningContext;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

/** Real cross-paper comparison with separate SHARED and LOCAL physical models. */
class CrossPaperComparisonTest {

    @TempDir
    Path directory;

    @Test
    void sharedAndLocalScenesProduceAuditableResults() throws Exception {
        Path workflow = directory.resolve("comparison.dax");
        Files.write(
                workflow,
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                                + "<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\">\n"
                                + "  <job id=\"a\" name=\"a\" runtime=\"1000\">\n"
                                + "    <uses file=\"x\" link=\"output\" size=\"100\"/>\n"
                                + "  </job>\n"
                                + "  <job id=\"b\" name=\"b\" runtime=\"1000\">\n"
                                + "    <uses file=\"x\" link=\"input\" size=\"100\"/>\n"
                                + "  </job>\n"
                                + "  <child ref=\"b\"><parent ref=\"a\"/></child>\n"
                                + "</adag>\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PlatformProfile platform = PlatformProfile.builder("comparison")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 4096, 10000L, 100000L))
                .addHost(new PlatformProfile.HostSpec(1, 1, 1000.0, 4096, 10000L, 100000L))
                .addVm(new PlatformProfile.VmSpec(
                        3,
                        1000.0,
                        1,
                        4096,
                        100L,
                        1000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        new PlatformProfile.CostSpec(10.0, 0.0, 0.0, 0.0),
                        10.0,
                        200.0,
                        null,
                        java.util.Collections.emptyList()))
                .addVm(new PlatformProfile.VmSpec(
                        8,
                        500.0,
                        1,
                        4096,
                        50L,
                        1000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        new PlatformProfile.CostSpec(1.0, 0.0, 0.0, 0.0),
                        5.0,
                        50.0,
                        null,
                        java.util.Collections.emptyList()))
                .build();

        List<CrossPaperComparison.RunResult> shared =
                new ArrayList<CrossPaperComparison.RunResult>(CrossPaperComparison.runSharedScene(workflow, platform));
        List<CrossPaperComparison.RunResult> local = CrossPaperComparison.runLocalScene(workflow, platform);
        double icpcpPlannerCost = icpcpPlannerCost(workflow, platform);
        for (int index = 0; index < shared.size(); index++) {
            CrossPaperComparison.RunResult result = shared.get(index);
            if ("IC_PCP".equals(result.getAlgorithm())) {
                shared.set(index, result.withPlannerCost(icpcpPlannerCost));
            }
        }

        assertEquals(5, shared.size());
        assertEquals(2, local.size());
        for (CrossPaperComparison.RunResult result : shared) {
            assertTrue(result.isCompleted(), result.getAlgorithm());
            assertTrue(result.getMakespanSeconds() > 0.0, result.getAlgorithm());
            assertTrue(result.getEnergyJoules() >= 0.0, result.getAlgorithm());
            assertTrue(result.getModeledProcessingCost() >= 0.0, result.getAlgorithm());
            assertTrue(result.getEnergyResidualJoules() < 1.0e-6, result.getAlgorithm());
            if ("IC_PCP".equals(result.getAlgorithm())) {
                assertTrue(result.getPlannerCost() != null && result.getPlannerCost() >= 0.0);
            }
            System.out.println("SHARED " + result.getAlgorithm() + " makespan=" + result.getMakespanSeconds()
                    + " energy=" + result.getEnergyJoules() + " modeledCost=" + result.getModeledProcessingCost()
                    + " plannerCost=" + result.getPlannerCost() + " mapping=" + result.getMapping());
        }
        assertTrue(
                shared.stream()
                                .map(CrossPaperComparison.RunResult::getMapping)
                                .distinct()
                                .count()
                        >= 3,
                "The comparison scene must distinguish algorithm mappings");
        for (CrossPaperComparison.RunResult result : local) {
            assertTrue(result.isCompleted(), result.getAlgorithm());
            assertTrue(result.getMakespanSeconds() > 0.0, result.getAlgorithm());
            assertTrue(result.getEnergyResidualJoules() < 1.0e-6, result.getAlgorithm());
            System.out.println("LOCAL " + result.getAlgorithm() + " makespan=" + result.getMakespanSeconds()
                    + " energy=" + result.getEnergyJoules() + " cost=" + result.getModeledProcessingCost()
                    + " mapping=" + result.getMapping());
        }
    }

    private static double icpcpPlannerCost(Path workflow, PlatformProfile platform) {
        SimulationConfig config = SimulationConfig.builder(
                        workflow.toString(), platform.getVms().size())
                .planningAlgorithm(Parameters.PlanningAlgorithm.IC_PCP)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .costModel(Parameters.CostModel.VM)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .deadline(10L)
                .runtimeReferenceMips(1.0)
                .randomSeed(7L)
                .build();
        try (SimulationSession session = SimulationSession.open(config)) {
            Task first = new Task(1, 1000L);
            Task second = new Task(2, 1000L);
            first.getFileList().add(file("x", Parameters.FileType.OUTPUT));
            second.getFileList().add(file("x", Parameters.FileType.INPUT));
            second.addParent(first);
            first.addChild(second);
            List<CondorVM> vms = Arrays.asList(vm(3, 1000.0, 100L), vm(8, 500.0, 50L));
            IcpcpPlanningAlgorithm planner = new IcpcpPlanningAlgorithm(new PlanningContext(config, platform));
            planner.setTaskList(Arrays.asList(first, second));
            planner.setVmList(vms);
            planner.run();
            return planner.getLastSchedule().getTotalCost();
        }
    }

    private static FileItem file(String name, Parameters.FileType type) {
        FileItem file = new FileItem(name, 100.0);
        file.setType(type);
        return file;
    }

    private static CondorVM vm(int id, double mips, long bandwidth) {
        return new CondorVM(id, 0, mips, 1, 4096, bandwidth, 1000L, "Xen", new CloudletSchedulerSpaceShared());
    }
}
