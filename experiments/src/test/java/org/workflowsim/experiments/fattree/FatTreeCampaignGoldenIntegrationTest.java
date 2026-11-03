package org.workflowsim.experiments.fattree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.utils.Parameters;

/**
 * Campaign sampling values, repeated-run and structural-contract regression under the
 * current model.
 *
 * <p>The fixture uses DAX-normalized workload, no explicit cost matrix, seed 91 and the
 * declared 3/4 host platforms; it is not mixed with the simulator's paper cost-matrix
 * fixture. The value, ranking, monotonicity and identity assertions below apply only to
 * the inputs and parameters of each test; they do not entail invariants for all DAGs or
 * new rankings for the complete historical study.</p>
 *
 * <p>For the historical parameters and the provenance of the retained results see
 * {@code experiments/studies/fattree-scheduling-campaign/RETENTION.md}.
 * This test maintains the current regression values and does not overwrite the
 * historical numeric artifacts in that directory.</p>
 */
class FatTreeCampaignGoldenIntegrationTest {

    private static final String V1 =
            DataMovementModel.preExecutionTransferDelayV1().getKind().name();
    private static final String R2 = DataMovementModel.preExecutionTransferDelayWithContentionV1()
            .getKind()
            .name();
    private static final String R6 =
            DataMovementModel.fatTreeContentionV1().getKind().name();
    private static final String HEFT = Parameters.PlanningAlgorithm.LOCAL_HEFT.name();
    private static final String CPOP = Parameters.PlanningAlgorithm.LOCAL_CPOP.name();
    private static final String RANDOM = Parameters.PlanningAlgorithm.RANDOM.name();
    private static final String PSO = Parameters.PlanningAlgorithm.PSO.name();
    private static final String HEFT_EXAMPLE = "heft-paper-example";
    private static final String CYBERSHAKE_N50 = "cybershake-n50";
    private static final String CYBERSHAKE_N100 = "cybershake-n100";
    private static final String BASELINE = "baseline-k4-full";
    private static final String A4 = "A4-k4-full-link10x";

    /** Golden makespan of the 12 paper-example combinations (seconds, campaign configuration). */
    @Test
    void paperExampleGoldenMakespansMatchCampaignConfiguration() throws Exception {
        Log.disable();
        FatTreeSchedulingCampaignExecutor.CampaignResults results = runSubset(false, false);
        assertMakespan(results, HEFT_EXAMPLE, HEFT, V1, 5123.1);
        assertMakespan(results, HEFT_EXAMPLE, HEFT, R2, 5195.1);
        assertMakespan(results, HEFT_EXAMPLE, HEFT, R6, 5738.1);
        assertMakespan(results, HEFT_EXAMPLE, CPOP, V1, 5141.1);
        assertMakespan(results, HEFT_EXAMPLE, CPOP, R2, 5212.1);
        assertMakespan(results, HEFT_EXAMPLE, CPOP, R6, 5902.1);
        assertMakespan(results, HEFT_EXAMPLE, RANDOM, V1, 6159.1);
        assertMakespan(results, HEFT_EXAMPLE, RANDOM, R2, 6281.1);
        assertMakespan(results, HEFT_EXAMPLE, RANDOM, R6, 7206.1);
        assertMakespan(results, HEFT_EXAMPLE, PSO, V1, 6132.1);
        assertMakespan(results, HEFT_EXAMPLE, PSO, R2, 6254.1);
        assertMakespan(results, HEFT_EXAMPLE, PSO, R6, 7262.1);
        // R10 reuses the R8 parameters 0.125/1.25 MB/s; the CPOP and contention columns
        // are locked against the corrected actual execution.
    }

    /** Contention flips the per-DAG winner of cybershake to PSO; the paper example keeps HEFT. */
    @Test
    void contentionFlipsCybershakeWinnersToPso() throws Exception {
        Log.disable();
        FatTreeSchedulingCampaignExecutor.CampaignResults results = runSubset(false, false);
        Map<String, Map<String, String>> winners = results.perDagWinners();
        assertEquals(HEFT, winners.get(V1).get(CYBERSHAKE_N50));
        assertEquals(PSO, winners.get(R2).get(CYBERSHAKE_N50));
        assertEquals(PSO, winners.get(R6).get(CYBERSHAKE_N50));
        // The R10-era core chose PSO here. Isolated old/new core controls show that
        // time-aware LOCAL data planning changes HEFT's mapping/schedule and restores
        // a V1-to-contention change of winner. Do not preserve a bug-dependent ranking.
        assertEquals(HEFT, winners.get(V1).get(CYBERSHAKE_N100));
        assertMakespan(results, CYBERSHAKE_N100, HEFT, V1, 1111837.479876);
        assertMakespan(results, CYBERSHAKE_N100, PSO, V1, 1112378.121047);
        assertTrue(results.makespan(CYBERSHAKE_N100, HEFT, V1) < results.makespan(CYBERSHAKE_N100, PSO, V1));
        assertEquals(PSO, winners.get(R2).get(CYBERSHAKE_N100));
        assertEquals(PSO, winners.get(R6).get(CYBERSHAKE_N100));
        for (String model : new String[] {V1, R2, R6}) {
            assertEquals(
                    HEFT,
                    winners.get(model).get(HEFT_EXAMPLE),
                    model + ": the paper example winner must always be LOCAL_HEFT");
        }
        // Golden makespans (the direct numeric evidence of the flip, seconds). After
        // re-calibration, R6 for N50 PSO recovers a small slowdown of the binding link
        // (587921.6099 &lt; R2 587924.0247, a noise-level crossing already covered by the
        // weak-monotonicity diagnostic); for N100 PSO, R6 equals R2 exactly (the link
        // layer adds no further constraint on that mapping).
        assertMakespan(results, CYBERSHAKE_N50, PSO, R2, 587924.0247);
        assertMakespan(results, CYBERSHAKE_N50, PSO, R6, 587921.1503);
        assertMakespan(results, CYBERSHAKE_N50, HEFT, R2, 588010.008326);
        assertMakespan(results, CYBERSHAKE_N100, PSO, R2, 1189487.4983);
        assertMakespan(results, CYBERSHAKE_N100, PSO, R6, 1189487.4983);
        assertMakespan(results, CYBERSHAKE_N100, CPOP, V1, 1155577.5672);
    }

    /** Weak monotonicity: strict on the paper example; crossings inside the subset must be noise-level. */
    @Test
    void weakMonotonicityStrictOnPaperExampleAndNoiseLevelElsewhere() throws Exception {
        Log.disable();
        FatTreeSchedulingCampaignExecutor.CampaignResults results = runSubset(false, false);
        for (String planner : new String[] {HEFT, CPOP, RANDOM, PSO}) {
            double a = results.makespan(HEFT_EXAMPLE, planner, V1);
            double b = results.makespan(HEFT_EXAMPLE, planner, R2);
            double c = results.makespan(HEFT_EXAMPLE, planner, R6);
            assertTrue(
                    b >= a && c >= b,
                    planner + ": weak monotonicity must be strict on the paper example " + a + "/" + b + "/" + c);
        }
        for (Map<String, Object> violation : results.monotonicityDiagnostics()) {
            double deviation = ((Double) violation.get("maxRelativeDeviation")).doubleValue();
            assertTrue(
                    deviation <= 1.0e-4,
                    "weak-monotonicity crossings inside the subset must be noise-level "
                            + "(<= 1e-4 relative deviation): " + violation);
        }
    }

    /** Determinism: two runs of the same configuration yield bit-identical main-matrix records. */
    @Test
    void subsetRunsAreDeterministic() throws Exception {
        Log.disable();
        FatTreeSchedulingCampaignExecutor.CampaignResults first = runSubset(false, false);
        FatTreeSchedulingCampaignExecutor.CampaignResults second = runSubset(false, false);
        assertEquals(first.mainMatrix, second.mainMatrix, "the two runs must yield a bit-identical main matrix");
    }

    /**
     * 3-host sensitivity: the baseline is identical to main-matrix R6; the structural axes
     * (A1/A2/A3/A5) are measured identical (on the 3-host platform every concurrent flow
     * must share the endpoint host -- physical degeneracy, not a link-parameter effect);
     * the bandwidth-ratio axis A4 (link 1.25 MB/s &gt; endpoint 1 MB/s, non-binding)
     * converges exactly back to main-matrix R2 -- after re-calibration this axis restores
     * the design semantics "converge to endpoint dominance when the link is far wider
     * than the endpoint".
     */
    @Test
    void sensitivityLocksDegenerateStructureAxesAndConvergingBandwidthAxis() throws Exception {
        Log.disable();
        FatTreeSchedulingCampaignExecutor.CampaignResults results = runSubset(true, false);
        assertEquals(36, results.mainMatrix.size(), "main-matrix record count = 3 DAGs x 12 combinations");
        assertEquals(
                12,
                results.sensitivity.size(),
                "sensitivity record count = 2 planners x 6 topology variants (paper example only)");
        for (String planner : new String[] {HEFT, CPOP}) {
            double baseline = results.sensitivityMakespan(HEFT_EXAMPLE, planner, BASELINE);
            assertEquals(
                    results.makespan(HEFT_EXAMPLE, planner, R6),
                    baseline,
                    1.0e-9,
                    planner + ": the sensitivity baseline topology must be identical " + "to the main-matrix R6 run");
            for (String variant :
                    new String[] {"A1-k4-oversub2x", "A2-k8-full", "A3-k8-oversub2x", "A5-k4-full-same-edge-pair"}) {
                assertEquals(
                        baseline,
                        results.sensitivityMakespan(HEFT_EXAMPLE, planner, variant),
                        1.0e-9,
                        planner + " x " + variant + ": structural axes must degenerate to identity on 3 hosts");
            }
            assertEquals(
                    results.makespan(HEFT_EXAMPLE, planner, R2),
                    results.sensitivityMakespan(HEFT_EXAMPLE, planner, A4),
                    1.0e-9,
                    planner + ": A4 (link 10x) must converge exactly back to the main-matrix R2 value");
        }
    }

    /**
     * 4-host structural block: the structural axes (A1/A2/A3/A5) are identical -- crossing
     * flows never traverse the differing link concurrently; the bandwidth axis A4 (link
     * 1.25 MB/s, non-binding) strictly lowers the makespan, which together with the
     * simulator slow-link probe (FatTreeContentionIntegrationTest, 0.25 MB/s -> 588.1
     * &gt; 284.1) proves that the link-contention model takes effect correctly on a
     * binding link. After the R8 re-calibration this contract restores its original
     * design shape.
     */
    @Test
    void structuralBlockLocksFourHostDegeneracyAndBandwidthEffect() throws Exception {
        Log.disable();
        FatTreeSchedulingCampaignExecutor.CampaignResults results = runSubset(false, true);
        assertEquals(
                12,
                results.structural.size(),
                "structural-sensitivity record count = 2 planners x 6 topology variants (paper example only)");
        for (String planner : new String[] {HEFT, CPOP}) {
            double baseline = results.structuralMakespan(HEFT_EXAMPLE, planner, BASELINE);
            for (String variant :
                    new String[] {"A1-k4-oversub2x", "A2-k8-full", "A3-k8-oversub2x", "A5-k4-full-same-edge-pairs"}) {
                assertEquals(
                        baseline,
                        results.structuralMakespan(HEFT_EXAMPLE, planner, variant),
                        1.0e-9,
                        planner + " x " + variant + ": structural axes stay identical on 4 hosts");
            }
            assertTrue(
                    results.structuralMakespan(HEFT_EXAMPLE, planner, A4) < baseline,
                    planner + ": A4 (link 1.25 MB/s, non-binding) must strictly lower the makespan");
        }
        assertEquals(5718.1, results.structuralMakespan(HEFT_EXAMPLE, HEFT, BASELINE), 1.0e-6);
        assertEquals(5718.1, results.structuralMakespan(HEFT_EXAMPLE, CPOP, BASELINE), 1.0e-6);
        assertEquals(5186.1, results.structuralMakespan(HEFT_EXAMPLE, HEFT, A4), 1.0e-6);
        assertEquals(5186.1, results.structuralMakespan(HEFT_EXAMPLE, CPOP, A4), 1.0e-6);
    }

    /**
     * The sensitivity and structural blocks run the paper example only (the other DAGs are
     * covered by the full campaign); the main matrix runs the subset.
     */
    private static FatTreeSchedulingCampaignExecutor.CampaignResults runSubset(
            boolean includeSensitivity, boolean includeStructural) throws Exception {
        Set<String> wanted = new HashSet<String>(Arrays.asList(HEFT_EXAMPLE, CYBERSHAKE_N50, CYBERSHAKE_N100));
        List<FatTreeSchedulingCampaignExecutor.DagScenario> subset =
                new ArrayList<FatTreeSchedulingCampaignExecutor.DagScenario>();
        List<FatTreeSchedulingCampaignExecutor.DagScenario> sensitivitySubset =
                new ArrayList<FatTreeSchedulingCampaignExecutor.DagScenario>();
        for (FatTreeSchedulingCampaignExecutor.DagScenario dag :
                FatTreeSchedulingCampaignExecutor.discoverCoreDags(datasetsRoot())) {
            if (wanted.contains(dag.id)) {
                subset.add(dag);
            }
            if (HEFT_EXAMPLE.equals(dag.id)) {
                sensitivitySubset.add(dag);
            }
        }
        assertEquals(3, subset.size(), "the subset must contain the paper example + cybershake-n50/n100");
        assertEquals(1, sensitivitySubset.size(), "the core DAG set must contain the HEFT paper example");
        FatTreeSchedulingCampaignExecutor.CampaignResults main =
                FatTreeSchedulingCampaignExecutor.runCampaign(datasetsRoot(), subset, false, false);
        if (includeSensitivity) {
            FatTreeSchedulingCampaignExecutor.CampaignResults withSensitivity =
                    FatTreeSchedulingCampaignExecutor.runCampaign(datasetsRoot(), sensitivitySubset, true, false);
            main.sensitivity.clear();
            main.sensitivity.addAll(withSensitivity.sensitivity);
        }
        if (includeStructural) {
            FatTreeSchedulingCampaignExecutor.CampaignResults withStructural =
                    FatTreeSchedulingCampaignExecutor.runCampaign(datasetsRoot(), sensitivitySubset, false, true);
            main.structural.clear();
            main.structural.addAll(withStructural.structural);
        }
        return main;
    }

    private static void assertMakespan(
            FatTreeSchedulingCampaignExecutor.CampaignResults results,
            String dax,
            String planner,
            String modelKind,
            double expected) {
        assertEquals(
                expected,
                results.makespan(dax, planner, modelKind),
                expected > 1000.0 ? 1.0e-3 : 1.0e-6,
                dax + " x " + planner + " x " + modelKind + " golden makespan");
    }

    private static java.nio.file.Path datasetsRoot() {
        return Paths.get("..", "datasets").toAbsolutePath().normalize();
    }
}
