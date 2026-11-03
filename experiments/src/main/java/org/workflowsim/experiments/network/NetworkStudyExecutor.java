package org.workflowsim.experiments.network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.ExperimentEvidenceContext;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/** Streams an explicit study to independent v4 evidence bundles, then validates its summary. */
public final class NetworkStudyExecutor {
    public static final String INDEX = "network-study.json";

    private NetworkStudyExecutor() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3 && args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: NetworkStudyExecutor <smoke|full> <datasets-root> <new-output-directory> "
                            + "[r10|peft-comparison|sensitivity-r13]");
        }
        NetworkStudyPlan.StudyVariant variant = NetworkStudyPlan.variantArgument(args.length == 4 ? args[3] : null);
        Path result = execute(args[0], variant, Paths.get(args[1]), Paths.get(args[2]));
        System.out.println("NETWORK_STUDY PASSED " + result);
    }

    public static Path execute(String mode, Path datasets, Path directory) throws Exception {
        return execute(mode, NetworkStudyPlan.StudyVariant.R10, datasets, directory);
    }

    public static Path execute(String mode, boolean peftComparison, Path datasets, Path directory) throws Exception {
        return execute(
                mode,
                peftComparison ? NetworkStudyPlan.StudyVariant.PEFT_COMPARISON : NetworkStudyPlan.StudyVariant.R10,
                datasets,
                directory);
    }

    public static Path execute(String mode, NetworkStudyPlan.StudyVariant variant, Path datasets, Path directory)
            throws Exception {
        Path root = directory.toAbsolutePath().normalize();
        if (Files.exists(root)) {
            throw new IOException("Study output already exists: " + root);
        }
        Files.createDirectories(root);
        NetworkStudyPlan plan =
                NetworkStudyPlan.create(mode, variant, datasets.toAbsolutePath().normalize(), root.resolve("inputs"));
        Path protocol = root.resolve("protocol.json");
        ExperimentArtifactWriter.writeJson(protocol, plan.asMap());
        ExperimentEvidenceContext context = ExperimentEvidenceContext.builder(plan.getProtocol())
                .artifact("org.workflowsim", "network-study", "1.0")
                .driver(NetworkStudyExecutor.class, "org/workflowsim/experiments/network")
                .protocol(plan.getProtocol(), protocol)
                .datasetRoot(datasets.toAbsolutePath().normalize())
                .build();
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        boolean disabled = Log.isDisabled();
        Log.disable();
        try {
            boolean heterogeneityAxis = NetworkStudyPlan.SENSITIVITY_PROTOCOL.equals(plan.getProtocol());
            for (NetworkStudyPlan.WorkflowCase workflow : plan.getWorkflows()) {
                for (NetworkStudyPlan.Condition condition : plan.getConditions()) {
                    int vmCount = condition.vmCount;
                    String network = condition.network;
                    for (Parameters.PlanningAlgorithm planner : plan.getPlanners()) {
                        for (long seed : plan.seeds(planner)) {
                            Map<String, Object> record = runStudyCell(
                                    workflow,
                                    condition,
                                    vmCount,
                                    network,
                                    planner,
                                    seed,
                                    heterogeneityAxis,
                                    root,
                                    context);
                            records.add(record);
                        }
                    }
                }
                System.out.println("[network-study] " + workflow.id + ": " + records.size() + "/" + plan.getRunCount());
                writeIndex(root, plan, records);
            }
        } finally {
            Log.setDisabled(disabled);
            writeIndex(root, plan, records);
        }
        NetworkStudyValidator.validate(root.resolve(INDEX));
        return root.resolve(INDEX);
    }

    /** Execution and evidence writing of a single study cell (moved verbatim). */
    private static Map<String, Object> runStudyCell(
            NetworkStudyPlan.WorkflowCase workflow,
            NetworkStudyPlan.Condition condition,
            int vmCount,
            String network,
            Parameters.PlanningAlgorithm planner,
            long seed,
            boolean heterogeneityAxis,
            Path root,
            ExperimentEvidenceContext context) {
        String runId = workflow.id + "-v" + vmCount + "-" + network
                + (heterogeneityAxis ? "-" + condition.heterogeneity : "")
                + "-" + planner + "-s" + seed;
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("runId", runId);
        record.put("workflowId", workflow.id);
        record.put("family", workflow.family);
        record.put("population", workflow.population);
        record.put("vmCount", vmCount);
        record.put("network", network);
        if (heterogeneityAxis) {
            record.put("heterogeneity", condition.heterogeneity);
        }
        record.put("planner", planner.name());
        record.put("seed", seed);
        try {
            SimulationConfig config =
                    NetworkStudyPlan.configuration(workflow.path.toString(), vmCount, network, planner, seed);
            SimulationReport report = new SimulationRunner()
                    .run(config, NetworkStudyPlan.platform(vmCount, network, condition.heterogeneity));
            if (!report.isWorkflowCompletedSuccessfully()) {
                throw new IllegalStateException("Incomplete workflow");
            }
            if (!workflow.sha256.equals(report.getInputs().get(0).getSha256())) {
                throw new IllegalStateException("Study input changed after protocol was frozen");
            }
            ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                    ExperimentArtifactWriter.write(report, root.resolve("runs").resolve(runId), "result", context);
            ExperimentArtifactValidator.validate(artifacts.getManifest());
            record.put("status", "COMPLETED_SUCCESSFULLY");
            record.put(
                    "manifest",
                    root.relativize(artifacts.getManifest()).toString().replace('\\', '/'));
            record.put("inputSha256", report.getInputs().get(0).getSha256());
            record.put("taskCount", report.getWorkflowProfile().getTaskCount());
            record.put("makespanSeconds", report.getMakespan());
            record.put("logicalCompletionSeconds", report.getLogicalTaskCompletionSeconds());
            record.put("meanWaitingSeconds", report.getMetrics().getMeanComputeTotalWaitingTimeSeconds());
            record.put("p95WaitingSeconds", report.getMetrics().getP95ComputeTotalWaitingTimeSeconds());
            record.put("meanVmUtilization", report.getMetrics().getMeanVmModeledIntervalUtilization());
        } catch (Exception failure) {
            record.put("status", "FAILED");
            record.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        return record;
    }

    private static void writeIndex(Path root, NetworkStudyPlan plan, List<Map<String, Object>> records)
            throws IOException {
        Map<String, Object> index = new LinkedHashMap<String, Object>();
        index.put("schema", "workflowsim-network-study-v1");
        index.put("plan", plan.asMap());
        index.put("runs", records);
        Map<String, Object> summary = NetworkStudySummary.summarize(records);
        if (records.size() != plan.getRunCount()) {
            summary.put("status", "RUNNING_OR_INTERRUPTED");
        }
        index.put("summary", summary);
        ExperimentArtifactWriter.writeJson(root.resolve(INDEX), index);
        Files.write(root.resolve("results.md"), markdown(plan, summary).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static String markdown(NetworkStudyPlan plan, Map<String, Object> summary) {
        boolean peft = NetworkStudyPlan.PEFT_COMPARISON_PROTOCOL.equals(plan.getProtocol());
        boolean sensitivity = NetworkStudyPlan.SENSITIVITY_PROTOCOL.equals(plan.getProtocol());
        StringBuilder out = new StringBuilder(
                sensitivity
                        ? "# Sensitivity Response-Surface Study (R13)\n\n"
                        : peft
                                ? "# PEFT Comparison Study (S5, r10 frozen matrix)\n\n"
                                : "# Network-Constrained Scheduling Study R10\n\n");
        out.append("Protocol: ")
                .append(plan.getProtocol())
                .append("; planned ")
                .append(plan.getRunCount())
                .append(" runs; executed ")
                .append(summary.get("runCount"))
                .append(" runs; status ")
                .append(summary.get("status"))
                .append(".\n\n")
                .append("Execution model: ")
                .append(org.workflowsim.utils.TaskExecutionModel.EXECUTION_SEMANTICS)
                .append(". This is a new execution under the corrected model: it reuses the parameter"
                        + " matrix but does not claim the frozen R10/R12/R13 results; passing the"
                        + " integrity check does not mean bit-identical scientific quantities.\n\n")
                .append(
                        sensitivity
                                ? "All three list planners (HEFT/CPOP/PEFT) are deterministic, so each"
                                        + " condition runs once (seed 11); the baseline is LOCAL_HEFT and the"
                                        + " candidates are LOCAL_CPOP/LOCAL_PEFT. The response surface spans VM"
                                        + " count (4/8/16/32) × link bandwidth (1 MB/s at the endpoint;"
                                        + " 0.125/0.5/1.25/5 MB/s for fat-tree), and the heterogeneity block"
                                        + " adds the MILD/STRONG/EXTREME deterministic VM MIPS mixes on"
                                        + " fat-tree-constrained. At VM=32 the fat-tree uses k=8 (k=4 holds"
                                        + " only 16 hosts), which affects cross-VM-count comparisons; read"
                                        + " those descriptively only. This round reuses the R13 parameter"
                                        + " matrix and does not inherit the pre-registered verdicts of the"
                                        + " old model; mechanistic explanation requires per-DAG mapping"
                                        + " diagnostics and independent ablations, and cannot be proven by"
                                        + " completion time alone. All surfaces are descriptive; no parameter"
                                        + " fitting is performed. Classic DAX and synthetic workloads are"
                                        + " kept separate, and exploratory conclusions from a fixed sample"
                                        + " do not extrapolate to real clouds. A positive improvement"
                                        + " percentage means the candidate is faster than HEFT.\n\n"
                                : peft
                                        ? "All three list planners (HEFT/CPOP/PEFT) are deterministic, so each"
                                                + " condition runs once (seed 11); the baseline is LOCAL_HEFT"
                                                + " and the candidates are LOCAL_CPOP/LOCAL_PEFT. Seeds are"
                                                + " aggregated within each DAG first, then paired by DAG."
                                                + " Classic DAX and synthetic workloads are kept separate, and"
                                                + " exploratory conclusions from a fixed sample do not"
                                                + " extrapolate to real clouds. A positive improvement"
                                                + " percentage means the candidate is faster than HEFT. The"
                                                + " platform VMs are homogeneous (1000 MIPS), so the PEFT OCT"
                                                + " lookahead term is the same on every VM and its difference"
                                                + " from HEFT comes only from task ordering.\n\n"
                                        : "HEFT/CPOP run once per condition; RANDOM/PSO use their declared"
                                                + " independent seeds. Seeds are aggregated within each DAG"
                                                + " first, then paired by DAG. Classic DAX and synthetic"
                                                + " workloads are kept separate, and exploratory conclusions"
                                                + " from a fixed sample do not extrapolate to real clouds. A"
                                                + " positive improvement percentage means the candidate is"
                                                + " faster than HEFT.\n\n")
                .append(
                        sensitivity
                                ? "| Source | VM | Network | Heterogeneity | Candidate | DAG Pairs"
                                        + " | Win/Tie/Loss | Median Improvement % | Raw p | Holm p |\n"
                                        + "|---|---:|---|---|---|---:|---|---:|---:|---:|\n"
                                : "| Source | VM | Network | Candidate | DAG Pairs"
                                        + " | Win/Tie/Loss | Median Improvement % | Raw p | Holm p |\n"
                                        + "|---|---:|---|---|---:|---|---:|---:|---:|\n");
        for (Map<String, Object> row : (List<Map<String, Object>>) summary.get("comparisons")) {
            out.append("| ")
                    .append(row.get("population"))
                    .append(" | ")
                    .append(row.get("vmCount"))
                    .append(" | ")
                    .append(row.get("network"));
            if (sensitivity) {
                out.append(" | ").append(row.get("heterogeneity"));
            }
            out.append(" | ")
                    .append(row.get("candidate"))
                    .append(" | ")
                    .append(row.get("dagPairs"))
                    .append(" | ")
                    .append(row.get("wins"))
                    .append('/')
                    .append(row.get("ties"))
                    .append('/')
                    .append(row.get("losses"))
                    .append(" | ")
                    .append(format(row.get("medianImprovementPercent")))
                    .append(" | ")
                    .append(format(row.get("pValue")))
                    .append(" | ")
                    .append(format(row.get("holmAdjustedPValue")))
                    .append(" |\n");
        }
        out.append(
                sensitivity
                        ? "\nExact two-sided sign test. Holm adjustment is applied per family of two"
                                + " candidates within the same source/VM/network/heterogeneity. Significance"
                                + " power is limited for small samples; read the effect magnitude and the"
                                + " per-DAG results together. The 1 MB/s endpoint, the"
                                + " 0.125/0.5/1.25/5 MB/s fat-tree links, 1000 MIPS homogeneous VMs and the"
                                + " declared MIPS mixes for heterogeneous blocks are model assumptions; every"
                                + " network variant starts transfers when ready, avoiding a mixed transfer"
                                + " start point.\n"
                        : peft
                                ? "\nExact two-sided sign test. Holm adjustment is applied per family of two"
                                        + " candidates within the same source/VM/network. Significance power"
                                        + " is limited for small samples; read the effect magnitude and the"
                                        + " per-DAG results together. The 0.125/1.25 MB/s links, the 1 MB/s"
                                        + " endpoint and 1000 MIPS VMs are model assumptions; all three"
                                        + " network variants start transfers when ready, avoiding a mixed"
                                        + " transfer start point.\n"
                                : "\nExact two-sided sign test; multiple seeds are not treated as multiple"
                                        + " DAGs. Holm adjustment is applied per family of three candidates"
                                        + " within the same source/VM/network. Significance power is limited"
                                        + " for small samples; read the effect magnitude and the per-DAG"
                                        + " results together. The 0.125/1.25 MB/s links, the 1 MB/s endpoint"
                                        + " and 1000 MIPS VMs are model assumptions; all three network"
                                        + " variants start transfers when ready, avoiding a mixed transfer"
                                        + " start point.\n");
        return out.toString();
    }

    private static String format(Object value) {
        return value == null ? "n/a" : String.format(java.util.Locale.ROOT, "%.6f", ((Number) value).doubleValue());
    }
}
