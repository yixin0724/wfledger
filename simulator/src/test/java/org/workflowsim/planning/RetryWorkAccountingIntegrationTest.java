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

class RetryWorkAccountingIntegrationTest {

    @TempDir
    Path directory;

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

    @Test
    void crchDoesNotCreditThePostProcessingDelay() throws Exception {
        OverheadModelConfig overhead = OverheadModelConfig.builder()
                .postDelays(Collections.singletonMap(
                        0, DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 5.0, 1.0e300)))
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

    @Test
    void crchWithFrequencyTiersIsRejected() throws Exception {
        SimulationConfig config =
                config("<job id=\"a\" name=\"a\" runtime=\"4\"/>", true, 4.0).build();
        assertThrows(SimulationConfigurationException.class, () -> new SimulationRunner().run(config, platform(true)));
    }

    private SimulationConfig.Builder config(String body, boolean crch, double vm3ArrivalSeconds) throws Exception {
        Path workflow = PlanningRegressionFixtures.workflow(directory, "retry.dax", body);
        Map<Integer, DistributionSpec[]> specs = new LinkedHashMap<Integer, DistributionSpec[]>();
        specs.put(3, new DistributionSpec[] {
            DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, vm3ArrivalSeconds, 1.0e300)
        });
        specs.put(8, new DistributionSpec[] {
            DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 1.0e9, 1.0e300)
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
