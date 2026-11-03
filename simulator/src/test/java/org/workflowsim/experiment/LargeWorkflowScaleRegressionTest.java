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

/**
 * Scale regression test of the algorithm tracks on a large workflow (Montage with 1000 tasks).
 *
 * <p>Motivation: the existing algorithm semantics tests all use small fixtures of at most 24
 * tasks, whereas research experiments usually run at hundreds to thousands of tasks. This test
 * brings thousand-task execution into the regression gate and verifies the correctness and
 * numerical stability of three representative tracks at scale:</p>
 * <ul>
 *   <li>Communication-aware static DAG planning (LOCAL_HEFT, LOCAL file system plus
 *       pre-execution transfer delay model);</li>
 *   <li>Online ready-batch scheduling (READY_BATCH_MINMIN and FCFS, SHARED file system);</li>
 *   <li>Determinism of repeated runs with the same configuration (golden value plus bit-identical
 *       reruns).</li>
 * </ul>
 *
 * <p><b>Assertion basis</b>: the golden makespan is locked by exact double equality (under a
 * deterministic simulation any numerical drift constitutes a regression); the test also asserts
 * that every logical task completes successfully, that no Job fails, and that VM mappings stay
 * within the legal range. The golden values are observations under the current platform semantics
 * and do not constitute a claim of reproduction of the paper or of a real platform.</p>
 *
 * <p><b>Dataset selection</b>: the online scheduling tracks use
 * {@code datasets/dax/montage/n1000/Montage_1000.dax} (the Pegasus standard DAX, 1000 tasks,
 * committed with the repository). The LOCAL planner track uses
 * {@code datasets/dax/epigenomics/n997/Epigenomics_997.dax}: under the flat file namespace of the
 * Pegasus Montage DAX many same-name files declare conflicting sizes (for example each mDiffFit
 * task has its own {@code fit.txt}), which the strict size guard of the LOCAL planner rejects -- a
 * deliberate platform boundary rather than a defect; Epigenomics_997 has unique file names and
 * zero conflicts.</p>
 */
final class LargeWorkflowScaleRegressionTest {

    /**
     * Work-conserving execution + time-stamped LOCAL replicas, 16 VMs, seed 42.
     * The pre-repair 253528.02533242913 had five CPU-work lower-bound violations.
     * Independent old/new binary controls found zero after repair; the invariant below
     * guards this value rather than merely accepting a newly observed golden.
     */
    private static final double LOCAL_HEFT_GOLDEN_MAKESPAN = 253528.51872582204;
    /** Golden value: READY_BATCH_MINMIN + SHARED file system, 16 VMs, seed 42. */
    private static final double READY_BATCH_MINMIN_GOLDEN_MAKESPAN = 1223.7949999999978;
    /** Golden value: FCFS + SHARED file system, 16 VMs, seed 42. */
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

    /** Robustly locate the repository root: user.dir under Maven surefire is the simulator/ module directory. */
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
        // Montage_1000 does not apply to the LOCAL planner: under the flat file namespace of the
        // Pegasus Montage DAX many same-name files (such as each mDiffFit task's own fit.txt) declare
        // conflicting sizes, which the planner's strict size guard rejects (a deliberate platform
        // boundary, see AbstractLocalCommPlanningAlgorithm). Epigenomics_997 has unique file names
        // and zero conflicts, so it serves as the large-scale sample of the LOCAL track.
        SimulationConfig config = SimulationConfig.builder(epigenomicsPath(), VM_COUNT)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .randomSeed(42L)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform());

        // 997 logical tasks; Job count = 997 + 1 historical stage-in bootstrap Job (the known COMM-2
        // boundary).
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

        // 1000 logical tasks; Job count = 1000 + 1 historical stage-in bootstrap Job (the known COMM-2
        // boundary).
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
            // This fixture has no matrix, no failure and no in-envelope stage-in.
            // Derive the bound from declared MI and capacity, not a production metric helper.
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
        // Job count = logical task count + 1 historical stage-in bootstrap Job (the known COMM-2
        // boundary; not counted as a logical task).
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
