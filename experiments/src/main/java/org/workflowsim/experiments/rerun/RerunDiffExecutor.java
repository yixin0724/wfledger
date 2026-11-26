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

public final class RerunDiffExecutor {

    public static final String REPORT_JSON_NAME = "rerun-report.json";

    public static final String REPORT_MARKDOWN_NAME = "rerun-report.md";

    static final int EXIT_USAGE = 64;

    static final int EXIT_UNEXPECTED = 70;

    private RerunDiffExecutor() {}

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
                    .codeIdentityNote(identityNote(diff))
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
                    EvidenceCoreDiffer.gitCommit(evidence.getManifest()),
                    EvidenceCoreDiffer.gitDirty(evidence.getManifest()),
                    EvidenceCoreDiffer.algorithmContract(evidence.getManifest()));
        }
        return builder.verdict(verdict).failure(reason, details).build();
    }

    static String identityNote(EvidenceCoreDiffer.DiffResult diff) {
        String original = diff.getOriginalGitCommit();
        String rerun = diff.getRerunGitCommit();
        if (original == null || rerun == null) {
            return "Code identity unknown: " + (original == null ? "the original run" : "the current run")
                    + " records no gitCommit, so code-version agreement cannot be established.";
        }
        if (!original.equals(rerun)) {
            return "⚠ Code version differs from the original run: if core quantities diverge, inspect code"
                    + " changes first; if they agree, the evidence is robust to that code evolution.";
        }
        if (Boolean.FALSE.equals(diff.getOriginalGitDirty()) && Boolean.FALSE.equals(diff.getRerunGitDirty())) {
            return "Code version matches the original run (identical gitCommit, clean working trees).";
        }
        return "⚠ Both runs record the same gitCommit, but a working tree was dirty or its state is unknown"
                + " (original gitDirty=" + diff.getOriginalGitDirty() + ", current gitDirty="
                + diff.getRerunGitDirty() + "); uncommitted changes may differ.";
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
