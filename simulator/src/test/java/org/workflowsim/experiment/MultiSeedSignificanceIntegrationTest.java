package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/**
 * R1 acceptance: the paired significance test is usable end to end for real algorithm
 * pairs under a multi-seed failure model.
 *
 * <p>FCFS (baseline) and READY_BATCH_MCT (candidate) each run 10 times under the same
 * common-root-seed plan. The scenario deliberately picks a combination on which the two
 * algorithms must diverge:</p>
 * <ul>
 * <li><b>heterogeneous platform</b> (16 VMs, MIPS 800-2300): on a homogeneous platform the
 * completion times of all online schedulers coincide and degenerate into the same schedule,
 * so every paired difference is zero and the significance test is unavailable;</li>
 * <li><b>thousand-task scale</b> (Montage_1000): on a small fixture most online schedulers
 * converge as well;</li>
 * <li><b>failure model</b> (Weibull arrivals, retry budget 64): supplies randomness
 * variance across seeds.</li>
 * </ul>
 *
 * <p>The campaign summary's {@code ComparisonSummary.getPairedSignificance()} must report
 * a usable status, a finite p-value and a consistent significance verdict. This test proves
 * that algorithm comparison reports carrying p-values are genuinely delivered.</p>
 */
class MultiSeedSignificanceIntegrationTest {

    private static final int REPLICATIONS = 10;

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void failureDrivenCampaignProducesPairedWilcoxonSignificance() throws Exception {
        String workflow = montage1000Path();
        List<Double> vmMips = new ArrayList<Double>();
        for (int vm = 0; vm < 16; vm++) {
            vmMips.add(Double.valueOf(800.0 + vm * 100.0));
        }
        PlatformProfile platform = PlatformProfiles.heterogeneousLocal("significance-platform", vmMips);
        FailureModelConfig failureModel = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][] {
                    {DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 100.0, 1.0)}
                })
                .maxTotalRetryJobs(64)
                .build();
        SeedPlan seeds =
                SeedPlan.derived(RandomizationDesign.COMMON_ROOT_SEEDS_NOT_EVENT_KEYED_CRN, 20260911L, REPLICATIONS);
        ExperimentPlan plan = ExperimentPlan.builder("multi-seed-significance")
                .addCell(cell(
                        "fcfs",
                        "fcfs",
                        true,
                        workflow,
                        platform,
                        seeds,
                        failureModel,
                        Parameters.SchedulingAlgorithm.FCFS))
                .addCell(cell(
                        "mct",
                        "ready-batch-mct",
                        false,
                        workflow,
                        platform,
                        seeds,
                        failureModel,
                        Parameters.SchedulingAlgorithm.READY_BATCH_MCT))
                .build();

        Log.disable();
        ExperimentCampaignResult result = new ExperimentCampaignExecutor().execute(plan);

        assertEquals(2 * REPLICATIONS, result.getRuns().size());
        assertEquals(1, result.getSummary().getComparisons().size());
        ExperimentCampaignSummary.ComparisonSummary comparison =
                result.getSummary().getComparisons().get(0);
        assertEquals(REPLICATIONS, comparison.getMatchedRootSeedCount());
        assertEquals("DESCRIPTIVE_ONLY_COMMON_ROOT_SEEDS_ARE_NOT_EVENT_KEYED_CRN", comparison.getInferenceStatus());

        PairedWilcoxonSignificance significance = comparison.getPairedSignificance();
        assertNotNull(significance);
        assertEquals(REPLICATIONS, significance.getPairedSampleCount());
        // On a heterogeneous platform at thousand-task scale the two schedules must diverge,
        // so the paired differences cannot all be zero.
        assertTrue(
                significance.getStatus().startsWith("AVAILABLE_"),
                "the acceptance scenario must produce a usable paired test, actual status: "
                        + significance.getStatus());
        assertNotNull(significance.getPValue());
        double p = significance.getPValue().doubleValue();
        assertTrue(p >= 0.0 && p <= 1.0, "p-value must lie in [0,1], actual: " + p);
        assertEquals(p < PairedWilcoxonSignificance.DEFAULT_ALPHA, significance.isSignificantAtDefaultAlpha());
        assertTrue(significance.getEffectiveSampleCount() >= 2);
        System.out.println("[R1] status=" + significance.getStatus()
                + " p=" + significance.getPValue()
                + " effectiveN=" + significance.getEffectiveSampleCount()
                + " medianDelta=" + significance.getMedianDifference());
    }

    private static ExperimentPlan.Cell cell(
            String id,
            String candidate,
            boolean baseline,
            String workflow,
            PlatformProfile platform,
            SeedPlan seeds,
            FailureModelConfig failureModel,
            Parameters.SchedulingAlgorithm algorithm) {
        SimulationConfig config = SimulationConfig.builder(workflow, 16)
                .schedulingAlgorithm(algorithm)
                .failureModel(failureModel)
                .build();
        return new ExperimentPlan.Cell(
                id,
                "shared-workflow",
                candidate,
                baseline,
                config,
                platform,
                seeds,
                Collections.singletonMap("purpose", "r1-acceptance"));
    }

    private static String montage1000Path() throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (root != null && !Files.exists(root.resolve("datasets/dax"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("Cannot locate repository root containing datasets/dax");
        }
        Path dax = root.resolve("datasets/dax/montage/n1000/Montage_1000.dax");
        if (!Files.isRegularFile(dax)) {
            throw new IllegalStateException("Missing required input: " + dax);
        }
        return dax.toString();
    }
}
