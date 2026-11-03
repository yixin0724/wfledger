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

/**
 * (AUD-F06, 2026-11-03) The generic campaign must apply the step-down Holm
 * correction within each comparison group's candidate family; before this fix only
 * the network study path corrected for multiplicity and the generic campaign
 * reported raw p values, contradicting the declared "Paired Wilcoxon + Holm"
 * discipline.
 *
 * <p>Fixture: one comparison group with one baseline and two candidates under a
 * common-root-seed plan on a heterogeneous platform. The second candidate runs the
 * baseline's own algorithm, so the bit-exact rerun contract guarantees identical
 * makespans per shared root seed: its paired differences are all zero and its
 * Wilcoxon p value is unavailable. Under the registered family convention that
 * untestable member still consumes correction budget, so the tested candidate's
 * adjusted p value must be min(1, 2 * p) with the declared family size 2, not
 * min(1, 1 * p).</p>
 *
 * <p>All expected relationships are contract arithmetic (Holm step-down on the
 * declared family), applied to the observed raw p value; the raw p value itself is
 * not asserted against a fixed number.</p>
 */
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
        // Family order follows plan declaration order of the non-baseline candidates.
        assertEquals("mct", comparisons.get(0).getCandidateCellId());
        assertEquals("fcfs-again", comparisons.get(1).getCandidateCellId());
        assertEquals(REPLICATIONS, comparisons.get(0).getMatchedRootSeedCount());
        assertEquals(REPLICATIONS, comparisons.get(1).getMatchedRootSeedCount());

        // The MCT candidate must diverge from FCFS on the heterogeneous platform, so
        // its paired test must be usable; otherwise the fixture no longer exercises
        // the tested-member branch.
        ExperimentCampaignSummary.ComparisonSummary tested = comparisons.get(0);
        assertTrue(
                tested.getPairedSignificance().getStatus().startsWith("AVAILABLE_"),
                "the fixture must produce a usable paired test, actual status: "
                        + tested.getPairedSignificance().getStatus());
        assertNotNull(tested.getPairedSignificance().getPValue());
        double rawP = tested.getPairedSignificance().getPValue().doubleValue();
        // Registered convention: multiplier base = declared family size (2), rank 0.
        assertEquals(
                Math.min(1.0, 2.0 * rawP),
                tested.getHolmAdjustedPValue().doubleValue(),
                0.0,
                "the untestable fcfs-again candidate must consume correction budget");
        assertEquals(
                Math.min(1.0, 2.0 * rawP) < PairedWilcoxonSignificance.DEFAULT_ALPHA,
                tested.isSignificantAfterHolmAtDefaultAlpha());

        // The self-identical candidate: all-zero paired differences, no p value, and
        // therefore no adjusted value and no Holm rejection.
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
