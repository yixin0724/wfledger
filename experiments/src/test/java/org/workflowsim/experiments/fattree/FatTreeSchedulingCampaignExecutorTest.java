package org.workflowsim.experiments.fattree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiments.fattree.FatTreeSchedulingCampaignExecutor.CampaignResults;
import org.workflowsim.experiments.fattree.FatTreeSchedulingCampaignExecutor.DagScenario;
import org.workflowsim.experiments.fattree.FatTreeSchedulingCampaignExecutor.TopologyVariant;
import org.workflowsim.utils.Parameters;

class FatTreeSchedulingCampaignExecutorTest {

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
    private static final List<String> PLANNER_NAMES = Arrays.asList(HEFT, CPOP, RANDOM, PSO);

    @Test
    void sensitivityAndStructuralVariantContracts() {
        List<String> expectedIds = Arrays.asList(
                "baseline-k4-full", "A1-k4-oversub2x", "A2-k8-full", "A3-k8-oversub2x", "A4-k4-full-link10x");
        List<TopologyVariant> sensitivity = FatTreeSchedulingCampaignExecutor.SENSITIVITY_VARIANTS;
        assertEquals(6, sensitivity.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(expectedIds.get(i), sensitivity.get(i).id);
        }
        assertEquals("A5-k4-full-same-edge-pair", sensitivity.get(5).id);

        Map<Integer, Integer> three = sensitivity.get(5).spec.getHostEdgePlacements();
        assertEquals(3, three.size());
        assertEquals(Integer.valueOf(0), three.get(Integer.valueOf(0)));
        assertEquals(Integer.valueOf(0), three.get(Integer.valueOf(1)));
        assertEquals(Integer.valueOf(2), three.get(Integer.valueOf(2)));

        List<TopologyVariant> structural = FatTreeSchedulingCampaignExecutor.structuralVariants();
        assertEquals(6, structural.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(expectedIds.get(i), structural.get(i).id);
        }
        assertEquals("A5-k4-full-same-edge-pairs", structural.get(5).id);

        Map<Integer, Integer> four = structural.get(5).spec.getHostEdgePlacements();
        assertEquals(4, four.size());
        assertEquals(Integer.valueOf(0), four.get(Integer.valueOf(0)));
        assertEquals(Integer.valueOf(0), four.get(Integer.valueOf(1)));
        assertEquals(Integer.valueOf(2), four.get(Integer.valueOf(2)));
        assertEquals(Integer.valueOf(2), four.get(Integer.valueOf(3)));

        assertEquals(null, sensitivity.get(0).spec.getHostEdgePlacements());
        assertEquals(null, structural.get(4).spec.getHostEdgePlacements());
    }

    @Test
    void discoverCoreDagsReturnsTenExpectedInstances() throws IOException {
        Path datasetsRoot = Paths.get("..", "datasets").toAbsolutePath().normalize();
        List<DagScenario> dags = FatTreeSchedulingCampaignExecutor.discoverCoreDags(datasetsRoot);
        assertEquals(10, dags.size());
        List<String> ids = new ArrayList<String>();
        for (DagScenario dag : dags) {
            ids.add(dag.id);
            assertTrue(Files.exists(dag.daxPath), "DAX must exist: " + dag.daxPath);
            assertTrue(dag.id.startsWith(dag.family), "id must carry the family prefix: " + dag.id);
        }
        assertEquals(
                Arrays.asList(
                        "heft-paper-example",
                        "epigenomics-n24",
                        "epigenomics-n46",
                        "epigenomics-n100",
                        "cybershake-n30",
                        "cybershake-n50",
                        "cybershake-n100",
                        "inspiral-n30",
                        "inspiral-n50",
                        "inspiral-n100"),
                ids);
    }

    @Test
    void discoverCoreDagsRejectsMalformedDatasetRoots(@TempDir Path tempRoot) throws IOException {

        Path empty = tempRoot.resolve("empty");
        prepareHeftExample(empty);
        Files.createDirectories(empty.resolve("dax/epigenomics/n24"));
        IllegalStateException noDax = assertThrows(
                IllegalStateException.class, () -> FatTreeSchedulingCampaignExecutor.discoverCoreDags(empty));
        assertTrue(noDax.getMessage().contains("No .dax file"), noDax.getMessage());

        Path multiple = tempRoot.resolve("multiple");
        prepareHeftExample(multiple);
        Path scale = multiple.resolve("dax/epigenomics/n24");
        Files.createDirectories(scale);
        Files.write(scale.resolve("a.dax"), "<adag/>".getBytes(StandardCharsets.UTF_8));
        Files.write(scale.resolve("b.dax"), "<adag/>".getBytes(StandardCharsets.UTF_8));
        IllegalStateException multi = assertThrows(
                IllegalStateException.class, () -> FatTreeSchedulingCampaignExecutor.discoverCoreDags(multiple));
        assertTrue(multi.getMessage().contains("Multiple .dax files"), multi.getMessage());
    }

    private static void prepareHeftExample(Path root) throws IOException {
        Path heftDir = root.resolve("dax/heft");
        Files.createDirectories(heftDir);
        Files.write(heftDir.resolve("heft-paper-example.dax"), "<adag/>".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void cleanFixtureComputesRankingsWinnersAndStrictMonotonicity() {
        CampaignResults results = fixture(false);
        results.assertWeakMonotonicity();
        assertTrue(results.monotonicityDiagnostics().isEmpty());
        Map<String, List<String>> rankings = results.averageRankings();
        assertEquals(3, rankings.size());
        for (String model : new String[] {V1, R2, R6}) {
            assertEquals(Arrays.asList(HEFT, CPOP, PSO, RANDOM), rankings.get(model));
        }
        Map<String, Map<String, String>> winners = results.perDagWinners();
        for (String model : new String[] {V1, R2, R6}) {
            assertEquals(HEFT, winners.get(model).get("dag-a"));
            assertEquals(HEFT, winners.get(model).get("dag-b"));
        }
        assertEquals(Arrays.asList(100.0, 101.0), results.makespans(HEFT, V1));
    }

    @Test
    void violationFixtureIsDiagnosedAndStrictAssertRejects() {
        CampaignResults results = fixture(true);
        List<Map<String, Object>> violations = results.monotonicityDiagnostics();
        assertEquals(1, violations.size());
        Map<String, Object> violation = violations.get(0);
        assertEquals("dag-b", violation.get("dax"));
        assertEquals(PSO, violation.get("planner"));
        double deviation = ((Double) violation.get("maxRelativeDeviation")).doubleValue();
        assertTrue(deviation > 0.0 && deviation <= 1.0e-4, "crossing must remain noise-level: " + deviation);
        assertEquals(121.0, ((Double) violation.get("v1MakespanSeconds")).doubleValue(), 1e-9);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, results::assertWeakMonotonicity);
        assertTrue(thrown.getMessage().contains("Weak-monotonicity violation"));
    }

    @Test
    void pairedStatisticsCoversPlannerAndModelAxes() {
        CampaignResults results = fixture(false);
        List<Map<String, Object>> statistics = results.pairedStatistics();
        assertEquals(21, statistics.size());
        Map<String, Object> first = statistics.get(0);
        assertEquals("planner@" + V1, first.get("axis"));
        assertEquals(HEFT, first.get("baseline"));
        assertEquals(CPOP, first.get("candidate"));
        assertEquals(Integer.valueOf(2), first.get("pairedSampleCount"));
        assertTrue(first.containsKey("status"));
        assertTrue(first.containsKey("pValue"));
        assertTrue(first.containsKey("significantAtAlpha005"));
        List<String> axes = new ArrayList<String>();
        for (Map<String, Object> record : statistics) {
            axes.add((String) record.get("axis"));
        }
        assertTrue(axes.contains("planner@" + R6));
        assertTrue(axes.contains("model-r2-vs-v1@" + PSO));
        assertTrue(axes.contains("model-r6-vs-r2@" + RANDOM));
        assertTrue(axes.contains("model-r6-vs-v1@" + HEFT));
    }

    @Test
    void scanStatisticsSeparatesDegenerateAndEffectiveAxes() {
        CampaignResults results = fixture(false);
        for (String block : new String[] {"sensitivity", "structural"}) {
            List<Map<String, Object>> statistics =
                    "sensitivity".equals(block) ? results.sensitivityStatistics() : results.structuralStatistics();
            assertEquals(10, statistics.size(), block + ": 2 planners x 5 non-baseline variants");
            for (Map<String, Object> record : statistics) {
                String axis = (String) record.get("axis");
                assertTrue(axis.startsWith(block + "@"), axis);
                String candidate = (String) record.get("candidate");
                if (candidate.startsWith("A4")) {
                    assertEquals("AVAILABLE_WILCOXON_SIGNED_RANK_EXACT", record.get("status"), axis);
                    assertEquals(-10.0, ((Double) record.get("medianDifferenceSeconds")).doubleValue(), 1e-9);
                } else {
                    assertEquals("UNAVAILABLE_ALL_PAIRED_DIFFERENCES_ARE_ZERO", record.get("status"), axis);
                    assertEquals(Integer.valueOf(0), record.get("effectiveSampleCount"), axis);
                }
            }
        }
    }

    @Test
    void recordLookupsFailLoudlyOnMissingEntries() {
        CampaignResults results = fixture(false);
        assertEquals(100.0, results.makespan("dag-a", HEFT, V1), 1e-9);
        assertEquals(130.0, results.sensitivityMakespan("dag-a", HEFT, "baseline-k4-full"), 1e-9);
        assertEquals(130.0, results.structuralMakespan("dag-b", CPOP, "baseline-k4-full"), 1e-9);
        IllegalStateException missingMain =
                assertThrows(IllegalStateException.class, () -> results.makespan("dag-zz", HEFT, V1));
        assertTrue(missingMain.getMessage().contains("Missing"));
        IllegalStateException missingSens = assertThrows(
                IllegalStateException.class, () -> results.sensitivityMakespan("dag-a", HEFT, "A9-unknown"));
        assertTrue(missingSens.getMessage().contains("Missing sensitivity record"), missingSens.getMessage());
        IllegalStateException missingStruct = assertThrows(
                IllegalStateException.class, () -> results.structuralMakespan("dag-a", HEFT, "A9-unknown"));
        assertTrue(missingStruct.getMessage().contains("Missing structural record"), missingStruct.getMessage());
    }

    @Test
    void jsonArtifactCarriesSchemaAndConditionalStatistics() {
        CampaignResults full = fixture(false);
        Map<String, Object> json = full.toJson();
        assertEquals(FatTreeSchedulingCampaignExecutor.RESULTS_SCHEMA, json.get("schema"));
        assertEquals(Long.valueOf(91L), json.get("fixedSeed"));
        assertEquals(Integer.valueOf(3), json.get("vmCount"));
        assertEquals(PLANNER_NAMES, json.get("planners"));
        assertEquals(Arrays.asList(V1, R2, R6), json.get("movementModels"));
        assertEquals(full.mainMatrix, json.get("mainMatrix"));
        assertEquals(full.sensitivity, json.get("sensitivity"));
        assertEquals(full.structural, json.get("structuralSensitivity"));
        assertTrue(json.containsKey("generatedAt"));
        assertTrue(json.containsKey("weakMonotonicityViolations"));
        assertTrue(json.containsKey("averageRankings"));
        assertTrue(json.containsKey("perDagWinners"));
        assertTrue(json.containsKey("pairedStatistics"));
        assertTrue(json.containsKey("sensitivityStatistics"));
        assertTrue(json.containsKey("structuralSensitivityStatistics"));

        Map<String, Object> mainOnly = mainOnlyFixture().toJson();
        assertFalse(mainOnly.containsKey("sensitivityStatistics"));
        assertFalse(mainOnly.containsKey("structuralSensitivityStatistics"));
    }

    @Test
    void markdownArtifactAdaptsToBlockPresence() {
        String full = fixture(false).toMarkdown();
        assertTrue(full.startsWith("# Fat-tree × Scheduling Campaign Measured Results"));
        assertTrue(full.contains("## Main Matrix makespan (seconds)"));
        assertTrue(full.contains("## Weak-Monotonicity Diagnostics"));
        assertTrue(full.contains("## Average Ranking"));
        assertTrue(full.contains("## Per-DAG Winner"));
        assertTrue(full.contains("## Paired Wilcoxon (across DAGs)"));
        assertTrue(full.contains("## Sensitivity Paired Wilcoxon (3 hosts, variant vs baseline topology)"));
        assertTrue(full.contains("## 4-Host Structural-Sensitivity makespan (seconds, R6 model)"));
        assertTrue(full.contains("## Structural-Sensitivity Paired Wilcoxon (4 hosts, variant vs baseline topology)"));
        assertTrue(full.contains("dag-a") && full.contains("dag-b"));

        String mainOnly = mainOnlyFixture().toMarkdown();
        assertTrue(mainOnly.contains("## Main Matrix makespan (seconds)"));
        assertFalse(mainOnly.contains("## Sensitivity Paired Wilcoxon"));
        assertFalse(mainOnly.contains("## 4-Host Structural-Sensitivity"));
    }

    private static CampaignResults fixture(boolean withViolation) {
        List<DagScenario> dags = new ArrayList<DagScenario>();
        dags.add(new DagScenario("dag-a", "dag", "a", Paths.get("dag-a.dax")));
        dags.add(new DagScenario("dag-b", "dag", "b", Paths.get("dag-b.dax")));
        Map<String, Double> plannerBase = new LinkedHashMap<String, Double>();
        plannerBase.put(HEFT, Double.valueOf(100.0));
        plannerBase.put(CPOP, Double.valueOf(110.0));
        plannerBase.put(PSO, Double.valueOf(120.0));
        plannerBase.put(RANDOM, Double.valueOf(500.0));
        Map<String, Double> modelAdd = new LinkedHashMap<String, Double>();
        modelAdd.put(V1, Double.valueOf(0.0));
        modelAdd.put(R2, Double.valueOf(10.0));
        modelAdd.put(R6, Double.valueOf(20.0));
        List<Map<String, Object>> mainMatrix = new ArrayList<Map<String, Object>>();
        for (DagScenario dag : dags) {
            double dagAddition = "dag-b".equals(dag.id) ? 1.0 : 0.0;
            for (Map.Entry<String, Double> planner : plannerBase.entrySet()) {
                for (Map.Entry<String, Double> model : modelAdd.entrySet()) {
                    double value =
                            planner.getValue().doubleValue() + model.getValue().doubleValue() + dagAddition;
                    if (withViolation
                            && "dag-b".equals(dag.id)
                            && PSO.equals(planner.getKey())
                            && R6.equals(model.getKey())) {

                        value = planner.getValue().doubleValue() + 10.0 + dagAddition - 0.001;
                    }
                    Map<String, Object> record = new LinkedHashMap<String, Object>();
                    record.put("dax", dag.id);
                    record.put("family", dag.family);
                    record.put("scale", dag.scale);
                    record.put("planner", planner.getKey());
                    record.put("movementModel", model.getKey());
                    record.put("makespanSeconds", Double.valueOf(value));
                    record.put("totalJobs", Integer.valueOf(7));
                    mainMatrix.add(record);
                }
            }
        }
        List<Map<String, Object>> sensitivity =
                scanRecords(dags, FatTreeSchedulingCampaignExecutor.SENSITIVITY_VARIANTS);
        List<Map<String, Object>> structural =
                scanRecords(dags, FatTreeSchedulingCampaignExecutor.structuralVariants());
        return new CampaignResults(dags, mainMatrix, sensitivity, structural);
    }

    private static CampaignResults mainOnlyFixture() {
        CampaignResults full = fixture(false);
        return new CampaignResults(
                full.dags, full.mainMatrix, new ArrayList<Map<String, Object>>(), new ArrayList<Map<String, Object>>());
    }

    private static List<Map<String, Object>> scanRecords(List<DagScenario> dags, List<TopologyVariant> variants) {
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        for (DagScenario dag : dags) {
            for (String planner : new String[] {HEFT, CPOP}) {
                for (TopologyVariant variant : variants) {
                    double baseline = 130.0;
                    double value = variant.id.startsWith("A4") ? baseline - 10.0 : baseline;
                    Map<String, Object> record = new LinkedHashMap<String, Object>();
                    record.put("dax", dag.id);
                    record.put("planner", planner);
                    record.put("variant", variant.id);
                    record.put("makespanSeconds", Double.valueOf(value));
                    records.add(record);
                }
            }
        }
        return records;
    }
}
