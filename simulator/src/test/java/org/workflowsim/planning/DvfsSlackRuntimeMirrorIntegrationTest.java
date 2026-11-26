package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.WorkflowParser;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.planning.dvfs.DvfsSlackSchedule;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class DvfsSlackRuntimeMirrorIntegrationTest {

    @TempDir
    Path directory;

    private boolean logWasDisabled;

    @BeforeEach
    void disableSimulationLog() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restoreSimulationLog() {
        Log.setDisabled(logWasDisabled);
    }

    @Test
    void plannedTierWindowsMirrorRuntimeTaskWindows() throws Exception {
        Path workflow = PlanningRegressionFixtures.workflow(
                directory,
                "dvfs-mirror.dax",
                "<job id=\"a\" name=\"a\" runtime=\"1000\">"
                        + "<uses file=\"ia\" link=\"input\" size=\"4000000\"/></job>"
                        + "<job id=\"b\" name=\"b\" runtime=\"1000\">"
                        + "<uses file=\"ib\" link=\"input\" size=\"4000000\"/></job>"
                        + "<job id=\"c\" name=\"c\" runtime=\"1000\">"
                        + "<uses file=\"ic\" link=\"input\" size=\"4000000\"/></job>"
                        + "<child ref=\"b\"><parent ref=\"a\"/></child>");
        PlatformProfile platform = PlatformProfile.builder("dvfs-mirror")
                .storage(new PlatformProfile.StorageSpec(1_000_000_000_000L, 20))
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
                                new PlatformProfile.FrequencyTier(1500.0, 6.0, 50.0),
                                new PlatformProfile.FrequencyTier(500.0, 2.0, 20.0))))
                .build();
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 1)
                .planningAlgorithm(Parameters.PlanningAlgorithm.DVFS_SLACK)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.DVFS_SLACK)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .deadline(3L)
                .runtimeReferenceMips(1.0)
                .randomSeed(7L)
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform);
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully());

        ReplicaCatalog.reset();
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.SHARED);
        Parameters.reset();
        Parameters.init(
                1,
                workflow.toString(),
                null,
                null,
                null,
                null,
                Parameters.SchedulingAlgorithm.DVFS_SLACK,
                null,
                null,
                0L);
        Parameters.setRuntimeReferenceMips(1.0);
        WorkflowParser parser = new WorkflowParser(0);
        parser.parse();
        List<Task> tasks = parser.getTaskList();
        List<CondorVM> vms = new ArrayList<CondorVM>();
        vms.add(new CondorVM(3, 0, 2000.0, 1, 4096, 10000L, 1000L, "Xen", new CloudletSchedulerSpaceShared()));
        DvfsSlackPlanningAlgorithm planner = new DvfsSlackPlanningAlgorithm(new PlanningContext(config, platform));
        planner.setTaskList(tasks);
        planner.setVmList(vms);
        planner.run();
        DvfsSlackSchedule plan = planner.getLastSchedule();

        boolean slowed = false;
        for (SimulationReport.TaskOutcome outcome : report.getTasks()) {
            DvfsSlackSchedule.Entry entry = plan.getTaskPlan(outcome.getTaskId());
            String subject = "task " + outcome.getTaskId();
            double stageIn = (long) (2000.0 * 0.2) / 2000.0;
            assertEquals(entry.getVmId(), outcome.getVmId(), subject + " VM");
            assertEquals(entry.getStartSeconds() + stageIn, outcome.getStartTime(), 1.0e-9, subject + " compute start");
            for (SimulationReport.JobOutcome job : report.getJobs()) {
                if (job.getJobId() == outcome.getJobId()) {
                    assertEquals(entry.getTierMips(), job.getTierMips(), 0.0, subject + " tier");
                    assertEquals(entry.getStartSeconds(), job.getStartTime(), 1.0e-9, subject + " envelope start");
                    assertEquals(entry.getFinishSeconds(), job.getFinishTime(), 1.0e-9, subject + " envelope finish");
                }
            }
            slowed |= entry.getTierMips() < 2000.0;
        }
        assertTrue(slowed, "the deadline must leave slack that DVFS uses");
    }
}
