package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Cloudlet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.CrchConfig;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/**
 * FGCS-R5 (2026-11-24): hand-derived mid-execution failures and CRCH checkpoint overhead.
 *
 * <p>Root causes: (D1) a successful CRCH attempt ran the bare work, so checkpointing was free unless
 * the attempt failed; (D2) the failure model was evaluated only when an attempt completed, so a
 * failed attempt always ran its full length. A failure now ends the attempt at the first failure
 * arrival inside its compute window {@code [start, finish)}.
 *
 * <p>Common timing (SHARED, no input files, 0.1 s kernel tick): the 110 MI stage-in Job is raised to
 * the 0.11 s completion guard and the roots start at 0.21 s. Failure arrivals come from a Weibull
 * stream of shape 1e9, whose inter-arrival samples equal the scale within 1e-8 relative error, so the
 * i-th arrival is {@code i * scale}; times are compared at 1e-6 s.
 */
class MidExecutionFailureIntegrationTest {

    private static final double TIME = 1.0e-6;
    private static final double NEVER = 1.0e9;
    private static final double DETERMINISTIC_SHAPE = 1.0e9;

    @TempDir
    Path directory;

    /**
     * (a) 3000 MI at 1000 MIPS, λ = 1 s, γ = 0.1 s, no failure: 3 s of work take checkpoints after 1 s
     * and 2 s of progress (none at completion), so the attempt runs 3000 + 2·100 = 3200 MI, 3.2 s, and
     * the workflow ends at 0.21 + 3.2 = 3.41 s. The former model ran 3.0 s and ended at 3.21 s.
     */
    @Test
    void aSuccessfulCrchAttemptPaysItsCheckpointOverhead() throws Exception {
        SimulationReport report = run(
                "<job id=\"a\" name=\"a\" runtime=\"3\"/>",
                crch(FailureModelConfig.disabled()),
                platform(1000.0, 1000.0, 0.0, 0.0));

        SimulationReport.TaskOutcome task = attempt(report, 1, Cloudlet.SUCCESS);
        assertEquals(3200L, task.getEffectiveExecutionLengthMi());
        assertEquals(0.21, task.getStartTime(), TIME);
        assertEquals(3.41, task.getFinishTime(), TIME);
        assertEquals(3.41, report.getMakespan(), TIME);
    }

    /**
     * (b) Parent 4000 MI (child 1000 MI) on two 1000 MIPS VMs; VM 3 depth-1 arrivals at 3.0, 6.0, ...
     * The fresh parent costs 4000 + 3·100 MI = 4.3 s on either VM; the tie goes to VM 3, compute
     * [0.21, 4.51). The arrival 3.0 ends it there: 2.79 s elapsed, checkpoints complete every 1.1 s,
     * so floor(2.79 / 1.1) = 2 commit and save 2000 MI. At 3.0 the retry costs 2000 MI with one
     * checkpoint (2100 MI, 2.1 s) on VM 3 but 4.3 s on VM 8, so it resumes on VM 3 over [3.0, 5.1).
     * The child is released only by the successful retry: [5.1, 6.1), makespan 6.1.
     */
    @Test
    void aFailureEndsTheAttemptAndTheRetryResumesFromItsCheckpoints() throws Exception {
        Map<Integer, DistributionSpec[]> specs = new LinkedHashMap<Integer, DistributionSpec[]>();
        specs.put(3, new DistributionSpec[] {weibull(NEVER), weibull(3.0), weibull(NEVER)});
        specs.put(8, new DistributionSpec[] {weibull(NEVER), weibull(NEVER), weibull(NEVER)});
        SimulationReport report = run(
                "<job id=\"p\" name=\"p\" runtime=\"4\"/><job id=\"c\" name=\"c\" runtime=\"1\"/>"
                        + "<child ref=\"c\"><parent ref=\"p\"/></child>",
                crch(crchFailure(FailureParameters.FTCFailure.FAILURE_VM_JOB, specs)),
                platform(1000.0, 1000.0, 0.0, 0.0));

        SimulationReport.TaskOutcome failed = attempt(report, 1, Cloudlet.FAILED);
        SimulationReport.JobOutcome failedJob = job(report, failed.getJobId());
        assertEquals(3, failed.getVmId());
        assertEquals(4300L, failed.getEffectiveExecutionLengthMi());
        assertEquals(0.21, failed.getStartTime(), TIME);
        assertEquals(3.0, failed.getFinishTime(), TIME);
        assertEquals(3.0, failedJob.getFinishTime(), TIME);

        SimulationReport.TaskOutcome retry = attempt(report, 1, Cloudlet.SUCCESS);
        assertEquals(3, retry.getVmId());
        assertEquals(2100L, retry.getEffectiveExecutionLengthMi());
        assertEquals(3.0, retry.getStartTime(), TIME);
        assertEquals(5.1, retry.getFinishTime(), TIME);

        SimulationReport.TaskOutcome child = attempt(report, 2, Cloudlet.SUCCESS);
        assertEquals(5.1, child.getStartTime(), TIME);
        assertEquals(6.1, child.getFinishTime(), TIME);
        assertEquals(6.1, report.getMakespan(), TIME);
        assertEquals(1, report.getFailedJobs());
    }

    /**
     * (b2) Checkpoints are VM-local. VM 3 runs 1000 MIPS (arrivals every 3.0 s), VM 8 5000 MIPS.
     * Task 1 (5000 MI) costs 1.0 s on VM 8 versus 5.4 s on VM 3 and takes VM 8 over [0.21, 1.21);
     * task 2 (4000 MI) then gets VM 3 at 4.3 s, fails at 3.0 and saves 2000 MI there. At 3.0 a
     * resumption on VM 3 costs 2.1 s while a full re-execution on VM 8 costs 4000 / 5000 = 0.8 s with
     * no checkpoint, so the retry moves to VM 8 and executes the full 4000 MI over [3.0, 3.8).
     */
    @Test
    void aRetryOnAnotherVmReexecutesTheSavedWork() throws Exception {
        Map<Integer, DistributionSpec[]> specs = new LinkedHashMap<Integer, DistributionSpec[]>();
        specs.put(3, new DistributionSpec[] {weibull(3.0)});
        specs.put(8, new DistributionSpec[] {weibull(NEVER)});
        SimulationReport report = run(
                "<job id=\"b\" name=\"b\" runtime=\"5\"/><job id=\"a\" name=\"a\" runtime=\"4\"/>",
                crch(crchFailure(FailureParameters.FTCFailure.FAILURE_VM, specs)),
                platform(1000.0, 5000.0, 0.0, 0.0));

        SimulationReport.TaskOutcome first = attempt(report, 1, Cloudlet.SUCCESS);
        assertEquals(8, first.getVmId());
        assertEquals(1.21, first.getFinishTime(), TIME);

        SimulationReport.TaskOutcome failed = attempt(report, 2, Cloudlet.FAILED);
        assertEquals(3, failed.getVmId());
        assertEquals(3.0, failed.getFinishTime(), TIME);

        SimulationReport.TaskOutcome retry = attempt(report, 2, Cloudlet.SUCCESS);
        assertEquals(8, retry.getVmId());
        assertEquals(4000L, retry.getEffectiveExecutionLengthMi());
        assertEquals(3.0, retry.getStartTime(), TIME);
        assertEquals(3.8, retry.getFinishTime(), TIME);
        assertEquals(3.8, report.getMakespan(), TIME);
    }

    /**
     * (c) NOOP retry under FCFS, one 2000 MI task, 1000 MIPS VMs drawing 100 W idle / 200 W busy, VM 3
     * arrivals every 2.1 s. The attempt computes [0.21, 2.21) and the arrival 2.1 ends it: the failed
     * Job is busy 2.1 - 0.21 = 1.89 s. FCFS restarts the full 2000 MI on VM 3 over [2.1, 4.1); the
     * next arrival 4.2 lies outside it. VM 3 is busy 0.11 (stage-in) + 1.89 + 2.0 = 4.0 s of the 4.1 s
     * makespan, so it draws 100·4.1 + (200 - 100)·4.0 = 810 J; idle VM 8 draws 410 J. The former model ran the
     * failed attempt to 2.21, the retry to 4.21, and booked 4.21 s of VM 3 busy time.
     */
    @Test
    void aNoopFailedAttemptIsChargedOnlyUntilTheFailure() throws Exception {
        Map<Integer, DistributionSpec[]> specs = new LinkedHashMap<Integer, DistributionSpec[]>();
        specs.put(3, new DistributionSpec[] {weibull(2.1)});
        specs.put(8, new DistributionSpec[] {weibull(NEVER)});
        SimulationReport report =
                run("<job id=\"a\" name=\"a\" runtime=\"2\"/>", noop(specs), platform(1000.0, 1000.0, 100.0, 200.0));

        SimulationReport.TaskOutcome failed = attempt(report, 1, Cloudlet.FAILED);
        SimulationReport.JobOutcome failedJob = job(report, failed.getJobId());
        assertEquals(2.1, failed.getFinishTime(), TIME);
        assertEquals(1.89, failedJob.getFinishTime() - failedJob.getStartTime(), TIME);
        assertEquals(1.89, failedJob.getCpuTime(), TIME);

        SimulationReport.TaskOutcome retry = attempt(report, 1, Cloudlet.SUCCESS);
        assertEquals(3, retry.getVmId());
        assertEquals(2.1, retry.getStartTime(), TIME);
        assertEquals(4.1, retry.getFinishTime(), TIME);
        assertEquals(4.1, report.getMakespan(), TIME);
        assertEquals(4.0, report.getMetrics().getVmMetrics().get(3).getModeledBusyIntervalSeconds(), TIME);
        assertEquals(810.0, report.getEnergyAccounting().getVmEnergyJoules().get(3), 1.0e-3);
        assertEquals(410.0, report.getEnergyAccounting().getVmEnergyJoules().get(8), 1.0e-3);
    }

    /** (d) An arrival at 2.5 s lies after the compute window [0.21, 2.21): the attempt succeeds. */
    @Test
    void anArrivalAfterTheComputeWindowIsNotAFailure() throws Exception {
        Map<Integer, DistributionSpec[]> specs = new LinkedHashMap<Integer, DistributionSpec[]>();
        specs.put(3, new DistributionSpec[] {weibull(2.5)});
        specs.put(8, new DistributionSpec[] {weibull(NEVER)});
        SimulationReport report =
                run("<job id=\"a\" name=\"a\" runtime=\"2\"/>", noop(specs), platform(1000.0, 1000.0, 0.0, 0.0));

        assertEquals(0, report.getFailedJobs());
        assertEquals(2.21, attempt(report, 1, Cloudlet.SUCCESS).getFinishTime(), TIME);
    }

    private SimulationReport run(String body, Configurer configurer, PlatformProfile platform) throws Exception {
        Path workflow = PlanningRegressionFixtures.workflow(directory, "mid-failure.dax", body);
        SimulationConfig.Builder builder = SimulationConfig.builder(workflow.toString(), 2)
                .runtimeReferenceMips(1000.0)
                .randomSeed(7L);
        return new SimulationRunner().run(configurer.apply(builder).build(), platform);
    }

    private interface Configurer {
        SimulationConfig.Builder apply(SimulationConfig.Builder builder);
    }

    private static Configurer crch(FailureModelConfig failure) {
        return builder -> builder.schedulingAlgorithm(Parameters.SchedulingAlgorithm.CRCH)
                .failureModel(failure)
                .crchConfig(new CrchConfig(1.0, 0.1));
    }

    private static Configurer noop(Map<Integer, DistributionSpec[]> specs) {
        return builder -> builder.schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .failureModel(FailureModelConfig.builder()
                        .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                        .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                        .generatorMode(FailureParameters.FTCFailure.FAILURE_VM)
                        .distributionFamily(DistributionGenerator.DistributionFamily.WEIBULL)
                        .generatorSpecsByVmId(specs)
                        .maxTotalRetryJobs(3)
                        .build());
    }

    private static FailureModelConfig crchFailure(
            FailureParameters.FTCFailure mode, Map<Integer, DistributionSpec[]> specs) {
        return FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_CRCH)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_ALL)
                .generatorMode(mode)
                .distributionFamily(DistributionGenerator.DistributionFamily.WEIBULL)
                .generatorSpecsByVmId(specs)
                .maxTotalRetryJobs(3)
                .build();
    }

    private static DistributionSpec weibull(double scale) {
        return DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, scale, DETERMINISTIC_SHAPE);
    }

    private static PlatformProfile platform(double vm3Mips, double vm8Mips, double idleWatts, double maxWatts) {
        PlatformProfile.Builder builder = PlatformProfile.builder("mid-execution-failure")
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20));
        int[] ids = {3, 8};
        double[] mips = {vm3Mips, vm8Mips};
        for (int index = 0; index < ids.length; index++) {
            builder.addHost(new PlatformProfile.HostSpec(index, 1, mips[index], 2048, 10_000L, 1_000_000L))
                    .addVm(new PlatformProfile.VmSpec(
                            ids[index],
                            mips[index],
                            1,
                            512,
                            1000L,
                            10_000L,
                            "Xen",
                            PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                            null,
                            idleWatts,
                            maxWatts,
                            null,
                            null))
                    .pinVmToHost(ids[index], index);
        }
        return builder.build();
    }

    private static SimulationReport.TaskOutcome attempt(SimulationReport report, int taskId, int status) {
        List<SimulationReport.TaskOutcome> matches = new ArrayList<SimulationReport.TaskOutcome>();
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            if (task.getTaskId() == taskId && task.getTaskStatus() == status) {
                matches.add(task);
            }
        }
        assertTrue(
                matches.size() == 1,
                "expected exactly one attempt of task " + taskId + " with status " + status + ", found "
                        + matches.size());
        return matches.get(0);
    }

    private static SimulationReport.JobOutcome job(SimulationReport report, int jobId) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getJobId() == jobId) {
                return job;
            }
        }
        throw new AssertionError("No Job " + jobId);
    }
}
