package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class EnergyPlannerRuntimeMirrorIntegrationTest {

    @TempDir
    Path tempDir;

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
    void greenHeftPlannedWindowsMirrorRuntimeWindowsExactly() throws Exception {
        mirror(PlanningAlgorithm.GREEN_HEFT);
    }

    @Test
    void moHeftPlannedWindowsMirrorRuntimeWindowsExactly() throws Exception {
        mirror(PlanningAlgorithm.MO_HEFT);
    }

    private void mirror(PlanningAlgorithm algorithm) throws Exception {
        Path workflow = sharedDax();
        PlatformProfile platform = platform();
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .planningAlgorithm(algorithm)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261124L)
                .build();
        SimulationReport report = new SimulationRunner().run(config, platform);
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully(), algorithm + " must complete");

        List<Task> tasks = reparse(workflow);
        List<CondorVM> vms = new ArrayList<CondorVM>();
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            vms.add(new CondorVM(
                    spec.getId(),
                    0,
                    spec.getMips(),
                    1,
                    512,
                    spec.getBandwidth(),
                    10_000L,
                    "Xen",
                    new CloudletSchedulerSpaceShared()));
        }
        Map<Integer, double[]> planned = plan(algorithm, new PlanningContext(config, platform), tasks, vms);
        Map<Integer, Double> stageIn = new LinkedHashMap<Integer, Double>();
        for (Task task : tasks) {
            double inputSeconds = 0.0;
            for (org.workflowsim.FileItem file : task.getFileList()) {
                if (file.isRealInputFile(task.getFileList())) {
                    inputSeconds +=
                            file.getSize() / 1.0e6 / platform.getStorage().getMaxTransferRateMbPerSecond();
                }
            }
            stageIn.put(task.getCloudletId(), inputSeconds);
        }

        assertEquals(tasks.size(), report.getTasks().size());
        for (SimulationReport.TaskOutcome outcome : report.getTasks()) {
            double[] window = planned.get(outcome.getTaskId());
            int vmId = (int) window[0];
            double mips = platform.getVms().get(vmId).getMips();
            double effectiveStageIn = (long) (mips * stageIn.get(outcome.getTaskId())) / mips;
            String subject = algorithm + " task " + outcome.getTaskId();
            assertEquals(vmId, outcome.getVmId(), subject + " VM");
            assertEquals(window[1] + effectiveStageIn, outcome.getStartTime(), 0.0, subject + " compute start");

            assertEquals(window[2], outcome.getFinishTime(), 1.0e-9, subject + " envelope finish");
        }
    }

    private static Map<Integer, double[]> plan(
            PlanningAlgorithm algorithm, PlanningContext context, List<Task> tasks, List<CondorVM> vms) {
        Map<Integer, double[]> result = new LinkedHashMap<Integer, double[]>();
        if (algorithm == PlanningAlgorithm.GREEN_HEFT) {
            GreenHeftPlanningAlgorithm planner = new GreenHeftPlanningAlgorithm(context);
            planner.setTaskList(tasks);
            planner.setVmList(vms);
            planner.run();
            for (org.workflowsim.planning.greenheft.GreenHeftSchedule.Entry entry :
                    planner.getLastSchedule().getTaskPlans()) {
                result.put(
                        entry.getTaskId(),
                        new double[] {entry.getVmId(), entry.getStartSeconds(), entry.getFinishSeconds()});
            }
        } else {
            MoHeftPlanningAlgorithm planner = new MoHeftPlanningAlgorithm(context);
            planner.setTaskList(tasks);
            planner.setVmList(vms);
            planner.run();
            for (Task task : tasks) {
                org.workflowsim.planning.moheft.MoHeftSchedule.Entry entry =
                        planner.getLastResult().getBalancedSchedule().getTaskPlan(task.getCloudletId());
                result.put(
                        entry.getTaskId(),
                        new double[] {entry.getVmId(), entry.getStartSeconds(), entry.getFinishSeconds()});
            }
        }
        return result;
    }

    private static List<Task> reparse(Path workflow) {
        ReplicaCatalog.reset();
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.SHARED);
        Parameters.reset();
        Parameters.init(2, workflow.toString(), null, null, null, null, SchedulingAlgorithm.STATIC, null, null, 0L);
        WorkflowParser parser = new WorkflowParser(0);
        parser.parse();
        return parser.getTaskList();
    }

    private Path sharedDax() throws Exception {
        String dax = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"4\">\n"
                + "  <job id=\"t1\" name=\"a\" runtime=\"60\">\n"
                + "    <uses name=\"in-1.bin\" link=\"input\" size=\"3000000\"/>\n"
                + "    <uses name=\"out-1.bin\" link=\"output\" size=\"8000000\"/>\n"
                + "  </job>\n"
                + "  <job id=\"t2\" name=\"b\" runtime=\"50\">\n"
                + "    <uses name=\"out-2.bin\" link=\"output\" size=\"6000000\"/>\n"
                + "  </job>\n"
                + "  <job id=\"t3\" name=\"c\" runtime=\"40\">\n"
                + "    <uses name=\"out-1.bin\" link=\"input\" size=\"8000000\"/>\n"
                + "    <uses name=\"out-3.bin\" link=\"output\" size=\"7000000\"/>\n"
                + "  </job>\n"
                + "  <job id=\"t4\" name=\"d\" runtime=\"30\">\n"
                + "    <uses name=\"out-1.bin\" link=\"input\" size=\"8000000\"/>\n"
                + "    <uses name=\"out-2.bin\" link=\"input\" size=\"6000000\"/>\n"
                + "  </job>\n"
                + "  <child ref=\"t3\"><parent ref=\"t1\"/></child>\n"
                + "  <child ref=\"t4\"><parent ref=\"t1\"/></child>\n"
                + "  <child ref=\"t4\"><parent ref=\"t2\"/></child>\n"
                + "</adag>\n";
        Path path = tempDir.resolve("energy-mirror.dax");
        Files.write(path, dax.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static PlatformProfile platform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("energy-mirror-platform");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(new PlatformProfile.VmSpec(
                0,
                1000.0,
                1,
                512,
                1_000L,
                10_000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                10.0,
                40.0));
        builder.addVm(new PlatformProfile.VmSpec(
                1,
                2000.0,
                1,
                512,
                1_000L,
                10_000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                20.0,
                120.0));
        return builder.build();
    }
}
