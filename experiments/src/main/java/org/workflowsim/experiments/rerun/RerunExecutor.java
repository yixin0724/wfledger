package org.workflowsim.experiments.rerun;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.ExperimentArtifactWriter.ExperimentArtifacts;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;

public final class RerunExecutor {

    public static final String RERUN_DIRECTORY_NAME = "rerun";

    public static final String RERUN_RUN_ID = "result";

    private RerunExecutor() {}

    public static RerunExecution execute(Path runDirectory, Path outputDirectory)
            throws RerunFailureException, IOException, SimulationExecutionException {
        return execute(RerunEvidenceReader.read(runDirectory), outputDirectory);
    }

    public static RerunExecution execute(RerunEvidence evidence, Path outputDirectory)
            throws RerunFailureException, IOException, SimulationExecutionException {
        if (evidence == null || outputDirectory == null) {
            throw new IllegalArgumentException("Evidence and output directory are required");
        }
        Path absoluteOutput = outputDirectory.toAbsolutePath().normalize();
        refuseNonEmptyDirectory(absoluteOutput);
        RerunInputs inputs = RerunInputResolver.resolve(evidence);
        ManifestConfigRebuilder.RebuiltConfiguration rebuilt = ManifestConfigRebuilder.rebuild(evidence, inputs);
        SimulationReport report = new SimulationRunner().run(rebuilt.getConfig(), rebuilt.getPlatform());
        ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, absoluteOutput.resolve(RERUN_DIRECTORY_NAME), RERUN_RUN_ID);
        return new RerunExecution(evidence, inputs, artifacts);
    }

    private static void refuseNonEmptyDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("Output path exists but is not a directory: " + directory);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            if (stream.iterator().hasNext()) {
                throw new IllegalStateException("Output directory must be empty or nonexistent: " + directory);
            }
        }
    }

    public static final class RerunExecution {

        private final RerunEvidence evidence;
        private final RerunInputs inputs;
        private final ExperimentArtifacts artifacts;

        RerunExecution(RerunEvidence evidence, RerunInputs inputs, ExperimentArtifacts artifacts) {
            this.evidence = evidence;
            this.inputs = inputs;
            this.artifacts = artifacts;
        }

        public RerunEvidence getEvidence() {
            return evidence;
        }

        public RerunInputs getInputs() {
            return inputs;
        }

        public ExperimentArtifacts getArtifacts() {
            return artifacts;
        }

        public Path getRerunDirectory() {
            return artifacts.getManifest().getParent();
        }
    }
}
