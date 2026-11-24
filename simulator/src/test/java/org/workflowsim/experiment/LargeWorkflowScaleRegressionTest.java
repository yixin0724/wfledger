package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

final class LargeWorkflowScaleRegressionTest {

    private static final double LOCAL_HEFT_GOLDEN_MAKESPAN = 253528.51872582204;

    /**
     * Golden regression (drift detector, not a correctness oracle). FGCS-R3 (2026-11-24): moved
     * from 1223.7949999999978 because ready-batch estimates now include the charged SHARED read
     * (B1) and a round contains all same-instant releases (B2); the hand-derived proofs are in
     * ReadyBatchRoundSemanticsIntegrationTest.
     */
    private static final double READY_BATCH_MINMIN_GOLDEN_MAKESPAN = 1227.6469999999981;

    private static final double FCFS_GOLDEN_MAKESPAN = 1223.8519999999985;

    private static final int VM_COUNT = 16;
    private static final int MONTAGE_1000_TASK_COUNT = 1000;
    private static final int EPIGENOMICS_997_TASK_COUNT = 997;

    private static String workflowPath;

    @BeforeEach
    void disableLogging() {
        Log.disable();
    }

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    static String datasetWorkflowPath() throws Exception {
        if (workflowPath == null) {
            Path root =
                    Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            while (root != null && !Files.exists(root.resolve("datasets/dax"))) {
                root = root.getParent();
            }
            if (root == null) {
                throw new IllegalStateException("Cannot locate repository root containing datasets/dax");
            }
            Path dax = root.resolve("datasets/dax/montage/n1000/Montage_1000.dax");
            if (!Files.isRegularFile(dax)) {
                throw new IllegalStateException("Missing required scale regression input: " + dax);
            }
            workflowPath = dax.toString();
        }
        return workflowPath;
    }

    @Test
    void localHeftCompletesEpigenomics997WithGoldenMakespan() throws Exception {

        SimulationConfig config = SimulationConfig.builder(epigenomicsPath(), VM_COUNT)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .randomSeed(42L)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform());

        assertCompleteWorkflow(report, EPIGENOMICS_997_TASK_COUNT);
        assertLocalComputeConservation(report);
        assertEquals(
                LOCAL_HEFT_GOLDEN_MAKESPAN,
                report.getMakespan(),
                "LOCAL_HEFT Epigenomics_997 makespan" + " golden value drift");
        assertVmMappingsInPlatformRange(report);
    }

    @Test
    void readyBatchMinMinCompletesMontage1000WithGoldenMakespan() throws Exception {
        SimulationConfig config = SimulationConfig.builder(datasetWorkflowPath(), VM_COUNT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN)
                .randomSeed(42L)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform());

        assertCompleteWorkflow(report, MONTAGE_1000_TASK_COUNT);
        assertEquals(
                READY_BATCH_MINMIN_GOLDEN_MAKESPAN,
                report.getMakespan(),
                "READY_BATCH_MINMIN Montage_1000 makespan golden value drift");
        assertVmMappingsInPlatformRange(report);
    }

    @Test
    void fcfsCompletesMontage1000WithGoldenMakespan() throws Exception {
        SimulationConfig config = SimulationConfig.builder(datasetWorkflowPath(), VM_COUNT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(42L)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform());

        assertCompleteWorkflow(report, MONTAGE_1000_TASK_COUNT);
        assertEquals(FCFS_GOLDEN_MAKESPAN, report.getMakespan(), "FCFS Montage_1000 makespan golden value drift");
        assertVmMappingsInPlatformRange(report);
    }

    @Test
    void repeatedLargeScaleRunsAreBitwiseDeterministic() throws Exception {
        SimulationConfig config = SimulationConfig.builder(datasetWorkflowPath(), VM_COUNT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN)
                .randomSeed(42L)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .build();

        SimulationReport first = new SimulationRunner().run(config, platform());
        SimulationReport second = new SimulationRunner().run(config, platform());

        assertEquals(first.getMakespan(), second.getMakespan(), "rerun makespan must be bit-identical");
        assertEquals(first.getTotalJobs(), second.getTotalJobs());
        assertEquals(first.getSuccessfulJobs(), second.getSuccessfulJobs());
        assertEquals(first.getTasks().size(), second.getTasks().size());
        for (int i = 0; i < first.getJobs().size(); i++) {
            assertEquals(
                    first.getJobs().get(i).getVmId(),
                    second.getJobs().get(i).getVmId(),
                    "VM mapping of Job " + i + " drifted across reruns");
        }
    }

    private static void assertLocalComputeConservation(SimulationReport report) {
        java.util.Map<Integer, Double> mips = new java.util.HashMap<>();
        for (PlatformProfile.VmSpec vm : report.getPlatform().getVms()) {
            mips.put(vm.getId(), vm.getMips());
        }
        java.util.Map<Integer, SimulationReport.JobOutcome> jobs = new java.util.HashMap<>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            jobs.put(job.getJobId(), job);
        }
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            SimulationReport.JobOutcome job = jobs.get(task.getJobId());
            double speed = mips.get(job.getVmId());

            double required = task.getLengthMi() / speed;
            double quantum = 1.0 / speed + 1e-8;
            double observed = job.getFinishTime() - job.getStartTime();
            assertTrue(
                    observed + quantum >= required,
                    "Task " + task.getTaskId() + " used " + observed + "s but needs " + required + "s");
            assertTrue(
                    task.getFinishTime() <= job.getFinishTime() + quantum,
                    "Modeled compute must fit inside its observed Job, apart from one MI rounding");
        }
    }

    private static PlatformProfile platform() {
        return PlatformProfiles.homogeneousLocal("scale-regression-platform", VM_COUNT);
    }

    private static String epigenomicsPath() throws Exception {
        Path root = repositoryRoot();
        Path dax = root.resolve("datasets/dax/epigenomics/n997/Epigenomics_997.dax");
        if (!Files.isRegularFile(dax)) {
            throw new IllegalStateException("Missing required scale regression input: " + dax);
        }
        return dax.toString();
    }

    private static Path repositoryRoot() {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (root != null && !Files.exists(root.resolve("datasets/dax"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("Cannot locate repository root containing datasets/dax");
        }
        return root;
    }

    private static void assertCompleteWorkflow(SimulationReport report, int expectedTaskCount) {
        assertEquals(expectedTaskCount, report.getTasks().size());

        assertEquals(expectedTaskCount + 1, report.getTotalJobs());
        assertEquals(expectedTaskCount + 1, report.getSuccessfulJobs());
        assertEquals(0, report.getFailedJobs());
        assertTrue(report.getMakespan() > 0.0 && Double.isFinite(report.getMakespan()));
    }

    private static void assertVmMappingsInPlatformRange(SimulationReport report) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            assertTrue(
                    job.getVmId() >= 0 && job.getVmId() < VM_COUNT,
                    "Job VM mapping outside the platform" + " range: " + job.getVmId());
        }
    }
}
