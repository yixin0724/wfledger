package org.workflowsim.experiments.standard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.cloudbus.cloudsim.Consts;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentCampaignArtifactWriter;
import org.workflowsim.experiment.ExperimentCampaignExecutor;
import org.workflowsim.experiment.ExperimentCampaignResult;
import org.workflowsim.experiment.ExperimentPlan;
import org.workflowsim.experiment.HolmStepDownCorrection;
import org.workflowsim.experiment.PairedWilcoxonSignificance;
import org.workflowsim.experiment.RandomizationDesign;
import org.workflowsim.experiment.SeedPlan;
import org.workflowsim.experiment.SimulationMetrics;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskExecutionModel;

import com.google.gson.GsonBuilder;

/** Driver of the standard workflow-scheduling study ({@value #PROTOCOL_ID}). */
public final class StandardStudy {

    public static final String PROTOCOL_ID = "standard-study-v1";

    public static final double TICK_SECONDS = 0.001;

    public static final double BOUNDED_SLOWDOWN_TAU_SECONDS = 1.0;

    public static final double RUNTIME_REFERENCE_MIPS = 1000.0;

    public static final int EVENT_RECORDER_CAP = 2_000_000;

    public static final long ROOT_SEED = 20261125L;

    public static final List<String> TESTED_METRICS =
            Collections.unmodifiableList(Arrays.asList("makespanSeconds", "energyJoules", "costUnits"));

    /** Decision layers; candidates are never pooled across layers. */
    public enum Layer {
        STATIC("SHARED_STORAGE_HEFT", "SHARED_STORAGE_HEFT", "SHARED_STORAGE_CPOP", "SHARED_STORAGE_PEFT"),
        ONLINE("FCFS", "FCFS", "READY_BATCH_MINMIN", "READY_BATCH_MAXMIN", "READY_BATCH_MCT");

        private final String baseline;
        private final List<String> members;

        Layer(String baseline, String... members) {
            this.baseline = baseline;
            this.members = Collections.unmodifiableList(Arrays.asList(members));
        }

        public String getBaseline() {
            return baseline;
        }

        public List<String> getMembers() {
            return members;
        }
    }

    /** Evidence classes of datasets/README.md; one study uses exactly one. */
    public enum EvidenceClass {
        WFGEN_SYNTHETIC("/datasets/wfgen-standard/", "/datasets/wfformat/"),
        WFINSTANCES_REAL_DERIVED("/datasets/wfinstances/");

        private final List<String> pathMarkers;

        EvidenceClass(String... pathMarkers) {
            this.pathMarkers = Arrays.asList(pathMarkers);
        }

        static EvidenceClass detect(Path path) {
            String normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/');
            for (EvidenceClass value : values()) {
                for (String marker : value.pathMarkers) {
                    if (normalized.contains(marker)) {
                        return value;
                    }
                }
            }
            return null;
        }
    }

    private StandardStudy() {}

    public static Result run(Design design, Path outputDirectory) throws Exception {
        Path output = prepareEmptyDirectory(outputDirectory);
        Log.disable();
        List<Instance> instances = loadInstances(design);
        ExperimentPlan.Builder plan = ExperimentPlan.builder("standard-study");
        Map<String, CellContext> contexts = new LinkedHashMap<String, CellContext>();
        for (int vmCount : design.vmCounts) {
            double meanMips = StandardPlatforms.meanMips(vmCount);
            for (int c = 0; c < design.ccrTargets.size(); c++) {
                double target = design.ccrTargets.get(c).doubleValue();
                for (int m = 0; m < design.mtbfRatios.size(); m++) {
                    Double mtbf = design.mtbfRatios.get(m);
                    String factorCell = factorCellId(vmCount, c, mtbf == null ? -1 : m);
                    for (Instance instance : instances) {
                        CcrCalibrator.Calibration calibration =
                                CcrCalibrator.calibrate(instance.tasks, target, meanMips);
                        PlatformProfile platform = StandardPlatforms.plain(
                                vmCount, calibration.getStorageRateMbPerSecond(), design.idleToMaxPowerRatio);
                        FailureModelConfig failure = mtbf == null
                                ? FailureModelConfig.disabled()
                                : StandardFailureModel.weibull(
                                        vmIds(platform),
                                        mtbf.doubleValue(),
                                        calibration.getComputeSecondsAtMeanMips() / instance.tasks.size(),
                                        instance.tasks.size());
                        SeedPlan seeds = mtbf == null
                                ? SeedPlan.deterministic(ROOT_SEED)
                                : SeedPlan.derived(
                                        RandomizationDesign.COMMON_ROOT_SEEDS_NOT_EVENT_KEYED_CRN,
                                        ROOT_SEED,
                                        design.replications);
                        String group = factorCell + "-i" + instance.index;
                        double serial = serialOnFastestSeconds(
                                instance.tasks, calibration.getStorageRateMbPerSecond(), StandardPlatforms.MAX_MIPS);
                        for (String candidate : design.candidates) {
                            String cellId = group + "-" + candidate;
                            plan.addCell(new ExperimentPlan.Cell(
                                    cellId,
                                    group,
                                    candidate,
                                    candidate.equals(design.layer.getBaseline()),
                                    config(design, instance.path, vmCount, candidate, failure),
                                    platform,
                                    seeds,
                                    null));
                            contexts.put(
                                    cellId,
                                    new CellContext(
                                            factorCell,
                                            vmCount,
                                            target,
                                            mtbf,
                                            instance,
                                            candidate,
                                            calibration,
                                            serial));
                        }
                    }
                }
            }
        }
        ExperimentPlan built = plan.build();
        ExperimentCampaignResult campaign = new ExperimentCampaignExecutor().execute(built);
        if (design.writeEvidence) {
            ExperimentCampaignArtifactWriter.write(campaign, output.resolve("campaign"));
        }
        List<Map<String, Object>> rows = rows(campaign, contexts, design);
        List<Map<String, Object>> comparisons = comparisons(rows, design);
        Map<String, Object> header = header(design, instances);
        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("protocol", header);
        document.put("rows", rows);
        document.put("comparisons", comparisons);
        Path json = output.resolve("standard-study.json");
        Files.write(
                json,
                (new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(document) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
        Path rowsCsv = output.resolve("standard-study-rows.csv");
        Path comparisonsCsv = output.resolve("standard-study-comparisons.csv");
        writeCsv(rowsCsv, header, rows);
        writeCsv(comparisonsCsv, header, comparisons);
        return new Result(json, rowsCsv, comparisonsCsv, header, rows, comparisons);
    }

    public static double serialOnFastestSeconds(List<Task> tasks, double storageRateMbPerSecond, double fastestMips) {
        List<Task> ordered = new ArrayList<Task>(tasks);
        Collections.sort(ordered, (a, b) -> Integer.compare(a.getCloudletId(), b.getCloudletId()));
        double seconds = 0.0;
        for (Task task : ordered) {
            double read = 0.0;
            for (FileItem file : task.getFileList()) {
                if (file.isRealInputFile(task.getFileList())) {
                    read += file.getSize() / (double) Consts.MILLION / storageRateMbPerSecond;
                }
            }
            long envelope = task.getCloudletLength() + (long) (fastestMips * read);
            seconds += envelope / fastestMips;
        }
        return seconds;
    }

    private static SimulationConfig config(
            Design design, String path, int vmCount, String candidate, FailureModelConfig failure) {
        SimulationConfig.Builder builder = SimulationConfig.builder(path, vmCount)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .costModel(Parameters.CostModel.VM)
                .runtimeReferenceMips(RUNTIME_REFERENCE_MIPS)
                .cloudSimMinEventIntervalSeconds(TICK_SECONDS)
                .boundedSlowdownThresholdSeconds(BOUNDED_SLOWDOWN_TAU_SECONDS)
                .eventRecorderCap(EVENT_RECORDER_CAP)
                .failureModel(failure)
                .randomSeed(ROOT_SEED);
        if (design.layer == Layer.STATIC) {
            builder.schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                    .planningAlgorithm(PlanningAlgorithm.valueOf(candidate));
        } else {
            builder.schedulingAlgorithm(SchedulingAlgorithm.valueOf(candidate));
        }
        return builder.build();
    }

    private static List<Map<String, Object>> rows(
            ExperimentCampaignResult campaign, Map<String, CellContext> contexts, Design design) {
        Map<String, List<SimulationReport>> byCell = new LinkedHashMap<String, List<SimulationReport>>();
        for (ExperimentCampaignResult.Run run : campaign.getRuns()) {
            List<SimulationReport> reports = byCell.get(run.getCell().getId());
            if (reports == null) {
                reports = new ArrayList<SimulationReport>();
                byCell.put(run.getCell().getId(), reports);
            }
            reports.add(run.getReport());
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, CellContext> entry : contexts.entrySet()) {
            CellContext context = entry.getValue();
            List<SimulationReport> reports = byCell.get(entry.getKey());
            if (reports == null || reports.isEmpty()) {
                throw new IllegalStateException("No run for declared cell " + entry.getKey());
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("cellId", entry.getKey());
            row.put("factorCell", context.factorCell);
            row.put("vmCount", context.vmCount);
            row.put("ccrTarget", context.ccrTarget);
            row.put("mtbfToTaskRatio", context.mtbfRatio);
            row.put("instanceIndex", context.instance.index);
            row.put("instance", context.instance.fileName);
            row.put("instanceSha256", context.instance.sha256);
            row.put("candidate", context.candidate);
            row.put("baseline", context.candidate.equals(design.layer.getBaseline()));
            row.put("storageRateMbPerSecond", context.calibration.getStorageRateMbPerSecond());
            row.put("ccrAchieved", context.calibration.getAchievedCcr());
            row.put("replications", reports.size());
            int completed = 0;
            for (SimulationReport report : reports) {
                if (report.isWorkflowCompletedSuccessfully()) {
                    completed++;
                }
            }
            if (completed != reports.size()) {
                throw new IllegalStateException("Cell " + entry.getKey() + " has " + (reports.size() - completed)
                        + " incomplete workflow run(s); the standard study reports complete runs only");
            }
            double makespan = mean(reports, r -> r.getMakespan());
            row.put("makespanSeconds", makespan);
            row.put("slrIoInclusive", slr(reports));
            row.put("serialOnFastestSeconds", context.serialOnFastestSeconds);
            row.put("speedupVsSerialOnFastest", context.serialOnFastestSeconds / makespan);
            row.put("meanWaitingSeconds", mean(reports, r -> r.getMetrics().getMeanComputeTotalWaitingTimeSeconds()));
            row.put("meanBoundedSlowdown", mean(reports, r -> r.getMetrics().getMeanComputeTrueSlowdown()));
            row.put("boundedSlowdownTauSeconds", BOUNDED_SLOWDOWN_TAU_SECONDS);
            row.put("boundedSlowdownClampedFraction", mean(reports, r -> r.getMetrics()
                    .getBoundedSlowdownFloorClampedFraction()));
            row.put("meanVmUtilization", mean(reports, r -> r.getMetrics().getMeanVmModeledIntervalUtilization()));
            row.put("inputReadShareOfBusyTime", mean(reports, r -> r.getMetrics()
                    .getInputReadShareOfBusyTime()));
            row.put("costUnits", mean(reports, r -> r.getMetrics().getTotalModeledProcessingCost()));
            row.put("energyJoules", mean(reports, r -> r.getEnergyAccounting().getTotalEnergyJoules()));
            row.put("retryAmplificationRatio", mean(reports, r -> r.getMetrics().getRetryAmplificationRatio()));
            rows.add(row);
        }
        return rows;
    }

    private static Double slr(List<SimulationReport> reports) {
        double sum = 0.0;
        for (SimulationReport report : reports) {
            SimulationMetrics metrics = report.getMetrics();
            if (!metrics.isControlledSharedStorageCriticalPathReferenceAvailable()) {
                return null;
            }
            sum += metrics.getControlledSharedStorageScheduleLengthRatio();
        }
        return Double.valueOf(sum / reports.size());
    }

    private static List<Map<String, Object>> comparisons(List<Map<String, Object>> rows, Design design) {
        Map<String, Map<String, Map<Integer, Map<String, Object>>>> byFactor =
                new LinkedHashMap<String, Map<String, Map<Integer, Map<String, Object>>>>();
        for (Map<String, Object> row : rows) {
            String factor = (String) row.get("factorCell");
            Map<String, Map<Integer, Map<String, Object>>> byCandidate = byFactor.get(factor);
            if (byCandidate == null) {
                byCandidate = new LinkedHashMap<String, Map<Integer, Map<String, Object>>>();
                byFactor.put(factor, byCandidate);
            }
            Map<Integer, Map<String, Object>> byInstance = byCandidate.get(row.get("candidate"));
            if (byInstance == null) {
                byInstance = new LinkedHashMap<Integer, Map<String, Object>>();
                byCandidate.put((String) row.get("candidate"), byInstance);
            }
            if (byInstance.put((Integer) row.get("instanceIndex"), row) != null) {
                throw new IllegalStateException("Duplicate pairing unit in " + factor);
            }
        }
        List<Map<String, Object>> comparisons = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Map<String, Map<Integer, Map<String, Object>>>> factor : byFactor.entrySet()) {
            Map<Integer, Map<String, Object>> baseline = factor.getValue().get(design.layer.getBaseline());
            for (String metric : TESTED_METRICS) {
                List<Map<String, Object>> family = new ArrayList<Map<String, Object>>();
                List<Double> rawP = new ArrayList<Double>();
                for (String candidate : design.candidates) {
                    if (candidate.equals(design.layer.getBaseline())) {
                        continue;
                    }
                    Map<Integer, Map<String, Object>> candidateRows =
                            factor.getValue().get(candidate);
                    if (candidateRows == null || !candidateRows.keySet().equals(baseline.keySet())) {
                        throw new IllegalStateException("Pairing invariant violated in " + factor.getKey()
                                + ": candidate " + candidate + " does not cover the baseline instances");
                    }
                    List<Double> b = new ArrayList<Double>();
                    List<Double> c = new ArrayList<Double>();
                    for (Integer index : baseline.keySet()) {
                        b.add((Double) baseline.get(index).get(metric));
                        c.add((Double) candidateRows.get(index).get(metric));
                    }
                    PairedWilcoxonSignificance test = PairedWilcoxonSignificance.evaluate(b, c);
                    Map<String, Object> comparison = new LinkedHashMap<String, Object>();
                    comparison.put("factorCell", factor.getKey());
                    comparison.put("metric", metric);
                    comparison.put("baseline", design.layer.getBaseline());
                    comparison.put("candidate", candidate);
                    comparison.put("pairedInstances", b.size());
                    comparison.put("wilcoxonStatus", test.getStatus());
                    comparison.put("effectiveNonZeroPairs", test.getEffectiveSampleCount());
                    comparison.put("medianDifference", test.getMedianDifference());
                    comparison.put("rawPValue", test.getPValue());
                    comparison.put("medianRatio", allPositive(b) ? PairedEffectSize.medianRatio(b, c) : null);
                    comparison.put("rankBiserial", PairedEffectSize.matchedPairsRankBiserial(b, c));
                    comparison.put("cliffsDelta", PairedEffectSize.cliffsDelta(b, c));
                    family.add(comparison);
                    rawP.add(test.getPValue());
                }
                List<HolmStepDownCorrection.Adjusted> adjusted = HolmStepDownCorrection.adjustWithinFamily(rawP);
                for (int i = 0; i < family.size(); i++) {
                    family.get(i).put("holmFamilySize", family.size());
                    family.get(i).put("holmAdjustedPValue", adjusted.get(i).getAdjustedPValue());
                    family.get(i).put("significantAfterHolm", adjusted.get(i).isSignificantAfterHolm());
                }
                comparisons.addAll(family);
            }
        }
        return comparisons;
    }

    private static boolean allPositive(List<Double> values) {
        for (Double value : values) {
            if (!(value.doubleValue() > 0.0)) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> header(Design design, List<Instance> instances) throws IOException {
        Map<String, Object> header = new LinkedHashMap<String, Object>();
        header.put("protocolId", PROTOCOL_ID);
        header.put("executionSemantics", TaskExecutionModel.EXECUTION_SEMANTICS);
        header.put("gitCommit", git("rev-parse", "HEAD"));
        header.put("gitWorkingTreeDirty", gitDirty());
        header.put("evidenceClass", design.evidenceClass.name());
        header.put("layer", design.layer.name());
        header.put("baseline", design.layer.getBaseline());
        header.put("candidates", design.candidates);
        header.put("vmCounts", design.vmCounts);
        header.put("ccrTargets", design.ccrTargets);
        header.put("mtbfToTaskRatios", design.mtbfRatios);
        header.put("replicationsWithFailures", design.replications);
        header.put("pairingUnit", "WORKFLOW_INSTANCE");
        header.put("statisticalTest", "PAIRED_WILCOXON_SIGNED_RANK_OVER_INSTANCES_EXACT_UP_TO_25_NONZERO");
        header.put("multiplicity", "HOLM_WITHIN_FACTOR_CELL_AND_METRIC_OVER_NON_BASELINE_CANDIDATES");
        header.put("testedMetrics", TESTED_METRICS);
        header.put("withinInstanceAggregation", "ARITHMETIC_MEAN_OVER_SEEDS_BEFORE_PAIRING");
        Map<String, Object> constants = new LinkedHashMap<String, Object>();
        constants.put("tickSeconds", TICK_SECONDS);
        constants.put("boundedSlowdownTauSeconds", BOUNDED_SLOWDOWN_TAU_SECONDS);
        constants.put("runtimeReferenceMips", RUNTIME_REFERENCE_MIPS);
        constants.put("rootSeed", ROOT_SEED);
        constants.put("eventRecorderCap", EVENT_RECORDER_CAP);
        constants.put("minMips", StandardPlatforms.MIN_MIPS);
        constants.put("maxMips", StandardPlatforms.MAX_MIPS);
        constants.put("speedFormula", "mips_i = 500 + 1500 * i / (N - 1)");
        constants.put("pricePerMipsSecond", StandardPlatforms.PRICE_PER_MIPS_SECOND);
        constants.put("billing", "PER_SIMULATED_SECOND_OF_CHARGED_CPU_TIME_CONTINUOUS");
        constants.put("wattsPerMips", StandardPlatforms.WATTS_PER_MIPS);
        constants.put("idleToMaxPowerRatio", design.idleToMaxPowerRatio);
        constants.put("weibullShape", StandardFailureModel.SCHROEDER_GIBSON_SHAPE);
        constants.put("retryBudgetPerTask", StandardFailureModel.DEFAULT_RETRY_BUDGET_PER_TASK);
        constants.put(
                "ccrDefinition",
                "sum real-input MB / R divided by sum L_t / meanMips; R solved in closed form, not rounded");
        constants.put("speedupDefinition", "sum_t (L_t + floor(2000 * in_t)) / 2000 divided by makespan");
        constants.put("dataMovementModel", "LEGACY_WORKFLOWSIM_V1");
        constants.put("fileSystem", "SHARED");
        header.put("constants", constants);
        List<Map<String, Object>> corpus = new ArrayList<Map<String, Object>>();
        StringBuilder index = new StringBuilder();
        for (Instance instance : instances) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("index", instance.index);
            entry.put("file", instance.fileName);
            entry.put("path", instance.path);
            entry.put("sha256", instance.sha256);
            entry.put("taskCount", instance.tasks.size());
            corpus.add(entry);
            index.append(instance.sha256).append("  ").append(instance.fileName).append('\n');
        }
        header.put("corpus", corpus);
        header.put("corpusIndexSha256", sha256(index.toString().getBytes(StandardCharsets.UTF_8)));
        header.put(
                "corpusIndexDefinition", "SHA-256 of UTF-8 lines '<sha256>  <file name>\\n' in ascending path order");
        return header;
    }

    private static List<Instance> loadInstances(Design design) throws IOException {
        List<Path> files = new ArrayList<Path>(design.instances);
        Collections.sort(files, (a, b) -> a.toAbsolutePath()
                .normalize()
                .toString()
                .compareTo(b.toAbsolutePath().normalize().toString()));
        SimulationConfig conversion = SimulationConfig.builder("unused.json", 1)
                .runtimeReferenceMips(RUNTIME_REFERENCE_MIPS)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .build();
        List<Instance> instances = new ArrayList<Instance>();
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i).toAbsolutePath().normalize();
            if (!Files.isRegularFile(file) || !file.getFileName().toString().endsWith(".json")) {
                throw new IllegalArgumentException("Standard study inputs must be WfFormat JSON files: " + file);
            }
            EvidenceClass detected = EvidenceClass.detect(file);
            if (detected != null && detected != design.evidenceClass) {
                throw new IllegalArgumentException("Input " + file + " belongs to evidence class " + detected
                        + " but the study declares " + design.evidenceClass
                        + "; evidence classes must not be mixed (datasets/README.md)");
            }
            List<Task> tasks = CcrCalibrator.parse(file.toString(), conversion);
            instances.add(new Instance(i, file.toString(), file.getFileName().toString(), sha256(file), tasks));
        }
        return instances;
    }

    private static List<Integer> vmIds(PlatformProfile platform) {
        List<Integer> ids = new ArrayList<Integer>();
        for (PlatformProfile.VmSpec vm : platform.getVms()) {
            ids.add(Integer.valueOf(vm.getId()));
        }
        return ids;
    }

    private static String factorCellId(int vmCount, int ccrIndex, int mtbfIndex) {
        return "v" + vmCount + "-c" + ccrIndex + (mtbfIndex < 0 ? "" : "-f" + mtbfIndex);
    }

    private interface ReportValue {
        double of(SimulationReport report);
    }

    private static double mean(List<SimulationReport> reports, ReportValue value) {
        double sum = 0.0;
        for (SimulationReport report : reports) {
            sum += value.of(report);
        }
        return sum / reports.size();
    }

    private static void writeCsv(Path target, Map<String, Object> header, List<Map<String, Object>> records)
            throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("# protocolId=").append(header.get("protocolId")).append('\n');
        text.append("# executionSemantics=")
                .append(header.get("executionSemantics"))
                .append('\n');
        text.append("# gitCommit=").append(header.get("gitCommit")).append('\n');
        text.append("# corpusIndexSha256=")
                .append(header.get("corpusIndexSha256"))
                .append('\n');
        text.append("# layer=")
                .append(header.get("layer"))
                .append(" evidenceClass=")
                .append(header.get("evidenceClass"))
                .append('\n');
        text.append("# constants=")
                .append(new GsonBuilder().create().toJson(header.get("constants")))
                .append('\n');
        if (!records.isEmpty()) {
            List<String> columns = new ArrayList<String>(records.get(0).keySet());
            text.append(String.join(",", columns)).append('\n');
            for (Map<String, Object> record : records) {
                if (!record.keySet().equals(new java.util.LinkedHashSet<String>(columns))) {
                    throw new IllegalStateException("CSV records must share one column set");
                }
                List<String> cells = new ArrayList<String>();
                for (String column : columns) {
                    cells.add(csv(record.get(column)));
                }
                text.append(String.join(",", cells)).append('\n');
            }
        }
        Files.write(target, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String text = value instanceof Double ? Double.toString(((Double) value).doubleValue()) : value.toString();
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static Path prepareEmptyDirectory(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (Files.exists(normalized)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(normalized)) {
                if (entries.iterator().hasNext()) {
                    throw new IllegalArgumentException(
                            "Standard study output must be a new or empty directory: " + normalized);
                }
            }
        } else {
            Files.createDirectories(normalized);
        }
        return normalized;
    }

    private static String git(String... arguments) {
        List<String> command = new ArrayList<String>();
        command.add("git");
        command.addAll(Arrays.asList(arguments));
        try {
            Process process =
                    new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] bytes;
            try (InputStream stream = process.getInputStream()) {
                bytes = readAll(stream);
            }
            if (process.waitFor() != 0) {
                return "UNAVAILABLE_GIT_EXIT_" + process.exitValue();
            }
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            return "UNAVAILABLE_GIT_NOT_EXECUTABLE";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading the git commit", exception);
        }
    }

    private static Boolean gitDirty() {
        String status = git("status", "--porcelain");
        if (status.startsWith("UNAVAILABLE_")) {
            return null;
        }
        return Boolean.valueOf(!status.isEmpty());
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    static String sha256(Path file) throws IOException {
        return sha256(Files.readAllBytes(file));
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            throw new IllegalArgumentException("usage: <output-dir> <STATIC|ONLINE> <evidence-class> <vm-counts> "
                    + "<ccr-targets> <instance.json>...");
        }
        Design.Builder builder = Design.builder(Layer.valueOf(args[1]), EvidenceClass.valueOf(args[2]));
        builder.vmCounts(ints(args[3])).ccrTargets(doubles(args[4]));
        List<Path> files = new ArrayList<Path>();
        for (int i = 5; i < args.length; i++) {
            files.add(java.nio.file.Paths.get(args[i]));
        }
        Result result = run(builder.instances(files).build(), java.nio.file.Paths.get(args[0]));
        System.out.println(result.getJson());
    }

    private static List<Integer> ints(String csv) {
        List<Integer> values = new ArrayList<Integer>();
        for (String part : csv.split(",")) {
            values.add(Integer.valueOf(part.trim()));
        }
        return values;
    }

    private static List<Double> doubles(String csv) {
        List<Double> values = new ArrayList<Double>();
        for (String part : csv.split(",")) {
            values.add(Double.valueOf(part.trim()));
        }
        return values;
    }

    private static final class Instance {
        private final int index;
        private final String path;
        private final String fileName;
        private final String sha256;
        private final List<Task> tasks;

        Instance(int index, String path, String fileName, String sha256, List<Task> tasks) {
            this.index = index;
            this.path = path;
            this.fileName = fileName;
            this.sha256 = sha256;
            this.tasks = tasks;
        }
    }

    private static final class CellContext {
        private final String factorCell;
        private final int vmCount;
        private final double ccrTarget;
        private final Double mtbfRatio;
        private final Instance instance;
        private final String candidate;
        private final CcrCalibrator.Calibration calibration;
        private final double serialOnFastestSeconds;

        CellContext(
                String factorCell,
                int vmCount,
                double ccrTarget,
                Double mtbfRatio,
                Instance instance,
                String candidate,
                CcrCalibrator.Calibration calibration,
                double serialOnFastestSeconds) {
            this.factorCell = factorCell;
            this.vmCount = vmCount;
            this.ccrTarget = ccrTarget;
            this.mtbfRatio = mtbfRatio;
            this.instance = instance;
            this.candidate = candidate;
            this.calibration = calibration;
            this.serialOnFastestSeconds = serialOnFastestSeconds;
        }
    }

    /** Declared study design. */
    public static final class Design {
        private final Layer layer;
        private final EvidenceClass evidenceClass;
        private final List<String> candidates;
        private final List<Path> instances;
        private final List<Integer> vmCounts;
        private final List<Double> ccrTargets;
        private final List<Double> mtbfRatios;
        private final int replications;
        private final double idleToMaxPowerRatio;
        private final boolean writeEvidence;

        private Design(Builder builder) {
            this.layer = builder.layer;
            this.evidenceClass = builder.evidenceClass;
            this.candidates = Collections.unmodifiableList(new ArrayList<String>(builder.candidates));
            this.instances = Collections.unmodifiableList(new ArrayList<Path>(builder.instances));
            this.vmCounts = Collections.unmodifiableList(new ArrayList<Integer>(builder.vmCounts));
            this.ccrTargets = Collections.unmodifiableList(new ArrayList<Double>(builder.ccrTargets));
            this.mtbfRatios = Collections.unmodifiableList(new ArrayList<Double>(builder.mtbfRatios));
            this.replications = builder.replications;
            this.idleToMaxPowerRatio = builder.idleToMaxPowerRatio;
            this.writeEvidence = builder.writeEvidence;
        }

        public static Builder builder(Layer layer, EvidenceClass evidenceClass) {
            return new Builder(layer, evidenceClass);
        }

        /** Builder of {@link Design}. */
        public static final class Builder {
            private final Layer layer;
            private final EvidenceClass evidenceClass;
            private List<String> candidates;
            private List<Path> instances = new ArrayList<Path>();
            private List<Integer> vmCounts = new ArrayList<Integer>();
            private List<Double> ccrTargets = new ArrayList<Double>();
            private List<Double> mtbfRatios = Collections.<Double>singletonList(null);
            private int replications = 1;
            private double idleToMaxPowerRatio = StandardPlatforms.DEFAULT_IDLE_TO_MAX_POWER_RATIO;
            private boolean writeEvidence = true;

            private Builder(Layer layer, EvidenceClass evidenceClass) {
                if (layer == null || evidenceClass == null) {
                    throw new IllegalArgumentException("Layer and evidence class are required");
                }
                this.layer = layer;
                this.evidenceClass = evidenceClass;
                this.candidates = new ArrayList<String>(layer.getMembers());
                for (int count : StandardPlatforms.STANDARD_VM_COUNTS) {
                    vmCounts.add(Integer.valueOf(count));
                }
                for (double target : CcrCalibrator.STANDARD_CCR_TARGETS) {
                    ccrTargets.add(Double.valueOf(target));
                }
            }

            public Builder candidates(List<String> value) {
                candidates = new ArrayList<String>(value);
                return this;
            }

            public Builder instances(List<Path> value) {
                instances = new ArrayList<Path>(value);
                return this;
            }

            public Builder vmCounts(List<Integer> value) {
                vmCounts = new ArrayList<Integer>(value);
                return this;
            }

            public Builder ccrTargets(List<Double> value) {
                ccrTargets = new ArrayList<Double>(value);
                return this;
            }

            public Builder failures(List<Double> ratios, int replicationCount) {
                mtbfRatios = new ArrayList<Double>(ratios);
                replications = replicationCount;
                return this;
            }

            public Builder idleToMaxPowerRatio(double value) {
                idleToMaxPowerRatio = value;
                return this;
            }

            public Builder writeEvidence(boolean value) {
                writeEvidence = value;
                return this;
            }

            public Design build() {
                if (candidates.isEmpty() || !candidates.contains(layer.getBaseline())) {
                    throw new IllegalArgumentException(
                            "Candidates must include the layer baseline " + layer.getBaseline());
                }
                if (new java.util.HashSet<String>(candidates).size() != candidates.size()) {
                    throw new IllegalArgumentException("Duplicate candidate in " + candidates);
                }
                for (String candidate : candidates) {
                    if (!layer.getMembers().contains(candidate)) {
                        throw new IllegalArgumentException("Candidate " + candidate + " is not in layer " + layer + " "
                                + layer.getMembers() + "; decision layers are never pooled");
                    }
                }
                if (instances.isEmpty() || vmCounts.isEmpty() || ccrTargets.isEmpty() || mtbfRatios.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Instances, VM counts, CCR targets, and failure levels are " + "required");
                }
                if (new java.util.HashSet<Path>(instances).size() != instances.size()) {
                    throw new IllegalArgumentException("Duplicate instance path");
                }
                boolean failures = mtbfRatios.get(0) != null;
                for (Double ratio : mtbfRatios) {
                    if ((ratio != null) != failures) {
                        throw new IllegalArgumentException("Failure levels must be all declared or all absent");
                    }
                }
                if (failures && layer == Layer.STATIC) {
                    throw new IllegalArgumentException("The SHARED_STORAGE static planners require the failure "
                            + "model to be disabled; the failure factor applies to the ONLINE layer only");
                }
                if (replications < 1 || (!failures && replications != 1)) {
                    throw new IllegalArgumentException(
                            "Replications must be 1 without failures and positive with " + "failures: " + replications);
                }
                for (Integer count : vmCounts) {
                    if (count == null || count.intValue() < 2) {
                        throw new IllegalArgumentException("VM counts must be at least 2: " + vmCounts);
                    }
                }
                return new Design(this);
            }
        }
    }

    /** Written study outputs. */
    public static final class Result {
        private final Path json;
        private final Path rowsCsv;
        private final Path comparisonsCsv;
        private final Map<String, Object> header;
        private final List<Map<String, Object>> rows;
        private final List<Map<String, Object>> comparisons;

        Result(
                Path json,
                Path rowsCsv,
                Path comparisonsCsv,
                Map<String, Object> header,
                List<Map<String, Object>> rows,
                List<Map<String, Object>> comparisons) {
            this.json = json;
            this.rowsCsv = rowsCsv;
            this.comparisonsCsv = comparisonsCsv;
            this.header = header;
            this.rows = rows;
            this.comparisons = comparisons;
        }

        public Path getJson() {
            return json;
        }

        public Path getRowsCsv() {
            return rowsCsv;
        }

        public Path getComparisonsCsv() {
            return comparisonsCsv;
        }

        public Map<String, Object> getHeader() {
            return header;
        }

        public List<Map<String, Object>> getRows() {
            return rows;
        }

        public List<Map<String, Object>> getComparisons() {
            return comparisons;
        }
    }
}
