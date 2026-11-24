package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.CrchConfig;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/** FGCS-R4 (2026-11-24): hand-derived work accounting of failed attempts and their retries. */
class RetryWorkAccountingIntegrationTest {

    @TempDir
    Path directory;

    /**
     * Two independent 4000 MI tasks, tiers 1000/500 MIPS, MIN_POWER: every complete attempt runs its
     * declared 4000 MI at 500 MIPS, hence 8 s. Round robin places stage-in on VM 3, then the tasks on
     * VM 8 and VM 3, both over [0.21, 8.21). FGCS-R5 (2026-11-24): VM 3 arrivals come every 8.1 s, so the
     * arrival 8.1 ends task 2's attempt there; VM 8 is still busy, so the retry restarts on VM 3 over
     * [8.1, 16.1), before the next arrival 16.2. The former retry started from the already tier-scaled
     * 8000 MI and took 16 s.
     */
    @Test
    void tieredRetryRestartsFromTheDeclaredWork() throws Exception {
        SimulationReport report = new SimulationRunner()
                .run(
                        config(
                                        "<job id=\"a\" name=\"a\" runtime=\"4\"/>"
                                                + "<job id=\"b\" name=\"b\" runtime=\"4\"/>",
                                        false,
                                        8.1)
                                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_ROUNDROBIN)
                                .frequencyPolicy(SimulationConfig.FrequencyPolicy.MIN_POWER)
                                .build(),
                        platform(true));
        List<SimulationReport.JobOutcome> attempts = attempts(report);
        assertEquals(1, report.getFailedJobs(), "the fixture must exercise exactly one retry");
        assertEquals(3, attempts.size());
        for (SimulationReport.JobOutcome attempt : attempts) {
            if (attempt.getStatus() == org.cloudbus.cloudsim.Cloudlet.SUCCESS) {
                assertEquals(8.0, attempt.getFinishTime() - attempt.getStartTime(), 1.0e-6);
            } else {
                assertEquals(8.1, attempt.getFinishTime(), 1.0e-6);
            }
        }
        assertEquals(16.1, report.getMakespan(), 1.0e-6);
    }

    /**
     * 200 MB at 20 MB/s is a 10 s read, then 4000 MI at 1000 MIPS with λ = 1 s, γ = 0.1 s: three
     * checkpoints, 4300 MI, compute [10.21, 14.51). FGCS-R5 (2026-11-24): the VM 3 arrival 13.5 ends the
     * attempt 3.29 s into compute, committing floor(3.29 / 1.1) = 2 checkpoints, 2000 MI. The retry
     * resumes on VM 3 with 2000 MI and one checkpoint, 2100 MI, and its compute [23.5, 25.6) ends before
     * the next arrival 27. Crediting the read (13.29 s) would commit all 3 checkpoints, 1000 MI retried.
     */
    @Test
    void crchCreditsComputeTimeButNotTheInputRead() throws Exception {
        SimulationReport report = new SimulationRunner()
                .run(
                        config(
                                        "<job id=\"a\" name=\"a\" runtime=\"4\">"
                                                + "<uses file=\"x\" link=\"input\" size=\"200000000\"/></job>",
                                        true,
                                        13.5)
                                .build(),
                        platform(false));
        assertEquals(2100L, lastTask(report).getEffectiveExecutionLengthMi());
        assertEquals(1, report.getFailedJobs());
    }

    /**
     * FGCS-R5 (2026-11-24): the failure instant is fixed inside the compute window at submission, so a
     * post-processing delay cannot extend the credited window. The delay is a deterministic 5 s
     * (Weibull shape 1e9) on every returned Job: the stage-in returns at 0.21 + 5 = 5.21, so the root
     * computes 4300 MI over [5.21, 9.51). The VM 3 arrival 4.0 precedes it and is consumed; the arrival
     * 8.0 ends it after 2.79 s, committing floor(2.79 / 1.1) = 2 checkpoints, 2000 MI. The failed attempt
     * returns at 8.0 + 5 = 13.0; at 13.0 the retry costs 2100 MI (2.1 s) on VM 3 versus 4300 MI on VM 8,
     * so it resumes on VM 3 over [13.0, 15.1), after the consumed arrival 12.0 and before 16.0. Crediting
     * the delay (13.0 - 5.21 = 7.79 s) would commit all 3 checkpoints and retry 1000 MI.
     */
    @Test
    void crchDoesNotCreditThePostProcessingDelay() throws Exception {
        OverheadModelConfig overhead = OverheadModelConfig.builder()
                .postDelays(Collections.singletonMap(
                        0, DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 5.0, 1.0e9)))
                .build();
        SimulationReport report = new SimulationRunner()
                .run(
                        config("<job id=\"a\" name=\"a\" runtime=\"4\"/>", true, 4.0)
                                .overheadModel(overhead)
                                .build(),
                        platform(false));
        assertEquals(2100L, lastTask(report).getEffectiveExecutionLengthMi());
        assertEquals(13.0, lastTask(report).getStartTime(), 1.0e-6);
        assertEquals(15.1, lastTask(report).getFinishTime(), 1.0e-6);
        assertEquals(1, report.getFailedJobs());
    }

    /** Saved MI would mix tier speed and nominal speed, so the combination is rejected up front. */
    @Test
    void crchWithFrequencyTiersIsRejected() throws Exception {
        SimulationConfig config =
                config("<job id=\"a\" name=\"a\" runtime=\"4\"/>", true, 4.0).build();
        assertThrows(SimulationConfigurationException.class, () -> new SimulationRunner().run(config, platform(true)));
    }

    /** Deterministic VM 3 arrivals every {@code vm3ArrivalSeconds}; Weibull shape 1e9 fixes each sample. */
    private SimulationConfig.Builder config(String body, boolean crch, double vm3ArrivalSeconds) throws Exception {
        Path workflow = PlanningRegressionFixtures.workflow(directory, "retry.dax", body);
        Map<Integer, DistributionSpec[]> specs = new LinkedHashMap<Integer, DistributionSpec[]>();
        specs.put(3, new DistributionSpec[] {
            DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, vm3ArrivalSeconds, 1.0e9)
        });
        specs.put(8, new DistributionSpec[] {
            DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 1.0e9, 1.0e9)
        });
        SimulationConfig.Builder builder = SimulationConfig.builder(workflow.toString(), 2)
                .runtimeReferenceMips(1000.0)
                .randomSeed(7L)
                .failureModel(FailureModelConfig.builder()
                        .clusteringAlgorithm(
                                crch
                                        ? FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_CRCH
                                        : FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                        .monitorMode(
                                crch
                                        ? FailureParameters.FTCMonitor.MONITOR_ALL
                                        : FailureParameters.FTCMonitor.MONITOR_NONE)
                        .generatorMode(FailureParameters.FTCFailure.FAILURE_VM)
                        .distributionFamily(DistributionGenerator.DistributionFamily.WEIBULL)
                        .generatorSpecsByVmId(specs)
                        .maxTotalRetryJobs(3)
                        .build());
        if (crch) {
            builder.schedulingAlgorithm(Parameters.SchedulingAlgorithm.CRCH).crchConfig(new CrchConfig(1.0, 0.1));
        }
        return builder;
    }

    private static PlatformProfile platform(boolean tiered) {
        PlatformProfile.Builder builder =
                PlatformProfile.builder("retry-work").storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20));
        int[] ids = {3, 8};
        for (int index = 0; index < ids.length; index++) {
            List<PlatformProfile.FrequencyTier> tiers = tiered
                    ? Arrays.asList(
                            new PlatformProfile.FrequencyTier(1000.0, 100.0, 400.0),
                            new PlatformProfile.FrequencyTier(500.0, 50.0, 120.0))
                    : null;
            builder.addHost(new PlatformProfile.HostSpec(index, 1, 1000.0, 2048, 10_000L, 1_000_000L))
                    .addVm(new PlatformProfile.VmSpec(
                            ids[index],
                            1000.0,
                            1,
                            512,
                            1000L,
                            10_000L,
                            "Xen",
                            PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                            null,
                            0.0,
                            0.0,
                            null,
                            tiers))
                    .pinVmToHost(ids[index], index);
        }
        return builder.build();
    }

    private static List<SimulationReport.JobOutcome> attempts(SimulationReport report) {
        List<SimulationReport.JobOutcome> result = new ArrayList<SimulationReport.JobOutcome>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getTierMips() != null) {
                result.add(job);
            }
        }
        return result;
    }

    private static SimulationReport.TaskOutcome lastTask(SimulationReport report) {
        return report.getTasks().get(report.getTasks().size() - 1);
    }
}
