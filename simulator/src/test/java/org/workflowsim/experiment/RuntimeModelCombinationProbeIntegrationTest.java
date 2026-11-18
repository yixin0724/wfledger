package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class RuntimeModelCombinationProbeIntegrationTest {

    private static final long OVERHEAD_SEED = 77L;

    private static final long FAILURE_SEED = 20260904L;

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void seededPostDelayOverheadExtendsJobReturnTimingAndReplaysExactly() throws Exception {
        OverheadModelConfig overhead = OverheadModelConfig.builder()
                .postDelays(Collections.singletonMap(
                        0, DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 1.0, 100.0)))
                .build();
        SimulationConfig noOverhead = SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(OVERHEAD_SEED)
                .build();
        SimulationConfig withOverhead = SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(OVERHEAD_SEED)
                .overheadModel(overhead)
                .build();

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        SimulationReport baseline = runner.run(noOverhead, PlatformProfiles.homogeneousLocal("post-delay-baseline", 1));
        SimulationReport first = runner.run(withOverhead, PlatformProfiles.homogeneousLocal("post-delay-probe", 1));
        SimulationReport second = runner.run(withOverhead, PlatformProfiles.homogeneousLocal("post-delay-probe", 1));

        List<Double> firstDelays = postDelays(first);
        assertFalse(firstDelays.isEmpty(), "every returned compute job must record a post-processing delay");
        for (Double delay : firstDelays) {
            assertTrue(delay.doubleValue() > 0.0, "the depth-0 default entry must draw a positive delay");
        }
        for (Double delay : postDelays(baseline)) {
            assertEquals(0.0, delay.doubleValue(), 0.0, "post-processing delay must be zero without overhead");
        }
        assertTrue(first.getMakespan() > baseline.getMakespan(), "post-delay postpones job return: makespan rises");
        assertEquals(postDelays(first), postDelays(second), "delay sampling must replay bitwise under a fixed seed");
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0);
    }

    @Test
    void failureModelCombinesWithQueueOverheadAndRemainsReplayable() throws Exception {
        FailureModelConfig failureModel = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][] {
                    {DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 0.1, 1.0)}
                })
                .maxTotalRetryJobs(16)
                .build();
        OverheadModelConfig overhead = OverheadModelConfig.builder()
                .queueDelays(Collections.singletonMap(
                        0, DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 1.0, 100.0)))
                .build();
        SimulationConfig combined = SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(FAILURE_SEED)
                .failureModel(failureModel)
                .overheadModel(overhead)
                .build();

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        SimulationReport first = runner.run(combined, PlatformProfiles.homogeneousLocal("failure-overhead", 1));
        SimulationReport second = runner.run(combined, PlatformProfiles.homogeneousLocal("failure-overhead", 1));

        assertTrue(first.getFailedJobs() > 0, "the fixed-seed fixture must trigger at least one retry");
        assertEquals(3, first.getFailedJobs(), "seed-scan measured golden value: 3 failed compute jobs");
        assertEquals(3, first.getMetrics().getRetryJobCreatedCount(), "seed-scan measured golden value: 3 retries");
        assertEquals(5.600810343783252, first.getMakespan(), 1.0e-9, "seed-scan measured golden value: makespan");
        for (Double delay : queueDelays(first)) {
            assertTrue(delay.doubleValue() > 0.0, "queue delay must still hold with failure and overhead");
        }
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0);
        assertEquals(
                first.getMetrics().getRetryJobCreatedCount(),
                second.getMetrics().getRetryJobCreatedCount());
        assertEquals(queueDelays(first), queueDelays(second));
    }

    @Test
    void vmCostModelCombinesWithPostDelayOverheadWithoutDistortingBilling() throws Exception {
        PlatformProfile platform = pricedPlatform();
        OverheadModelConfig overhead = OverheadModelConfig.builder()
                .postDelays(Collections.singletonMap(
                        0, DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 1.0, 100.0)))
                .build();
        SimulationConfig vmCostOnly = SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(OVERHEAD_SEED)
                .costModel(Parameters.CostModel.VM)
                .build();
        SimulationConfig vmCostWithOverhead = SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(OVERHEAD_SEED)
                .costModel(Parameters.CostModel.VM)
                .overheadModel(overhead)
                .build();

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        SimulationReport costOnly = runner.run(vmCostOnly, platform);
        SimulationReport withOverhead = runner.run(vmCostWithOverhead, platform);

        assertEquals(costOnly.getJobs().size(), withOverhead.getJobs().size());

        for (SimulationReport.JobOutcome job : withOverhead.getJobs()) {
            assertEquals(job.getCpuTime() * 5.0, job.getModeledProcessingCost(), 1.0e-12, "VM billing rate stays 5.0");
        }
        for (Double delay : postDelays(withOverhead)) {
            assertTrue(delay.doubleValue() > 0.0);
        }
        assertTrue(withOverhead.getMakespan() > costOnly.getMakespan());
    }

    private static PlatformProfile pricedPlatform() {
        PlatformProfile.CostSpec vmCosts = new PlatformProfile.CostSpec(5.0, 0.0, 0.0, 0.0);
        PlatformProfile.CostSpec datacenterCosts = new PlatformProfile.CostSpec(2.0, 0.0, 0.0, 0.0);
        return PlatformProfile.builder("priced-probe-platform")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 1024, 10_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(
                        0,
                        1000.0,
                        1,
                        512,
                        1000L,
                        10_000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        vmCosts))
                .costs(datacenterCosts)
                .build();
    }

    private static String workflow() throws Exception {
        URL url = RuntimeModelCombinationProbeIntegrationTest.class.getResource("/wfcommons/minimum-runtime.json");
        if (url == null) {
            throw new IllegalStateException("Missing WfCommons test fixture");
        }
        return Paths.get(url.toURI()).toString();
    }

    private static List<Double> postDelays(SimulationReport report) {
        List<Double> delays = new ArrayList<Double>();
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.JOB_RETURNED) {
                delays.add(((Number) event.getAttributes().get("postDelaySeconds")).doubleValue());
            }
        }
        return delays;
    }

    private static List<Double> queueDelays(SimulationReport report) {
        List<Double> delays = new ArrayList<Double>();
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.JOB_DISPATCHED) {
                delays.add(((Number) event.getAttributes().get("queueDelaySeconds")).doubleValue());
            }
        }
        return delays;
    }
}
