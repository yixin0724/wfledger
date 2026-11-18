package org.workflowsim.experiments.fattree;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.PairedWilcoxonSignificance;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

public final class FatTreeSchedulingCampaignExecutor {

    static final String RESULTS_JSON_FILE_NAME = "campaign-results.json";
    static final String RESULTS_MD_FILE_NAME = "campaign-results.md";
    static final String RESULTS_SCHEMA = "workflowsim-fattree-campaign-v1";
    static final long FIXED_SEED = 91L;

    static final List<Parameters.PlanningAlgorithm> PLANNERS = Collections.unmodifiableList(Arrays.asList(
            Parameters.PlanningAlgorithm.LOCAL_HEFT,
            Parameters.PlanningAlgorithm.LOCAL_CPOP,
            Parameters.PlanningAlgorithm.RANDOM,
            Parameters.PlanningAlgorithm.PSO));

    static final List<DataMovementModel> MODELS = Collections.unmodifiableList(Arrays.asList(
            DataMovementModel.preExecutionTransferDelayV1(),
            DataMovementModel.preExecutionTransferDelayWithContentionV1(),
            DataMovementModel.fatTreeContentionV1()));

    static final double BASELINE_LINK_BANDWIDTH_MB = 0.125;

    static final double A4_LINK_BANDWIDTH_MB = BASELINE_LINK_BANDWIDTH_MB * 10.0;

    static final List<TopologyVariant> SENSITIVITY_VARIANTS = Collections.unmodifiableList(Arrays.asList(
            new TopologyVariant("baseline-k4-full", NetworkTopologySpec.fatTree(4, BASELINE_LINK_BANDWIDTH_MB)),
            new TopologyVariant("A1-k4-oversub2x", NetworkTopologySpec.fatTree(4, BASELINE_LINK_BANDWIDTH_MB, 2)),
            new TopologyVariant("A2-k8-full", NetworkTopologySpec.fatTree(8, BASELINE_LINK_BANDWIDTH_MB)),
            new TopologyVariant("A3-k8-oversub2x", NetworkTopologySpec.fatTree(8, BASELINE_LINK_BANDWIDTH_MB, 8)),
            new TopologyVariant("A4-k4-full-link10x", NetworkTopologySpec.fatTree(4, A4_LINK_BANDWIDTH_MB)),
            new TopologyVariant(
                    "A5-k4-full-same-edge-pair",
                    NetworkTopologySpec.fatTree(4, BASELINE_LINK_BANDWIDTH_MB, null, sameEdgePairPlacements()))));

    static List<TopologyVariant> structuralVariants() {
        Map<Integer, Integer> pairPlacements = new LinkedHashMap<Integer, Integer>();
        pairPlacements.put(Integer.valueOf(0), Integer.valueOf(0));
        pairPlacements.put(Integer.valueOf(1), Integer.valueOf(0));
        pairPlacements.put(Integer.valueOf(2), Integer.valueOf(2));
        pairPlacements.put(Integer.valueOf(3), Integer.valueOf(2));
        return Collections.unmodifiableList(Arrays.asList(
                new TopologyVariant("baseline-k4-full", NetworkTopologySpec.fatTree(4, BASELINE_LINK_BANDWIDTH_MB)),
                new TopologyVariant("A1-k4-oversub2x", NetworkTopologySpec.fatTree(4, BASELINE_LINK_BANDWIDTH_MB, 2)),
                new TopologyVariant("A2-k8-full", NetworkTopologySpec.fatTree(8, BASELINE_LINK_BANDWIDTH_MB)),
                new TopologyVariant("A3-k8-oversub2x", NetworkTopologySpec.fatTree(8, BASELINE_LINK_BANDWIDTH_MB, 8)),
                new TopologyVariant("A4-k4-full-link10x", NetworkTopologySpec.fatTree(4, A4_LINK_BANDWIDTH_MB)),
                new TopologyVariant(
                        "A5-k4-full-same-edge-pairs",
                        NetworkTopologySpec.fatTree(4, BASELINE_LINK_BANDWIDTH_MB, null, pairPlacements))));
    }

    private FatTreeSchedulingCampaignExecutor() {}

    private static Map<Integer, Integer> sameEdgePairPlacements() {
        Map<Integer, Integer> placements = new LinkedHashMap<Integer, Integer>();
        placements.put(Integer.valueOf(0), Integer.valueOf(0));
        placements.put(Integer.valueOf(1), Integer.valueOf(0));
        placements.put(Integer.valueOf(2), Integer.valueOf(2));
        return placements;
    }

    public static void main(String[] args) throws Exception {
        Path datasetsRoot;
        Path outputDir;
        if (args == null || args.length == 0) {
            Path projectRoot =
                    Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            datasetsRoot = projectRoot.resolve("datasets");
            outputDir = projectRoot
                    .resolve("fattree-campaign-output")
                    .resolve("run-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()));
            System.out.println("[campaign] no arguments given; using default paths:");
            System.out.println("[campaign]   datasets root = " + datasetsRoot);
            System.out.println("[campaign]   output directory = " + outputDir);
        } else if (args.length == 2) {
            datasetsRoot = Paths.get(args[0]).toAbsolutePath().normalize();
            outputDir = Paths.get(args[1]).toAbsolutePath().normalize();
        } else {
            throw new IllegalArgumentException("Usage: FatTreeSchedulingCampaignExecutor <datasets-root> <output-dir>");
        }
        Log.disable();
        List<DagScenario> dags = discoverCoreDags(datasetsRoot);
        System.out.println("[campaign] discovered " + dags.size() + " core DAGs");
        CampaignResults results = runCampaign(datasetsRoot, dags, true, true);
        Files.createDirectories(outputDir);
        Path json = outputDir.resolve(RESULTS_JSON_FILE_NAME);
        Path markdown = outputDir.resolve(RESULTS_MD_FILE_NAME);
        ExperimentArtifactWriter.writeJson(json, results.toJson());
        Files.write(markdown, results.toMarkdown().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("[campaign] completed: main matrix " + results.mainMatrix.size()
                + " runs + 3-host sensitivity " + results.sensitivity.size()
                + " runs + 4-host structural sensitivity " + results.structural.size() + " runs");
        System.out.println("[campaign] artifacts: " + json + " , " + markdown);
        System.out.println(results.toMarkdown());
    }

    static List<DagScenario> discoverCoreDags(Path datasetsRoot) throws IOException {
        String[][] families = {
            {"epigenomics", "n24", "n46", "n100"},
            {"cybershake", "n30", "n50", "n100"},
            {"inspiral", "n30", "n50", "n100"}
        };
        List<DagScenario> dags = new ArrayList<DagScenario>();
        dags.add(new DagScenario(
                "heft-paper-example", "heft", "n10", datasetsRoot.resolve("dax/heft/heft-paper-example.dax")));
        for (String[] family : families) {
            for (int scaleIndex = 1; scaleIndex < family.length; scaleIndex++) {
                String familyName = family[0];
                String scale = family[scaleIndex];
                Path dir = datasetsRoot.resolve("dax").resolve(familyName).resolve(scale);
                dags.add(new DagScenario(familyName + "-" + scale, familyName, scale, singleDax(dir)));
            }
        }
        return Collections.unmodifiableList(dags);
    }

    private static Path singleDax(Path dir) throws IOException {
        Path found = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.dax")) {
            for (Path candidate : stream) {
                if (found != null) {
                    throw new IllegalStateException("Multiple .dax files in " + dir);
                }
                found = candidate;
            }
        }
        if (found == null) {
            throw new IllegalStateException("No .dax file in " + dir);
        }
        return found;
    }

    static CampaignResults runCampaign(
            Path datasetsRoot, List<DagScenario> dags, boolean includeSensitivity, boolean includeStructural)
            throws Exception {
        SimulationRunner runner = new SimulationRunner();
        List<Map<String, Object>> mainMatrix = new ArrayList<Map<String, Object>>();
        long startedAt = System.nanoTime();
        for (DagScenario dag : dags) {
            for (Parameters.PlanningAlgorithm planner : PLANNERS) {
                for (DataMovementModel model : MODELS) {
                    boolean fatTree = model.isFatTreeContentionV1();
                    SimulationConfig config = baseConfig(dag, planner, model, 3);
                    PlatformProfile platform =
                            platform(3, fatTree ? NetworkTopologySpec.fatTree(4, BASELINE_LINK_BANDWIDTH_MB) : null);
                    SimulationReport report = runner.run(config, platform);
                    assertHealthy(report, "main:" + dag.id + ":" + planner + ":" + model.getKind());
                    Map<String, Object> record = new LinkedHashMap<String, Object>();
                    record.put("dax", dag.id);
                    record.put("family", dag.family);
                    record.put("scale", dag.scale);
                    record.put("planner", planner.name());
                    record.put("movementModel", model.getKind().name());
                    record.put("makespanSeconds", Double.valueOf(report.getMakespan()));
                    record.put("totalJobs", Integer.valueOf(report.getTotalJobs()));
                    mainMatrix.add(record);
                }
            }
        }
        List<Parameters.PlanningAlgorithm> sensitivePlanners =
                Arrays.asList(Parameters.PlanningAlgorithm.LOCAL_HEFT, Parameters.PlanningAlgorithm.LOCAL_CPOP);
        List<Map<String, Object>> sensitivity = new ArrayList<Map<String, Object>>();
        if (includeSensitivity) {
            for (DagScenario dag : dags) {
                for (Parameters.PlanningAlgorithm planner : sensitivePlanners) {
                    for (TopologyVariant variant : SENSITIVITY_VARIANTS) {
                        SimulationConfig config = baseConfig(dag, planner, MODELS.get(2), 3);
                        SimulationReport report = runner.run(config, platform(3, variant.spec));
                        assertHealthy(report, "sens:" + dag.id + ":" + planner + ":" + variant.id);
                        Map<String, Object> record = new LinkedHashMap<String, Object>();
                        record.put("dax", dag.id);
                        record.put("planner", planner.name());
                        record.put("variant", variant.id);
                        record.put("makespanSeconds", Double.valueOf(report.getMakespan()));
                        sensitivity.add(record);
                    }
                }
            }
        }
        List<Map<String, Object>> structural = new ArrayList<Map<String, Object>>();
        if (includeStructural) {
            for (DagScenario dag : dags) {
                for (Parameters.PlanningAlgorithm planner : sensitivePlanners) {
                    for (TopologyVariant variant : structuralVariants()) {
                        SimulationConfig config = baseConfig(dag, planner, MODELS.get(2), 4);
                        SimulationReport report = runner.run(config, platform(4, variant.spec));
                        assertHealthy(report, "struct:" + dag.id + ":" + planner + ":" + variant.id);
                        Map<String, Object> record = new LinkedHashMap<String, Object>();
                        record.put("dax", dag.id);
                        record.put("planner", planner.name());
                        record.put("variant", variant.id);
                        record.put("makespanSeconds", Double.valueOf(report.getMakespan()));
                        structural.add(record);
                    }
                }
            }
        }
        CampaignResults results = new CampaignResults(dags, mainMatrix, sensitivity, structural);
        System.out.println("[campaign] elapsed " + (System.nanoTime() - startedAt) / 1_000_000_000.0 + " s");
        return results;
    }

    private static SimulationConfig baseConfig(
            DagScenario dag, Parameters.PlanningAlgorithm planner, DataMovementModel model, int vmCount) {
        return SimulationConfig.builder(dag.daxPath.toString(), vmCount)
                .planningAlgorithm(planner)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(model)
                .randomSeed(FIXED_SEED)
                .build();
    }

    private static PlatformProfile platform(int vmCount, NetworkTopologySpec topology) {
        PlatformProfile.Builder builder = PlatformProfile.builder("campaign-platform");
        for (int id = 0; id < vmCount; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    id, 1.0, 1, 512, 1L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        if (topology != null) {
            builder.networkTopology(topology);
        }
        return builder.build();
    }

    private static void assertHealthy(SimulationReport report, String label) {
        if (!"COMPLETED_SUCCESSFULLY".equals(report.getMetrics().getLogicalTaskCompletionStatus())) {
            throw new IllegalStateException(label
                    + ": abnormal logical task completion status "
                    + report.getMetrics().getLogicalTaskCompletionStatus());
        }
        if (report.getTotalJobs() != report.getSuccessfulJobs()) {
            throw new IllegalStateException(label + ": not all jobs completed successfully");
        }
        if (!(report.getMakespan() > 0.0) || !Double.isFinite(report.getMakespan())) {
            throw new IllegalStateException(label + ": invalid makespan " + report.getMakespan());
        }
    }

    static final class DagScenario {
        final String id;
        final String family;
        final String scale;
        final Path daxPath;

        DagScenario(String id, String family, String scale, Path daxPath) {
            this.id = id;
            this.family = family;
            this.scale = scale;
            this.daxPath = daxPath;
        }
    }

    static final class TopologyVariant {
        final String id;
        final NetworkTopologySpec spec;

        TopologyVariant(String id, NetworkTopologySpec spec) {
            this.id = id;
            this.spec = spec;
        }
    }

    static final class CampaignResults {
        final List<DagScenario> dags;
        final List<Map<String, Object>> mainMatrix;
        final List<Map<String, Object>> sensitivity;
        final List<Map<String, Object>> structural;

        CampaignResults(
                List<DagScenario> dags,
                List<Map<String, Object>> mainMatrix,
                List<Map<String, Object>> sensitivity,
                List<Map<String, Object>> structural) {
            this.dags = dags;
            this.mainMatrix = mainMatrix;
            this.sensitivity = sensitivity;
            this.structural = structural;
        }

        List<Double> makespans(String planner, String modelKind) {
            List<Double> values = new ArrayList<Double>();
            for (DagScenario dag : dags) {
                values.add(Double.valueOf(makespan(dag.id, planner, modelKind)));
            }
            return values;
        }

        double makespan(String dax, String planner, String modelKind) {
            for (Map<String, Object> record : mainMatrix) {
                if (dax.equals(record.get("dax"))
                        && planner.equals(record.get("planner"))
                        && modelKind.equals(record.get("movementModel"))) {
                    return ((Double) record.get("makespanSeconds")).doubleValue();
                }
            }
            throw new IllegalStateException("Missing main-matrix record " + dax + "/" + planner + "/" + modelKind);
        }

        double sensitivityMakespan(String dax, String planner, String variant) {
            return scanMakespan(sensitivity, dax, planner, variant, "sensitivity");
        }

        double structuralMakespan(String dax, String planner, String variant) {
            return scanMakespan(structural, dax, planner, variant, "structural");
        }

        private static double scanMakespan(
                List<Map<String, Object>> records, String dax, String planner, String variant, String block) {
            for (Map<String, Object> record : records) {
                if (dax.equals(record.get("dax"))
                        && planner.equals(record.get("planner"))
                        && variant.equals(record.get("variant"))) {
                    return ((Double) record.get("makespanSeconds")).doubleValue();
                }
            }
            throw new IllegalStateException("Missing " + block + " record " + dax + "/" + planner + "/" + variant);
        }

        List<Map<String, Object>> monotonicityDiagnostics() {
            List<Map<String, Object>> violations = new ArrayList<Map<String, Object>>();
            String v1 =
                    DataMovementModel.preExecutionTransferDelayV1().getKind().name();
            String r2 = DataMovementModel.preExecutionTransferDelayWithContentionV1()
                    .getKind()
                    .name();
            String r6 = DataMovementModel.fatTreeContentionV1().getKind().name();
            for (DagScenario dag : dags) {
                for (Parameters.PlanningAlgorithm planner : PLANNERS) {
                    double a = makespan(dag.id, planner.name(), v1);
                    double b = makespan(dag.id, planner.name(), r2);
                    double c = makespan(dag.id, planner.name(), r6);
                    if (b >= a - 1.0e-9 && c >= b - 1.0e-9) {
                        continue;
                    }
                    Map<String, Object> record = new LinkedHashMap<String, Object>();
                    record.put("dax", dag.id);
                    record.put("planner", planner.name());
                    record.put("v1MakespanSeconds", Double.valueOf(a));
                    record.put("r2MakespanSeconds", Double.valueOf(b));
                    record.put("r6MakespanSeconds", Double.valueOf(c));
                    double maxDeviation = 0.0;
                    if (b < a) {
                        maxDeviation = Math.max(maxDeviation, (a - b) / b);
                    }
                    if (c < b) {
                        maxDeviation = Math.max(maxDeviation, (b - c) / c);
                    }
                    record.put("maxRelativeDeviation", Double.valueOf(maxDeviation));
                    violations.add(record);
                }
            }
            return violations;
        }

        void assertWeakMonotonicity() {
            List<Map<String, Object>> violations = monotonicityDiagnostics();
            if (!violations.isEmpty()) {
                throw new IllegalStateException("Weak-monotonicity violation: " + violations);
            }
        }

        Map<String, List<String>> averageRankings() {
            Map<String, List<String>> rankings = new LinkedHashMap<String, List<String>>();
            for (DataMovementModel model : MODELS) {
                List<String> order = new ArrayList<String>();
                List<Double> means = new ArrayList<Double>();
                for (Parameters.PlanningAlgorithm planner : PLANNERS) {
                    List<Double> values =
                            makespans(planner.name(), model.getKind().name());
                    double mean = 0.0;
                    for (Double value : values) {
                        mean += value.doubleValue();
                    }
                    mean /= values.size();
                    int position = 0;
                    while (position < order.size() && means.get(position).doubleValue() <= mean) {
                        position++;
                    }
                    order.add(position, planner.name());
                    means.add(position, Double.valueOf(mean));
                }
                rankings.put(model.getKind().name(), order);
            }
            return rankings;
        }

        Map<String, Map<String, String>> perDagWinners() {
            Map<String, Map<String, String>> winners = new LinkedHashMap<String, Map<String, String>>();
            for (DataMovementModel model : MODELS) {
                Map<String, String> perDag = new LinkedHashMap<String, String>();
                for (DagScenario dag : dags) {
                    String best = null;
                    double bestMakespan = Double.MAX_VALUE;
                    for (Parameters.PlanningAlgorithm planner : PLANNERS) {
                        double value =
                                makespan(dag.id, planner.name(), model.getKind().name());
                        if (value < bestMakespan) {
                            bestMakespan = value;
                            best = planner.name();
                        }
                    }
                    perDag.put(dag.id, best);
                }
                winners.put(model.getKind().name(), perDag);
            }
            return winners;
        }

        List<Map<String, Object>> pairedStatistics() {
            List<Map<String, Object>> statistics = new ArrayList<Map<String, Object>>();
            String v1 =
                    DataMovementModel.preExecutionTransferDelayV1().getKind().name();
            String r2 = DataMovementModel.preExecutionTransferDelayWithContentionV1()
                    .getKind()
                    .name();
            String r6 = DataMovementModel.fatTreeContentionV1().getKind().name();
            String baselinePlanner = Parameters.PlanningAlgorithm.LOCAL_HEFT.name();
            for (DataMovementModel model : MODELS) {
                for (Parameters.PlanningAlgorithm planner : PLANNERS) {
                    if (planner.name().equals(baselinePlanner)) {
                        continue;
                    }
                    statistics.add(statRecord(
                            "planner@" + model.getKind().name(),
                            baselinePlanner,
                            planner.name(),
                            makespans(baselinePlanner, model.getKind().name()),
                            makespans(planner.name(), model.getKind().name())));
                }
            }
            for (Parameters.PlanningAlgorithm planner : PLANNERS) {
                statistics.add(statRecord(
                        "model-r2-vs-v1@" + planner.name(),
                        v1,
                        r2,
                        makespans(planner.name(), v1),
                        makespans(planner.name(), r2)));
                statistics.add(statRecord(
                        "model-r6-vs-r2@" + planner.name(),
                        r2,
                        r6,
                        makespans(planner.name(), r2),
                        makespans(planner.name(), r6)));
                statistics.add(statRecord(
                        "model-r6-vs-v1@" + planner.name(),
                        v1,
                        r6,
                        makespans(planner.name(), v1),
                        makespans(planner.name(), r6)));
            }
            return statistics;
        }

        List<Map<String, Object>> sensitivityStatistics() {
            return scanStatistics(SENSITIVITY_VARIANTS, "sensitivity");
        }

        List<Map<String, Object>> structuralStatistics() {
            return scanStatistics(structuralVariants(), "structural");
        }

        private List<Map<String, Object>> scanStatistics(List<TopologyVariant> variants, String block) {
            List<Map<String, Object>> statistics = new ArrayList<Map<String, Object>>();
            List<Parameters.PlanningAlgorithm> sensitivePlanners =
                    Arrays.asList(Parameters.PlanningAlgorithm.LOCAL_HEFT, Parameters.PlanningAlgorithm.LOCAL_CPOP);
            for (Parameters.PlanningAlgorithm planner : sensitivePlanners) {
                for (TopologyVariant variant : variants) {
                    if (variant.id.equals("baseline-k4-full")) {
                        continue;
                    }
                    List<Double> baseline = new ArrayList<Double>();
                    List<Double> candidate = new ArrayList<Double>();
                    for (DagScenario dag : dags) {
                        baseline.add(Double.valueOf(scanMakespan(
                                "sensitivity".equals(block) ? sensitivity : structural,
                                dag.id,
                                planner.name(),
                                "baseline-k4-full",
                                block)));
                        candidate.add(Double.valueOf(scanMakespan(
                                "sensitivity".equals(block) ? sensitivity : structural,
                                dag.id,
                                planner.name(),
                                variant.id,
                                block)));
                    }
                    statistics.add(statRecord(
                            block + "@" + planner.name(), "baseline-k4-full", variant.id, baseline, candidate));
                }
            }
            return statistics;
        }

        private static Map<String, Object> statRecord(
                String axis,
                String baselineLabel,
                String candidateLabel,
                List<Double> baseline,
                List<Double> candidate) {
            PairedWilcoxonSignificance significance = PairedWilcoxonSignificance.evaluate(baseline, candidate);
            Map<String, Object> record = new LinkedHashMap<String, Object>();
            record.put("axis", axis);
            record.put("baseline", baselineLabel);
            record.put("candidate", candidateLabel);
            record.put("pairedSampleCount", Integer.valueOf(significance.getPairedSampleCount()));
            record.put("effectiveSampleCount", Integer.valueOf(significance.getEffectiveSampleCount()));
            record.put("medianDifferenceSeconds", significance.getMedianDifference());
            record.put("status", significance.getStatus());
            record.put("pValue", significance.getPValue());
            record.put("significantAtAlpha005", Boolean.valueOf(significance.isSignificantAtDefaultAlpha()));
            return record;
        }

        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<String, Object>();
            json.put("schema", RESULTS_SCHEMA);
            json.put("generatedAt", java.time.OffsetDateTime.now().toString());
            json.put("fixedSeed", Long.valueOf(FIXED_SEED));
            json.put("vmCount", Integer.valueOf(3));
            json.put(
                    "planners",
                    Arrays.asList(
                            Parameters.PlanningAlgorithm.LOCAL_HEFT.name(),
                            Parameters.PlanningAlgorithm.LOCAL_CPOP.name(),
                            Parameters.PlanningAlgorithm.RANDOM.name(),
                            Parameters.PlanningAlgorithm.PSO.name()));
            json.put(
                    "movementModels",
                    Arrays.asList(
                            MODELS.get(0).getKind().name(),
                            MODELS.get(1).getKind().name(),
                            MODELS.get(2).getKind().name()));
            json.put("mainMatrix", mainMatrix);
            json.put("sensitivity", sensitivity);
            json.put("structuralSensitivity", structural);
            json.put("weakMonotonicityViolations", monotonicityDiagnostics());
            json.put("averageRankings", averageRankings());
            json.put("perDagWinners", perDagWinners());
            json.put("pairedStatistics", pairedStatistics());
            if (!sensitivity.isEmpty()) {
                json.put("sensitivityStatistics", sensitivityStatistics());
            }
            if (!structural.isEmpty()) {
                json.put("structuralSensitivityStatistics", structuralStatistics());
            }
            return json;
        }

        String toMarkdown() {
            StringBuilder md = new StringBuilder();
            appendHeader(md);
            appendMainMatrix(md);
            appendMonotonicityDiagnostics(md);
            appendAverageRankings(md);
            appendPerDagWinners(md);
            appendPairedStatistics(md);
            appendStructuralSensitivity(md);
            return md.toString();
        }

        private void appendHeader(StringBuilder md) {
            md.append("# Fat-tree × Scheduling Campaign Measured Results (auto-generated)\n\n");
            md.append("- seed=")
                    .append(FIXED_SEED)
                    .append(", 3-host homogeneous platform, ")
                    .append(dags.size())
                    .append(" DAGs\n");
            md.append("- Main matrix: ")
                    .append(mainMatrix.size())
                    .append(" runs; 3-host sensitivity: ")
                    .append(sensitivity.size())
                    .append(" runs; 4-host structural sensitivity: ")
                    .append(structural.size())
                    .append(" runs\n\n");
        }

        private void appendMainMatrix(StringBuilder md) {
            md.append("## Main Matrix makespan (seconds)\n\n");
            md.append("| DAG |");
            for (DataMovementModel model : MODELS) {
                for (Parameters.PlanningAlgorithm planner : PLANNERS) {
                    md.append(' ')
                            .append(shortPlanner(planner))
                            .append(' ')
                            .append(shortModel(model))
                            .append(" |");
                }
            }
            md.append('\n').append("| --- |");
            for (int i = 0; i < MODELS.size() * PLANNERS.size(); i++) {
                md.append(" --- |");
            }
            md.append('\n');
            for (DagScenario dag : dags) {
                md.append("| ").append(dag.id).append(" |");
                for (DataMovementModel model : MODELS) {
                    for (Parameters.PlanningAlgorithm planner : PLANNERS) {
                        md.append(' ')
                                .append(format(makespan(
                                        dag.id, planner.name(), model.getKind().name())))
                                .append(" |");
                    }
                }
                md.append('\n');
            }
        }

        private void appendMonotonicityDiagnostics(StringBuilder md) {
            md.append("\n## Weak-Monotonicity Diagnostics (V1 ≤ R2 ≤ R6)\n\n");
            List<Map<String, Object>> violations = monotonicityDiagnostics();
            if (violations.isEmpty()) {
                md.append("No violations.\n");
            } else {
                md.append("Found ")
                        .append(violations.size())
                        .append(" cross-model order crossings; the magnitudes are reported as-is below,")
                        .append(" and each cause needs per-scenario analysis.")
                        .append(" Cross-model monotonicity is not a general theorem:\n\n");
                md.append("| DAG | Planner | V1 | R2 | R6 | Max Relative Deviation |\n");
                md.append("| --- | --- | --- | --- | --- | --- |\n");
                for (Map<String, Object> record : violations) {
                    md.append("| ")
                            .append(record.get("dax"))
                            .append(" | ")
                            .append(record.get("planner"))
                            .append(" | ")
                            .append(format((Double) record.get("v1MakespanSeconds")))
                            .append(" | ")
                            .append(format((Double) record.get("r2MakespanSeconds")))
                            .append(" | ")
                            .append(format((Double) record.get("r6MakespanSeconds")))
                            .append(" | ")
                            .append(format((Double) record.get("maxRelativeDeviation")))
                            .append(" |\n");
                }
            }
        }

        private void appendAverageRankings(StringBuilder md) {
            md.append("\n## Average Ranking (makespan ascending)\n\n");
            for (Map.Entry<String, List<String>> entry : averageRankings().entrySet()) {
                md.append("- ")
                        .append(entry.getKey())
                        .append(": ")
                        .append(String.join(" < ", entry.getValue()))
                        .append('\n');
            }
        }

        private void appendPerDagWinners(StringBuilder md) {
            md.append("\n## Per-DAG Winner\n\n| DAG |");
            for (DataMovementModel model : MODELS) {
                md.append(' ').append(shortModel(model)).append(" |");
            }
            md.append("\n| --- | --- | --- | --- |\n");
            Map<String, Map<String, String>> winners = perDagWinners();
            for (DagScenario dag : dags) {
                md.append("| ").append(dag.id).append(" |");
                for (DataMovementModel model : MODELS) {
                    md.append(' ')
                            .append(shortPlanner(Parameters.PlanningAlgorithm.valueOf(
                                    winners.get(model.getKind().name()).get(dag.id))))
                            .append(" |");
                }
                md.append('\n');
            }
        }

        private void appendPairedStatistics(StringBuilder md) {
            md.append("\n## Paired Wilcoxon (across DAGs)\n\n");
            md.append("| Axis | Baseline | Candidate | n (effective) | Median Diff (s)")
                    .append(" | Status | p | Significant |\n");
            md.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
            appendStatRows(md, pairedStatistics());
            if (!sensitivity.isEmpty()) {
                md.append("\n## Sensitivity Paired Wilcoxon").append(" (3 hosts, variant vs baseline topology)\n\n");
                md.append("| Axis | Baseline | Candidate | n (effective) | Median Diff (s)")
                        .append(" | Status | p | Significant |\n");
                md.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
                appendStatRows(md, sensitivityStatistics());
            }
        }

        private void appendStructuralSensitivity(StringBuilder md) {
            if (!structural.isEmpty()) {
                md.append("\n## 4-Host Structural-Sensitivity makespan").append(" (seconds, R6 model)\n\n");
                md.append("| DAG | Planner |");
                for (TopologyVariant variant : structuralVariants()) {
                    md.append(' ').append(variant.id).append(" |");
                }
                md.append('\n').append("| --- | --- |");
                for (int i = 0; i < structuralVariants().size(); i++) {
                    md.append(" --- |");
                }
                md.append('\n');
                for (DagScenario dag : dags) {
                    for (Parameters.PlanningAlgorithm planner : Arrays.asList(
                            Parameters.PlanningAlgorithm.LOCAL_HEFT, Parameters.PlanningAlgorithm.LOCAL_CPOP)) {
                        md.append("| ")
                                .append(dag.id)
                                .append(" | ")
                                .append(shortPlanner(planner))
                                .append(" |");
                        for (TopologyVariant variant : structuralVariants()) {
                            md.append(' ')
                                    .append(format(structuralMakespan(dag.id, planner.name(), variant.id)))
                                    .append(" |");
                        }
                        md.append('\n');
                    }
                }
                md.append("\n## Structural-Sensitivity Paired Wilcoxon")
                        .append(" (4 hosts, variant vs baseline topology)\n\n");
                md.append("| Axis | Baseline | Candidate | n (effective) | Median Diff (s)")
                        .append(" | Status | p | Significant |\n");
                md.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
                appendStatRows(md, structuralStatistics());
            }
        }

        private static void appendStatRows(StringBuilder md, List<Map<String, Object>> stats) {
            for (Map<String, Object> record : stats) {
                md.append("| ")
                        .append(record.get("axis"))
                        .append(" | ")
                        .append(record.get("baseline"))
                        .append(" | ")
                        .append(record.get("candidate"))
                        .append(" | ")
                        .append(record.get("pairedSampleCount"))
                        .append('(')
                        .append(record.get("effectiveSampleCount"))
                        .append(')')
                        .append(" | ")
                        .append(format((Double) record.get("medianDifferenceSeconds")))
                        .append(" | ")
                        .append(record.get("status"))
                        .append(" | ")
                        .append(format((Double) record.get("pValue")))
                        .append(" | ")
                        .append(record.get("significantAtAlpha005"))
                        .append(" |\n");
            }
        }

        private static String format(Double value) {
            return value == null ? "n/a" : String.format(Locale.ROOT, "%.4f", value);
        }

        private static String shortPlanner(Parameters.PlanningAlgorithm planner) {
            switch (planner) {
                case LOCAL_HEFT:
                    return "HEFT";
                case LOCAL_CPOP:
                    return "CPOP";
                case RANDOM:
                    return "RAND";
                case PSO:
                    return "PSO";
                default:
                    return planner.name();
            }
        }

        private static String shortModel(DataMovementModel model) {
            if (model.isFatTreeContentionV1()) {
                return "R6";
            }
            String kind = model.getKind().name();
            return kind.endsWith("CONTENTION_V1") && !kind.contains("FAT_TREE") ? "R2" : "V1";
        }
    }
}
