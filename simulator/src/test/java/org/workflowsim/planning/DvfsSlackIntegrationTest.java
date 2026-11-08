package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** Full SimulationRunner integration of planner-selected DVFS tiers. */
class DvfsSlackIntegrationTest {

    @TempDir
    Path directory;

    @Test
    void runnerUsesThePlannerSelectedTierForEveryComputeJob() throws Exception {
        Path workflow = PlanningRegressionFixtures.workflow(
                directory,
                "dvfs.dax",
                "<job id=\"a\" name=\"a\" runtime=\"1000\"/>"
                        + "<job id=\"b\" name=\"b\" runtime=\"1000\"/>"
                        + "<child ref=\"b\"><parent ref=\"a\"/></child>");
        PlatformProfile platform = PlatformProfile.builder("dvfs")
                .addHost(new PlatformProfile.HostSpec(0, 1, 2000.0, 4096, 10000L, 100000L))
                .addVm(new PlatformProfile.VmSpec(
                        3,
                        2000.0,
                        1,
                        4096,
                        10000L,
                        1000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        0.0,
                        0.0,
                        null,
                        Arrays.asList(
                                new PlatformProfile.FrequencyTier(2000.0, 10.0, 100.0),
                                new PlatformProfile.FrequencyTier(500.0, 2.0, 20.0))))
                .build();
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 1)
                .planningAlgorithm(Parameters.PlanningAlgorithm.DVFS_SLACK)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.DVFS_SLACK)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(org.workflowsim.data.DataMovementModel.legacyWorkflowsimV1())
                .deadline(5L)
                .runtimeReferenceMips(1.0)
                .randomSeed(7L)
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform);

        assertTrue(report.getJobs().size() >= 2);
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == Parameters.ClassType.COMPUTE.value) {
                assertEquals(500.0, job.getTierMips(), 1.0e-9);
            }
        }
        assertTrue(report.getMakespan() <= 4.3);
    }

    @Test
    void rejectsRawMiFeasibleButSharedStorageInfeasibleDeadline() throws Exception {
        Path workflow = PlanningRegressionFixtures.workflow(
                directory,
                "dvfs-input.dax",
                "<job id=\"a\" name=\"a\" runtime=\"1000\">"
                        + "<uses file=\"x\" link=\"input\" size=\"100000000\"/></job>");
        PlatformProfile platform = PlatformProfile.builder("dvfs-storage")
                .storage(new PlatformProfile.StorageSpec(1000000000000L, 1))
                .addHost(new PlatformProfile.HostSpec(0, 1, 2000.0, 4096, 10000L, 100000L))
                .addVm(new PlatformProfile.VmSpec(
                        3,
                        2000.0,
                        1,
                        4096,
                        10000L,
                        1000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        0.0,
                        0.0,
                        null,
                        Arrays.asList(
                                new PlatformProfile.FrequencyTier(2000.0, 10.0, 100.0),
                                new PlatformProfile.FrequencyTier(500.0, 2.0, 20.0))))
                .build();
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 1)
                .planningAlgorithm(Parameters.PlanningAlgorithm.DVFS_SLACK)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.DVFS_SLACK)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(org.workflowsim.data.DataMovementModel.legacyWorkflowsimV1())
                .deadline(2L)
                .runtimeReferenceMips(1.0)
                .randomSeed(7L)
                .build();

        assertThrows(IllegalStateException.class, () -> new SimulationRunner().run(config, platform));
    }
}
