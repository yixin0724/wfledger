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

class MidExecutionFailureIntegrationTest {

    private static final double TIME = 1.0e-6;
    private static final double NEVER = 1.0e9;
    private static final double DETERMINISTIC_SHAPE = 1.0e300;

    @TempDir
    Path directory;

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
