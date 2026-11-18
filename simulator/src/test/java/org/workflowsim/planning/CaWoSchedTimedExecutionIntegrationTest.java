package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.CondorVM;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class CaWoSchedTimedExecutionIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void timedPlanDefersAndReleasesJobsAtPlannedStarts() throws Exception {
        Path workflow = PlanningRegressionFixtures.workflow(
                directory,
                "cawosched-timed.dax",
                "<job id=\"a\" name=\"a\" runtime=\"4\"/>"
                        + "<job id=\"b\" name=\"b\" runtime=\"2\"/>"
                        + "<child ref=\"b\"><parent ref=\"a\"/></child>");
        List<CondorVM> vms = PlanningRegressionFixtures.vms(1000.0, 1, 1000.0, 1);
        PlatformProfile.Builder builder = PlatformProfile.builder("cawosched-timed-platform");
        for (int index = 0; index < vms.size(); index++) {
            CondorVM vm = vms.get(index);
            builder.addHost(
                    new PlatformProfile.HostSpec(index, vm.getNumberOfPes(), vm.getMips(), 2048, 10000L, 1000000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    vm.getId(),
                    vm.getMips(),
                    vm.getNumberOfPes(),
                    512,
                    vm.getBw(),
                    10000L,
                    "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                    null,
                    10.0,
                    30.0,
                    null));
            builder.pinVmToHost(vm.getId(), index);
        }
        builder.carbonWindow(0.0, 5.0, 0.0).carbonWindow(5.0, 10.0, 100.0).carbonWindow(10.0, 20.0, 0.0);
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.CAWOSCHED_SLACK)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(org.workflowsim.data.DataMovementModel.legacyWorkflowsimV1())
                .deadline(20L)
                .runtimeReferenceMips(1.0)
                .randomSeed(7L)
                .build();

        SimulationReport report = new SimulationRunner().run(config, builder.build());
        SimulationReport.JobOutcome first = PlanningRegressionFixtures.computeJob(report, 1);
        SimulationReport.JobOutcome second = PlanningRegressionFixtures.computeJob(report, 2);

        assertTrue(first.getStartTime() >= 0.0);
        assertTrue(second.getStartTime() >= first.getFinishTime() - 1.0e-9);
        assertTrue(second.getFinishTime() <= 20.0 + 1.0e-9);
        assertTrue(second.getStartTime() > 0.0);
    }
}
