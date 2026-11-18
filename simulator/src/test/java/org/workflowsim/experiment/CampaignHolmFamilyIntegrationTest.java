package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class CampaignHolmFamilyIntegrationTest {

    private static final int REPLICATIONS = 3;

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void genericCampaignAppliesHolmWithinTheCandidateFamily() throws Exception {
        String workflow = resourcePath("/dax/reproducibility-workflow.dax");
        List<Double> vmMips = new ArrayList<Double>();
        vmMips.add(Double.valueOf(800.0));
        vmMips.add(Double.valueOf(1600.0));
        PlatformProfile platform = PlatformProfiles.heterogeneousLocal("holm-family-platform", vmMips);
        SeedPlan seeds =
                SeedPlan.derived(RandomizationDesign.COMMON_ROOT_SEEDS_NOT_EVENT_KEYED_CRN, 20260902L, REPLICATIONS);
        ExperimentPlan plan = ExperimentPlan.builder("holm-family-campaign")
                .addCell(cell(
                        "fcfs-base", "baseline", true, workflow, platform, seeds, Parameters.SchedulingAlgorithm.FCFS))
                .addCell(cell(
                        "mct",
                        "candidate-mct",
                        false,
                        workflow,
                        platform,
                        seeds,
                        Parameters.SchedulingAlgorithm.READY_BATCH_MCT))
                .addCell(cell(
                        "fcfs-again",
                        "candidate-fcfs-again",
                        false,
                        workflow,
                        platform,
                        seeds,
                        Parameters.SchedulingAlgorithm.FCFS))
                .build();

        Log.disable();
        ExperimentCampaignResult result = new ExperimentCampaignExecutor().execute(plan);

        List<ExperimentCampaignSummary.ComparisonSummary> comparisons =
                result.getSummary().getComparisons();
        assertEquals(2, comparisons.size());

        assertEquals("mct", comparisons.get(0).getCandidateCellId());
        assertEquals("fcfs-again", comparisons.get(1).getCandidateCellId());
        assertEquals(REPLICATIONS, comparisons.get(0).getMatchedRootSeedCount());
        assertEquals(REPLICATIONS, comparisons.get(1).getMatchedRootSeedCount());

        ExperimentCampaignSummary.ComparisonSummary tested = comparisons.get(0);
        assertTrue(
                tested.getPairedSignificance().getStatus().startsWith("AVAILABLE_"),
                "the fixture must produce a usable paired test, actual status: "
                        + tested.getPairedSignificance().getStatus());
        assertNotNull(tested.getPairedSignificance().getPValue());
        double rawP = tested.getPairedSignificance().getPValue().doubleValue();

        assertEquals(
                Math.min(1.0, 2.0 * rawP),
                tested.getHolmAdjustedPValue().doubleValue(),
                0.0,
                "the untestable fcfs-again candidate must consume correction budget");
        assertEquals(
                Math.min(1.0, 2.0 * rawP) < PairedWilcoxonSignificance.DEFAULT_ALPHA,
                tested.isSignificantAfterHolmAtDefaultAlpha());

        ExperimentCampaignSummary.ComparisonSummary untestable = comparisons.get(1);
        assertEquals(
                "UNAVAILABLE_ALL_PAIRED_DIFFERENCES_ARE_ZERO",
                untestable.getPairedSignificance().getStatus());
        assertNull(untestable.getHolmAdjustedPValue());
        assertFalse(untestable.isSignificantAfterHolmAtDefaultAlpha());
    }

    private static ExperimentPlan.Cell cell(
            String id,
            String candidate,
            boolean baseline,
            String workflow,
            PlatformProfile platform,
            SeedPlan seeds,
            Parameters.SchedulingAlgorithm algorithm) {
        SimulationConfig config = SimulationConfig.builder(workflow, 2)
                .schedulingAlgorithm(algorithm)
                .build();
        return new ExperimentPlan.Cell(
                id,
                "holm-family",
                candidate,
                baseline,
                config,
                platform,
                seeds,
                Collections.singletonMap("purpose", "holm-family"));
    }

    private static String resourcePath(String resource) throws Exception {
        URL url = CampaignHolmFamilyIntegrationTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
