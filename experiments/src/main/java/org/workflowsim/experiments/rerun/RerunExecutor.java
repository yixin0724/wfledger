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

/**
 * D2 phase 4: full rerun pipeline -- read the v4 evidence, locate the inputs through three
 * tiers, rebuild the configuration and platform, re-simulate, and write the new artifact
 * trio under {@code <output>/rerun/}.
 *
 * <p>This class only executes and performs no comparison: the stage-5
 * {@code EvidenceCoreDiffer} produces the core-quantity difference verdict. All failure paths
 * keep the existing verdict semantics:
 * {@code EVIDENCE_INVALID}/{@code INPUT_UNRESOLVED}/{@code INPUT_HASH_MISMATCH}/
 * {@code RECONSTRUCTION_REJECTED} are thrown through {@link RerunFailureException}; an execution
 * failure of the simulation itself (such as {@code RetryLimitExceededException}) propagates
 * unchanged and is not disguised as an evidence problem.</p>
 *
 * <p>The output directory must be empty or nonexistent, so that rerun artifacts are not mixed
 * into an existing study directory.</p>
 */
public final class RerunExecutor {

    /** Subdirectory name that always holds the new artifact trio inside the output directory
     * (contract section "Report and Exit Codes"). */
    public static final String RERUN_DIRECTORY_NAME = "rerun";

    /** runId of the rerun artifacts, isomorphic to the historical evidence (result.*). */
    public static final String RERUN_RUN_ID = "result";

    private RerunExecutor() {}

    /**
     * Run the full rerun pipeline starting from a run directory.
     *
     * @param runDirectory historical run directory containing the result.manifest.json artifact
     *        trio
     * @param outputDirectory output root directory (must be empty or nonexistent)
     * @return evidence, input locations, and new artifact-trio paths of this rerun
     * @throws RerunFailureException evidence, input, or rebuild stage failed (carries a verdict)
     * @throws IOException file-system read or write failure
     * @throws SimulationExecutionException simulation execution failure
     */
    public static RerunExecution execute(Path runDirectory, Path outputDirectory)
            throws RerunFailureException, IOException, SimulationExecutionException {
        return execute(RerunEvidenceReader.read(runDirectory), outputDirectory);
    }

    /**
     * Run the rerun pipeline from an evidence object that already passed structural validation
     * (so a caller can reuse evidence it has already read).
     *
     * @param evidence evidence already validated by {@link RerunEvidenceReader}
     * @param outputDirectory output root directory (must be empty or nonexistent)
     * @return evidence, input locations, and new artifact-trio paths of this rerun
     * @throws RerunFailureException input or rebuild stage failed (carries a verdict)
     * @throws IOException file-system read or write failure
     * @throws SimulationExecutionException simulation execution failure
     */
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

    /** An existing output directory must be empty; never silently overwrite or intermix existing
     * artifacts. */
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

    /** Complete result of one successful rerun: the original evidence, the input-location details,
     * and the new artifact-trio paths. */
    public static final class RerunExecution {

        private final RerunEvidence evidence;
        private final RerunInputs inputs;
        private final ExperimentArtifacts artifacts;

        RerunExecution(RerunEvidence evidence, RerunInputs inputs, ExperimentArtifacts artifacts) {
            this.evidence = evidence;
            this.inputs = inputs;
            this.artifacts = artifacts;
        }

        /** Original (historical) evidence snapshot. */
        public RerunEvidence getEvidence() {
            return evidence;
        }

        /** Input-location details (hit tier, path, and hash verification result for each input). */
        public RerunInputs getInputs() {
            return inputs;
        }

        /** Absolute paths of the new artifact trio. */
        public ExperimentArtifacts getArtifacts() {
            return artifacts;
        }

        /** Directory holding the new artifact trio ({@code <output>/rerun/}). */
        public Path getRerunDirectory() {
            return artifacts.getManifest().getParent();
        }
    }
}
