package org.workflowsim.experiment;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ExperimentCampaignArtifactWriter {

    public static final String INDEX_FILE_NAME = "experiment-campaign-index.json";

    public static final String SUMMARY_FILE_NAME = "campaign-summary.json";

    private ExperimentCampaignArtifactWriter() {}

    public static CampaignArtifacts write(ExperimentCampaignResult result, Path outputDirectory) throws IOException {
        return write(result, outputDirectory, EvidenceOutput.FULL);
    }

    /**
     * Writes campaign outputs at the requested evidence depth.
     *
     * <p>Under {@link EvidenceOutput#SUMMARY} only {@code campaign-summary.json} is written: the plan, the
     * run count and the campaign summary, without per-run bundles.
     *
     * @param result campaign result
     * @param outputDirectory empty or absent directory
     * @param evidenceOutput evidence depth
     * @return written artifacts; the index is null when only the summary is written
     * @throws IOException when a file cannot be written
     */
    public static CampaignArtifacts write(
            ExperimentCampaignResult result, Path outputDirectory, EvidenceOutput evidenceOutput) throws IOException {
        if (result == null || outputDirectory == null || evidenceOutput == null) {
            throw new IllegalArgumentException("Campaign result and output directory are required");
        }
        Path output = prepareEmptyOutputDirectory(outputDirectory);
        if (!evidenceOutput.requiresPerRunArtifacts()) {
            Map<String, Object> summary = new LinkedHashMap<String, Object>();
            summary.put("schema", ExperimentCampaignValidator.SCHEMA);
            summary.put("plan", plan(result.getPlan()));
            summary.put("runCount", result.getRuns().size());
            summary.put("summary", result.getSummary());
            Path file = output.resolve(SUMMARY_FILE_NAME);
            ExperimentArtifactWriter.writeJson(file, summary);
            return new CampaignArtifacts(null, new ArrayList<RunArtifact>());
        }
        List<RunArtifact> records = new ArrayList<RunArtifact>();
        for (ExperimentCampaignResult.Run run : result.getRuns()) {
            String runId = String.format(Locale.ROOT, "replication-%04d", run.getReplicationIndex());
            Path cellDirectory = output.resolve(run.getCell().getId());
            ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                    ExperimentArtifactWriter.write(run.getReport(), cellDirectory, runId);
            records.add(RunArtifact.from(output, run, artifacts));
        }
        Path index = output.resolve(INDEX_FILE_NAME);
        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("schema", ExperimentCampaignValidator.SCHEMA);
        document.put("plan", plan(result.getPlan()));
        document.put("runCount", records.size());
        document.put("runs", records);
        document.put("summary", result.getSummary());
        ExperimentArtifactWriter.writeJson(index, document);
        ExperimentCampaignValidator.validate(index);
        return new CampaignArtifacts(index, records);
    }

    private static Path prepareEmptyOutputDirectory(Path outputDirectory) throws IOException {
        Path normalized = outputDirectory.toAbsolutePath().normalize();
        if (Files.exists(normalized)) {
            if (!Files.isDirectory(normalized)) {
                throw new IllegalArgumentException("Campaign output path is not a directory: " + normalized);
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(normalized)) {
                if (entries.iterator().hasNext()) {
                    throw new IllegalArgumentException("Campaign output directory must be empty: " + normalized);
                }
            }
        } else {
            Files.createDirectories(normalized);
        }
        return normalized;
    }

    private static Map<String, Object> plan(ExperimentPlan plan) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("schema", ExperimentPlan.SCHEMA);
        values.put("id", plan.getId());
        values.put("runCount", plan.getRunCount());
        List<Map<String, Object>> cells = new ArrayList<Map<String, Object>>();
        for (ExperimentPlan.Cell cell : plan.getCells()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", cell.getId());
            item.put("comparisonGroup", cell.getComparisonGroup());
            item.put("candidateId", cell.getCandidateId());
            item.put("baseline", cell.isBaseline());
            item.put(
                    "randomizationDesign",
                    cell.getSeedPlan().getRandomizationDesign().name());
            item.put("derivationRootSeed", cell.getSeedPlan().getDerivationRootSeed());
            item.put("rootSeeds", cell.getSeedPlan().getSeeds());
            item.put("tags", cell.getTags());
            cells.add(item);
        }
        values.put("cells", cells);
        return values;
    }

    public static final class CampaignArtifacts {
        private final Path index;
        private final List<RunArtifact> runs;

        private CampaignArtifacts(Path index, List<RunArtifact> runs) {
            this.index = index;
            this.runs = Collections.unmodifiableList(new ArrayList<RunArtifact>(runs));
        }

        public Path getIndex() {
            return index;
        }

        public List<RunArtifact> getRuns() {
            return runs;
        }
    }

    public static final class RunArtifact {
        private final String cellId;
        private final String comparisonGroup;
        private final String candidateId;
        private final boolean baseline;
        private final int replicationIndex;
        private final long seed;
        private final String manifest;
        private final String metrics;
        private final String events;
        private final double makespanSeconds;
        private final int totalJobs;
        private final int successfulJobs;
        private final int failedJobs;
        private final WorkflowProfile workflowProfile;

        private RunArtifact(
                String cellId,
                String comparisonGroup,
                String candidateId,
                boolean baseline,
                int replicationIndex,
                long seed,
                String manifest,
                String metrics,
                String events,
                double makespanSeconds,
                int totalJobs,
                int successfulJobs,
                int failedJobs,
                WorkflowProfile workflowProfile) {
            this.cellId = cellId;
            this.comparisonGroup = comparisonGroup;
            this.candidateId = candidateId;
            this.baseline = baseline;
            this.replicationIndex = replicationIndex;
            this.seed = seed;
            this.manifest = manifest;
            this.metrics = metrics;
            this.events = events;
            this.makespanSeconds = makespanSeconds;
            this.totalJobs = totalJobs;
            this.successfulJobs = successfulJobs;
            this.failedJobs = failedJobs;
            this.workflowProfile = workflowProfile;
        }

        private static RunArtifact from(
                Path output, ExperimentCampaignResult.Run run, ExperimentArtifactWriter.ExperimentArtifacts artifacts) {
            SimulationReport report = run.getReport();
            return new RunArtifact(
                    run.getCell().getId(),
                    run.getCell().getComparisonGroup(),
                    run.getCell().getCandidateId(),
                    run.getCell().isBaseline(),
                    run.getReplicationIndex(),
                    run.getSeed(),
                    relative(output, artifacts.getManifest()),
                    relative(output, artifacts.getMetrics()),
                    relative(output, artifacts.getEvents()),
                    report.getMakespan(),
                    report.getTotalJobs(),
                    report.getSuccessfulJobs(),
                    report.getFailedJobs(),
                    report.getWorkflowProfile());
        }

        private static String relative(Path output, Path artifact) {
            return output.relativize(artifact.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
        }

        public String getCellId() {
            return cellId;
        }

        public String getComparisonGroup() {
            return comparisonGroup;
        }

        public String getCandidateId() {
            return candidateId;
        }

        public boolean isBaseline() {
            return baseline;
        }

        public int getReplicationIndex() {
            return replicationIndex;
        }

        public long getSeed() {
            return seed;
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

        public double getMakespanSeconds() {
            return makespanSeconds;
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

        public WorkflowProfile getWorkflowProfile() {
            return workflowProfile;
        }
    }
}
