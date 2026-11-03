package org.workflowsim.experiments.rerun;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.failure.RetryLimitExceededException;

/**
 * D2 stage 6: single entry point for the rerun and the difference comparison (contract sections
 * "Command and Input Conventions" and "Execution and Snapshot Boundaries").
 *
 * <pre>
 * mvn -pl :workflowsim-workbench -am compile exec:java \
 *   -Dexec.mainClass=org.workflowsim.experiments.rerun.RerunDiffExecutor \
 *   -Dexec.args="&lt;run-dir&gt; &lt;output-dir&gt;"
 * </pre>
 *
 * <p>The execution flow is chained exactly as the contract specifies: read and validate the
 * three-piece bundle ({@link RerunEvidenceReader}) -> locate the inputs and check their hashes
 * ({@link RerunInputResolver}) -> rebuild the configuration
 * ({@link ManifestConfigRebuilder}) -> rerun through the standard path and persist a new
 * three-piece bundle ({@link RerunExecutor}) -> compare the core quantities
 * ({@link EvidenceCoreDiffer}) -> generate {@code rerun-report.json} + {@code rerun-report.md}.
 * Every step fails fast, and the verdict maps to an independent exit code (see
 * {@link RerunVerdict}), so it can serve directly as a CI gate.</p>
 *
 * <p>Semantics: a failure of the simulation execution phase itself
 * ({@link SimulationExecutionException} or {@link RetryLimitExceededException}) is likewise
 * reported as {@code RECONSTRUCTION_REJECTED} -- the contract defines that conclusion as a
 * legitimate exposure of "historical evidence is incompatible with the current code semantics";
 * a run-phase rejection is one of its forms, and the failure reason states that it occurred in
 * the execution phase.</p>
 */
public final class RerunDiffExecutor {

    /** Machine-readable report file name. */
    public static final String REPORT_JSON_NAME = "rerun-report.json";

    /** Human-readable report file name. */
    public static final String REPORT_MARKDOWN_NAME = "rerun-report.md";

    /** Command-line usage-error exit code (does not overlap any verdict code). */
    static final int EXIT_USAGE = 64;

    /** Unexpected-exception exit code (does not overlap any verdict code). */
    static final int EXIT_UNEXPECTED = 70;

    private RerunDiffExecutor() {}

    /**
     * CLI entry point: verdict exit code 0-5; usage error 64; unexpected exception 70.
     *
     * @param args {@code <run-dir> <output-dir>}
     */
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: RerunDiffExecutor <run-dir> <output-dir>");
            System.exit(EXIT_USAGE);
            return;
        }
        try {
            RerunReport report = execute(Paths.get(args[0]), Paths.get(args[1]));
            System.out.println("verdict: " + report.getVerdict().name() + " (exit code " + report.getExitCode() + ")");
            System.out.println(
                    "report: " + Paths.get(args[1]).toAbsolutePath().normalize().resolve(REPORT_JSON_NAME));
            System.exit(report.getExitCode());
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("rerun refused: " + e.getMessage());
            System.exit(EXIT_USAGE);
        } catch (Exception e) {
            System.err.println("rerun failed unexpectedly: " + e);
            e.printStackTrace();
            System.exit(EXIT_UNEXPECTED);
        }
    }

    /**
     * Execute the full rerun and difference comparison for a single run, and write the report
     * into the output directory.
     *
     * <p>A failure path (verdict other than IDENTICAL/DIVERGED) produces no new {@code rerun/}
     * three-piece bundle, but both reports are still written, carrying the failure reason and
     * details such as candidate paths.</p>
     *
     * @param runDirectory evidence directory of a single run (containing the v4 three-piece
     *     bundle)
     * @param outputDirectory output directory: must not exist or must be empty
     * @return complete report object (identical to what was written to disk)
     * @throws IOException reading evidence or writing the report failed
     * @throws IllegalArgumentException an argument is null
     * @throws IllegalStateException the output directory already exists and is not empty (no
     *     existing file is overwritten)
     */
    public static RerunReport execute(Path runDirectory, Path outputDirectory) throws IOException {
        if (runDirectory == null || outputDirectory == null) {
            throw new IllegalArgumentException("Run directory and output directory are required");
        }
        Path absoluteRun = runDirectory.toAbsolutePath().normalize();
        Path absoluteOutput = outputDirectory.toAbsolutePath().normalize();
        requireEmptyOrNonexistent(absoluteOutput);

        RerunReport.Builder builder = new RerunReport.Builder()
                .runDirectory(absoluteRun.toString())
                .outputDirectory(absoluteOutput.toString());
        RerunEvidence evidence = null;
        RerunReport report;
        try {
            evidence = RerunEvidenceReader.read(absoluteRun);
            RerunExecutor.RerunExecution execution = RerunExecutor.execute(evidence, absoluteOutput);
            RerunEvidence rerunEvidence = RerunEvidenceReader.read(execution.getRerunDirectory());
            EvidenceCoreDiffer.DiffResult diff = EvidenceCoreDiffer.compare(evidence, rerunEvidence);
            report = builder.verdict(diff.isIdenticalCore() ? RerunVerdict.IDENTICAL_CORE : RerunVerdict.DIVERGED)
                    .inputResolution(RerunReport.inputResolutionsOf(execution.getInputs()))
                    .divergences(diff)
                    .networkEvidenceCoverage(
                            evidence.getNetworkCaptureStatus(), rerunEvidence.getNetworkCaptureStatus())
                    .codeIdentityNote(identityNote(diff.getOriginalSourceTreeSha256(), diff.getRerunSourceTreeSha256()))
                    .build();
        } catch (RerunFailureException e) {
            report = failureReport(builder, evidence, e.getVerdict(), e.getMessage(), e.getDetails());
        } catch (SimulationExecutionException | RetryLimitExceededException e) {
            report = failureReport(
                    builder,
                    evidence,
                    RerunVerdict.RECONSTRUCTION_REJECTED,
                    "Simulation execution was rejected by the current code (historical evidence"
                            + " is incompatible with current semantics): "
                            + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    Collections.<String>emptyList());
        } catch (IllegalArgumentException
                | IllegalStateException
                | org.workflowsim.exception.SimulationConfigurationException e) {
            // BAT-V4.1 (2026-10-29 test-engineer adversarial round / DVFS fifth round, red ->
            // green): a run-phase law rejection must stay inside the verdict contract and must
            // not bubble up as a usage-class exit (exit 64, both reports not written at all --
            // that is the crash-class failure the contract section "Classified
            // simulation-execution exceptions ... map to RECONSTRUCTION_REJECTED" exists to
            // eliminate). Actual hit path: during rebuilding, ManifestConfigRebuilder strips
            // the frequency tier from tiered+carbon three-dimensional evidence (D5 forward
            // contract), and the rebuilt "no power + carbon intensity" half-declared platform
            // is rejected by the BAT-C1 half-declaration law with an IllegalArgumentException
            // when the SimulationReport is constructed -- semantically this is a legitimate
            // exposure of "historical evidence incompatible with the current code"
            // (the recorded failure path of DVFS_DIMENSION_DESIGN.md section 2.4) and must land
            // as RECONSTRUCTION_REJECTED with the report still written (exit 4), not as a
            // crash. The same class of exception during the rebuilding phase is already
            // wrapped into RerunFailureException by ManifestConfigRebuilder (the previous
            // catch); such an exception reaching this point unwrapped can only come from the
            // simulation execution or the report-writing phase.
            report = failureReport(
                    builder,
                    evidence,
                    RerunVerdict.RECONSTRUCTION_REJECTED,
                    "Simulation execution was rejected by the current code (historical evidence"
                            + " is incompatible with current semantics): "
                            + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    Collections.<String>emptyList());
        }
        writeReports(absoluteOutput, report);
        return report;
    }

    private static RerunReport failureReport(
            RerunReport.Builder builder,
            RerunEvidence evidence,
            RerunVerdict verdict,
            String reason,
            List<String> details) {
        if (evidence != null) {
            builder.originalIdentity(
                    EvidenceCoreDiffer.sourceTreeSha256(evidence.getManifest()),
                    EvidenceCoreDiffer.algorithmContract(evidence.getManifest()));
        }
        return builder.verdict(verdict).failure(reason, details).build();
    }

    private static String identityNote(String original, String rerun) {
        if (original == null || rerun == null) {
            return null;
        }
        if (original.equals(rerun)) {
            return "Code version matches the original run (identical sourceTreeSha256).";
        }
        return "⚠ Code version differs from the original run: if core quantities diverge, inspect code"
                + " changes first; if they agree, the evidence is robust to that code evolution.";
    }

    private static void requireEmptyOrNonexistent(Path output) throws IOException {
        if (!Files.exists(output)) {
            return;
        }
        if (!Files.isDirectory(output)) {
            throw new IllegalStateException("Output path exists but is not a directory: " + output);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(output)) {
            if (stream.iterator().hasNext()) {
                throw new IllegalStateException("Output directory must be empty or nonexistent: " + output);
            }
        }
    }

    private static void writeReports(Path outputDirectory, RerunReport report) throws IOException {
        Files.createDirectories(outputDirectory);
        Files.write(outputDirectory.resolve(REPORT_JSON_NAME), report.toJson().getBytes(StandardCharsets.UTF_8));
        Files.write(
                outputDirectory.resolve(REPORT_MARKDOWN_NAME),
                report.toMarkdown().getBytes(StandardCharsets.UTF_8));
    }
}
