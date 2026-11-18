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
    }

    @Test
    void contentionFlipsCybershakeWinnersToPso() throws Exception {
        Log.disable();
        FatTreeSchedulingCampaignExecutor.CampaignResults results = runSubset(false, false);
        Map<String, Map<String, String>> winners = results.perDagWinners();
        assertEquals(HEFT, winners.get(V1).get(CYBERSHAKE_N50));
        assertEquals(PSO, winners.get(R2).get(CYBERSHAKE_N50));
        assertEquals(PSO, winners.get(R6).get(CYBERSHAKE_N50));

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

        assertMakespan(results, CYBERSHAKE_N50, PSO, R2, 587924.0247);
        assertMakespan(results, CYBERSHAKE_N50, PSO, R6, 587921.1503);
        assertMakespan(results, CYBERSHAKE_N50, HEFT, R2, 588010.008326);
        assertMakespan(results, CYBERSHAKE_N100, PSO, R2, 1189487.4983);
        assertMakespan(results, CYBERSHAKE_N100, PSO, R6, 1189487.4983);
        assertMakespan(results, CYBERSHAKE_N100, CPOP, V1, 1155577.5672);
    }

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

    @Test
    void subsetRunsAreDeterministic() throws Exception {
        Log.disable();
        FatTreeSchedulingCampaignExecutor.CampaignResults first = runSubset(false, false);
        FatTreeSchedulingCampaignExecutor.CampaignResults second = runSubset(false, false);
        assertEquals(first.mainMatrix, second.mainMatrix, "the two runs must yield a bit-identical main matrix");
    }

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
