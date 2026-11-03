package org.workflowsim.experiments.reference.p7;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.ExperimentEvidenceContext;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/**
 * P7 compatibility reference-matrix executor; routine new experiments use the Workbench.
 *
 * <p>Execute the frozen matrix (2 Epigenomics inputs x 2 platforms x 5 online schedulers = 20
 * simulations) and generate the standardized experiment evidence packages
 * (manifest/metrics/events) plus the index file. Parameter-free execution is supported (it uses
 * the {@code datasets} directory under the project root and the automatically created
 * {@code p7-output/run-<timestamp>} output directory).
 *
 * <p><b>Usage:</b>
 * <ul>
 * <li>No arguments: {@code main()} -- use the default paths (run from the IDE context menu)</li>
 * <li>Explicit paths: {@code main(<dataset-root>, <output-dir>)} -- custom paths</li>
 * </ul>
 *
 * @see P7BaselineMatrix baseline scenario and algorithm definitions
 * @see P7EvidenceIndexValidator validator for the generated index
 */
public final class P7BaselineExecutor {

    static final String BASELINE_INDEX_FILE_NAME = "p7-baseline-index.json";
    static final String BASELINE_INDEX_SCHEMA = "workflowsim-p7-baseline-index-v3";
    static final String BASELINE_INDEX_KIND = "FROZEN_FULL_BASELINE";
    static final String SELECTION_INDEX_FILE_NAME = "p7-reference-selection-index.json";
    static final String SELECTION_INDEX_SCHEMA = "workflowsim-p7-reference-selection-index-v1";
    static final String SELECTION_INDEX_KIND = "PARTIAL_SELECTION_NOT_P7_BASELINE";
    static final String PROTOCOL_REFERENCE = P7ReferenceIdentity.PROTOCOL_FILE_NAME;

    private P7BaselineExecutor() {}

    /**
     * Command-line entry point.
     *
     * <p>Usage: {@code P7BaselineExecutor <absolute-dataset-root> <absolute-empty-output-directory>}.
     * The output directory must be empty; when execution finishes it holds the index and the
     * verifiable evidence artifacts of every run.</p>
     *
     * <p>When run without arguments (for example, Run directly in an IDE), the {@code datasets}
     * directory under the current working directory is used as the dataset root, and an empty
     * timestamped output directory is created under {@code p7-output/}.</p>
     *
     * @param args absolute dataset root and absolute empty output directory; when omitted, the
     *     defaults above apply
     * @throws Exception when the arguments, the input contract, the simulation execution, or the
     *     evidence writing and validation fails
     */
    public static void main(String[] args) throws Exception {
        if (args == null || args.length == 0) {
            // Direct IDE-run support: derive the dataset and output directories from the project root.
            Path projectRoot =
                    Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            Path defaultDatasets = projectRoot.resolve("datasets");
            Path defaultOutput = projectRoot
                    .resolve("p7-output")
                    .resolve("run-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()));
            args = new String[] {defaultDatasets.toString(), defaultOutput.toString()};
            System.out.println("[P7] no arguments given; using default paths:");
            System.out.println("[P7]   datasets root = " + args[0]);
            System.out.println("[P7]   output directory = " + args[1]);
        }
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: P7BaselineExecutor " + "<absolute-dataset-root> <absolute-empty-output-directory>");
        }
        Path datasetRoot = ReferenceDatasetRoot.require(Paths.get(args[0]));
        Path outputDirectory = Paths.get(args[1]);
        Log.disable();
        List<RunRecord> records;
        try {
            records = executeBaseline(datasetRoot, outputDirectory);
        } finally {
            Log.enable();
        }
        System.out.println("P7 baseline completed " + records.size() + " deterministic run(s) in "
                + outputDirectory.toAbsolutePath().normalize());
    }

    /**
     * Execute the complete, frozen P7 baseline matrix.
     *
     * <p>The method always executes the four scenarios and five scheduler combinations defined by
     * the protocol and writes only {@value #BASELINE_INDEX_FILE_NAME}. The validator afterwards
     * confirms that the index contains exactly the whole 4 x 5 matrix, which is what allows the
     * artifact to be called a P7 baseline.</p>
     *
     * @param datasetRoot explicit, absolute {@code datasets} directory
     * @param outputDirectory absolute output directory that must be empty or nonexistent
     * @return immutable list of run records with relative artifact paths and key results
     * @throws Exception when the directory conditions, the scenario inputs, the simulation, or
     *     the artifact validation does not meet the requirements
     */
    public static List<RunRecord> executeBaseline(Path datasetRoot, Path outputDirectory) throws Exception {
        Path normalizedDatasetRoot = ReferenceDatasetRoot.require(datasetRoot);
        return executeInternal(
                normalizedDatasetRoot,
                outputDirectory,
                P7BaselineMatrix.scenarios(normalizedDatasetRoot),
                P7BaselineMatrix.schedulingAlgorithms(),
                BASELINE_INDEX_FILE_NAME,
                BASELINE_INDEX_SCHEMA,
                BASELINE_INDEX_KIND);
    }

    /**
     * Execute a given subset of P7 scenarios and scheduling algorithms to support development and
     * smoke validation.
     *
     * <p>The method writes {@value #SELECTION_INDEX_FILE_NAME} and marks the result with
     * {@value #SELECTION_INDEX_KIND}. Even when the selection happens to contain the current full
     * matrix, callers must not treat that output as a frozen baseline; an official P7 baseline
     * can only be produced through {@link #executeBaseline(Path, Path)} or the command-line entry
     * point.</p>
     *
     * @param datasetRoot explicit, absolute {@code datasets} directory
     * @param outputDirectory absolute output directory that must be empty or nonexistent
     * @param scenarios frozen P7 scenarios to execute
     * @param algorithms maintained scheduling algorithms to evaluate for each scenario
     * @return immutable list of run records with relative artifact paths and key results
     * @throws Exception when the directory conditions, the scenario inputs, the simulation, or
     *     the artifact validation does not meet the requirements
     */
    public static List<RunRecord> executeSelection(
            Path datasetRoot,
            Path outputDirectory,
            List<P7BaselineMatrix.Scenario> scenarios,
            List<Parameters.SchedulingAlgorithm> algorithms)
            throws Exception {
        return executeInternal(
                datasetRoot,
                outputDirectory,
                scenarios,
                algorithms,
                SELECTION_INDEX_FILE_NAME,
                SELECTION_INDEX_SCHEMA,
                SELECTION_INDEX_KIND);
    }

    /**
     * Subset-execution entry point kept for legacy callers.
     *
     * @deprecated use {@link #executeBaseline(Path, Path)} to generate the complete frozen
     *     baseline, or {@link #executeSelection(Path, Path, List, List)} to explicitly produce a
     *     non-baseline selection run.
     */
    @Deprecated
    public static List<RunRecord> execute(
            Path datasetRoot,
            Path outputDirectory,
            List<P7BaselineMatrix.Scenario> scenarios,
            List<Parameters.SchedulingAlgorithm> algorithms)
            throws Exception {
        return executeSelection(datasetRoot, outputDirectory, scenarios, algorithms);
    }

    private static List<RunRecord> executeInternal(
            Path datasetRoot,
            Path outputDirectory,
            List<P7BaselineMatrix.Scenario> scenarios,
            List<Parameters.SchedulingAlgorithm> algorithms,
            String indexFileName,
            String indexSchema,
            String indexKind)
            throws Exception {
        if (datasetRoot == null
                || outputDirectory == null
                || scenarios == null
                || algorithms == null
                || scenarios.isEmpty()
                || algorithms.isEmpty()) {
            throw new IllegalArgumentException(
                    "Dataset root, output directory, scenarios, and algorithms are required");
        }
        Path normalizedDatasetRoot = ReferenceDatasetRoot.require(datasetRoot);
        validateSelection(scenarios, algorithms);
        Path normalizedOutput = prepareEmptyOutputDirectory(outputDirectory);
        ExperimentEvidenceContext evidenceContext = P7ReferenceIdentity.context(normalizedDatasetRoot);
        List<RunRecord> records = new ArrayList<RunRecord>();
        SimulationRunner runner = new SimulationRunner();
        for (P7BaselineMatrix.Scenario scenario : scenarios) {
            PlatformProfile platform = P7BaselineMatrix.baselinePlatform(scenario);
            for (Parameters.SchedulingAlgorithm algorithm : algorithms) {
                SimulationConfig config = P7BaselineMatrix.baselineConfig(scenario, algorithm, normalizedDatasetRoot);
                SimulationReport report = runner.run(config, platform);
                verifyReport(scenario, report);
                Path scenarioDirectory = normalizedOutput.resolve(scenario.getId());
                String runId = algorithm.name().toLowerCase(Locale.ROOT);
                ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                        ExperimentArtifactWriter.write(report, scenarioDirectory, runId, evidenceContext);
                ExperimentArtifactValidator.validate(artifacts.getManifest());
                records.add(RunRecord.from(
                        scenario,
                        algorithm,
                        normalizedOutput.relativize(artifacts.getManifest()),
                        normalizedOutput.relativize(artifacts.getMetrics()),
                        normalizedOutput.relativize(artifacts.getEvents()),
                        report));
            }
        }
        Path index = normalizedOutput.resolve(indexFileName);
        writeIndex(index, records, evidenceContext, indexSchema, indexKind);
        P7EvidenceIndexValidator.validate(index);
        return Collections.unmodifiableList(new ArrayList<RunRecord>(records));
    }

    private static void validateSelection(
            List<P7BaselineMatrix.Scenario> scenarios, List<Parameters.SchedulingAlgorithm> algorithms) {
        Set<String> selectedCells = new HashSet<String>();
        for (P7BaselineMatrix.Scenario scenario : scenarios) {
            if (scenario == null || !P7BaselineMatrix.isBaselineScenarioId(scenario.getId())) {
                throw new IllegalArgumentException("Scenario is outside the P7 reference matrix");
            }
            for (Parameters.SchedulingAlgorithm algorithm : algorithms) {
                if (algorithm == null || !P7BaselineMatrix.isBaselineAlgorithmName(algorithm.name())) {
                    throw new IllegalArgumentException("Scheduling algorithm is outside the P7 reference matrix");
                }
                String cell = P7BaselineMatrix.cellKey(scenario.getId(), algorithm.name());
                if (!selectedCells.add(cell)) {
                    throw new IllegalArgumentException(
                            "P7 selection contains duplicate cell: " + scenario.getId() + "/" + algorithm.name());
                }
            }
        }
    }

    private static Path prepareEmptyOutputDirectory(Path outputDirectory) throws IOException {
        if (!outputDirectory.isAbsolute()) {
            throw new IllegalArgumentException("P7 output directory must be absolute: " + outputDirectory);
        }
        Path normalized = outputDirectory.normalize();
        if (Files.exists(normalized)) {
            if (!Files.isDirectory(normalized)) {
                throw new IllegalArgumentException("P7 output path is not a directory: " + normalized);
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(normalized)) {
                if (entries.iterator().hasNext()) {
                    throw new IllegalArgumentException("P7 output directory must be empty: " + normalized);
                }
            }
        } else {
            Files.createDirectories(normalized);
        }
        return normalized;
    }

    private static void verifyReport(P7BaselineMatrix.Scenario scenario, SimulationReport report) {
        if (report.getInputs().size() != 1 || report.getInputReports().size() != 1) {
            throw new IllegalStateException("P7 baseline scenarios require one parsed input");
        }
        if (!scenario.getExpectedWorkflowSha256()
                .equals(report.getInputs().get(0).getSha256())) {
            throw new IllegalStateException("P7 workflow hash mismatch for " + scenario.getId());
        }
        if (scenario.getExpectedTaskCount() != report.getInputReports().get(0).getTaskCount()) {
            throw new IllegalStateException("P7 task-count mismatch for " + scenario.getId());
        }
        if (report.getFailedJobs() != 0 || report.getSuccessfulJobs() != report.getTotalJobs()) {
            throw new IllegalStateException("P7 failure-disabled run did not complete every job: " + scenario.getId());
        }
    }

    private static void writeIndex(
            Path indexFile,
            List<RunRecord> records,
            ExperimentEvidenceContext evidenceContext,
            String schema,
            String kind)
            throws IOException {
        Map<String, Object> index = new LinkedHashMap<String, Object>();
        index.put("schema", schema);
        index.put("kind", kind);
        index.put("protocol", PROTOCOL_REFERENCE);
        index.put("referenceIdentity", evidenceContext.asMap());
        index.put("rootSeed", P7BaselineMatrix.ROOT_SEED);
        index.put("runtimeReferenceMips", P7BaselineMatrix.RUNTIME_REFERENCE_MIPS);
        index.put("runtimeScale", P7BaselineMatrix.RUNTIME_SCALE);
        index.put("cloudSimMinEventIntervalSeconds", P7BaselineMatrix.CLOUDSIM_MIN_EVENT_INTERVAL_SECONDS);
        index.put("runCount", records.size());
        index.put("runs", records);
        ExperimentArtifactWriter.writeJson(indexFile, index);
    }

    /** Immutable machine-readable run summary that carries the relative manifest path. */
    public static final class RunRecord {

        private final String scenarioId;
        private final String workflowFamily;
        private final int expectedTaskCount;
        private final String platformVariant;
        private final String schedulingAlgorithm;
        private final String workflowSha256;
        private final String manifest;
        private final String metrics;
        private final String events;
        private final double makespan;
        private final int totalJobs;
        private final int successfulJobs;
        private final int failedJobs;

        private RunRecord(
                String scenarioId,
                String workflowFamily,
                int expectedTaskCount,
                String platformVariant,
                String schedulingAlgorithm,
                String workflowSha256,
                String manifest,
                String metrics,
                String events,
                double makespan,
                int totalJobs,
                int successfulJobs,
                int failedJobs) {
            this.scenarioId = scenarioId;
            this.workflowFamily = workflowFamily;
            this.expectedTaskCount = expectedTaskCount;
            this.platformVariant = platformVariant;
            this.schedulingAlgorithm = schedulingAlgorithm;
            this.workflowSha256 = workflowSha256;
            this.manifest = manifest;
            this.metrics = metrics;
            this.events = events;
            this.makespan = makespan;
            this.totalJobs = totalJobs;
            this.successfulJobs = successfulJobs;
            this.failedJobs = failedJobs;
        }

        private static RunRecord from(
                P7BaselineMatrix.Scenario scenario,
                Parameters.SchedulingAlgorithm algorithm,
                Path manifest,
                Path metrics,
                Path events,
                SimulationReport report) {
            return new RunRecord(
                    scenario.getId(),
                    scenario.getWorkflowFamily(),
                    scenario.getExpectedTaskCount(),
                    scenario.getPlatformVariant().name(),
                    algorithm.name(),
                    report.getInputs().get(0).getSha256(),
                    manifest.toString(),
                    metrics.toString(),
                    events.toString(),
                    report.getMakespan(),
                    report.getTotalJobs(),
                    report.getSuccessfulJobs(),
                    report.getFailedJobs());
        }

        public String getScenarioId() {
            return scenarioId;
        }

        public String getWorkflowFamily() {
            return workflowFamily;
        }

        public int getExpectedTaskCount() {
            return expectedTaskCount;
        }

        public String getPlatformVariant() {
            return platformVariant;
        }

        public String getSchedulingAlgorithm() {
            return schedulingAlgorithm;
        }

        public String getWorkflowSha256() {
            return workflowSha256;
        }

        public String getManifest() {
            return manifest;
        }

        public String getMetrics() {
            return metrics;
        }

        public String getEvents() {
            return events;
        }

        public double getMakespan() {
            return makespan;
        }

        public int getTotalJobs() {
            return totalJobs;
        }

        public int getSuccessfulJobs() {
            return successfulJobs;
        }

        public int getFailedJobs() {
            return failedJobs;
        }
    }
}
