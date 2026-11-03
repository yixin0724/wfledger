package org.workflowsim.experiments.rerun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * D2 stage 6: rerun divergence comparison report (contract section "report format").
 *
 * <p>The machine-readable form is {@code rerun-report.json} (schema
 * {@code workflowsim-rerun-report-v1}); the human-readable form is
 * {@code rerun-report.md}. Fields correspond one-to-one with the contract:
 * {@code verdict}, {@code inputResolution}, {@code coreDivergences},
 * {@code volatileFieldsNoted}, {@code codeIdentityNote}, and the
 * {@code sourceTreeSha256} of both sides; the failure path additionally carries
 * {@code failureReason} and {@code failureDetails} (for example, the candidate
 * path list of INPUT_UNRESOLVED); truncated network evidence also carries a
 * conditional coverage note.</p>
 *
 * <p>This class only carries and serializes data; it does not decide the verdict.</p>
 */
public final class RerunReport {

    /** Report schema identifier. */
    public static final String SCHEMA_V1 = "workflowsim-rerun-report-v1";

    private static final Gson JSON =
            new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private final String schema = SCHEMA_V1;
    private final String verdict;
    private final int exitCode;
    private final String runDirectory;
    private final String outputDirectory;
    private final String failureReason;
    private final List<String> failureDetails;
    private final List<InputResolution> inputResolution;
    private final List<DivergenceEntry> coreDivergences;
    private final List<DivergenceEntry> volatileFieldsNoted;
    private final String codeIdentityNote;
    private final String originalSourceTreeSha256;
    private final String rerunSourceTreeSha256;
    private final String originalAlgorithmContract;
    private final String rerunAlgorithmContract;
    /** Added to JSON only when relevant; serializeNulls must not change old OFF report shape. */
    private final transient String networkEvidenceCoverageNote;

    RerunReport(Builder builder) {
        this.verdict = builder.verdict.name();
        this.exitCode = builder.verdict.getExitCode();
        this.runDirectory = builder.runDirectory;
        this.outputDirectory = builder.outputDirectory;
        this.failureReason = builder.failureReason;
        this.failureDetails = immutable(builder.failureDetails);
        this.inputResolution = immutable(builder.inputResolution);
        this.coreDivergences = immutable(builder.coreDivergences);
        this.volatileFieldsNoted = immutable(builder.volatileFieldsNoted);
        this.codeIdentityNote = builder.codeIdentityNote;
        this.originalSourceTreeSha256 = builder.originalSourceTreeSha256;
        this.rerunSourceTreeSha256 = builder.rerunSourceTreeSha256;
        this.originalAlgorithmContract = builder.originalAlgorithmContract;
        this.rerunAlgorithmContract = builder.rerunAlgorithmContract;
        this.networkEvidenceCoverageNote = builder.networkEvidenceCoverageNote;
    }

    public RerunVerdict getVerdict() {
        return RerunVerdict.valueOf(verdict);
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getRunDirectory() {
        return runDirectory;
    }

    public String getOutputDirectory() {
        return outputDirectory;
    }

    /** Failure reason (null for a successful verdict). */
    public String getFailureReason() {
        return failureReason;
    }

    /** Additional failure details (such as the candidate path list); empty list for a successful verdict. */
    public List<String> getFailureDetails() {
        return failureDetails;
    }

    public List<InputResolution> getInputResolution() {
        return inputResolution;
    }

    public List<DivergenceEntry> getCoreDivergences() {
        return coreDivergences;
    }

    public List<DivergenceEntry> getVolatileFieldsNoted() {
        return volatileFieldsNoted;
    }

    /** Code identity note; null when either side's hash is unknown. */
    public String getCodeIdentityNote() {
        return codeIdentityNote;
    }

    public String getOriginalSourceTreeSha256() {
        return originalSourceTreeSha256;
    }

    public String getRerunSourceTreeSha256() {
        return rerunSourceTreeSha256;
    }

    public String getOriginalAlgorithmContract() {
        return originalAlgorithmContract;
    }

    public String getRerunAlgorithmContract() {
        return rerunAlgorithmContract;
    }

    /** @return conditional warning for truncated network evidence, or null */
    public String getNetworkEvidenceCoverageNote() {
        return networkEvidenceCoverageNote;
    }

    /** Machine-readable JSON; legacy fields keep null and the coverage note is added only when truncated. */
    public String toJson() {
        if (networkEvidenceCoverageNote == null) {
            return JSON.toJson(this);
        }
        JsonObject document = JSON.toJsonTree(this).getAsJsonObject();
        if (networkEvidenceCoverageNote != null) {
            document.addProperty("networkEvidenceCoverageNote", networkEvidenceCoverageNote);
        }
        return JSON.toJson(document);
    }

    /** Human-readable markdown summary. */
    public String toMarkdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Rerun Divergence Report\n\n");
        md.append("- **verdict: `")
                .append(verdict)
                .append("`** (exit code ")
                .append(exitCode)
                .append(")\n");
        md.append("- Original run: `").append(runDirectory).append("`\n");
        md.append("- Output directory: `").append(outputDirectory).append("`\n\n");

        if (networkEvidenceCoverageNote != null) {
            md.append("## Network Evidence Coverage\n\n")
                    .append(networkEvidenceCoverageNote)
                    .append("\n\n");
        }
        md.append("## Code Identity\n\n");
        if (codeIdentityNote == null) {
            md.append("(source-tree hashes are unavailable on both sides; comparison not possible)\n\n");
        } else {
            md.append(codeIdentityNote).append("\n\n");
            md.append("- Original sourceTreeSha256: `")
                    .append(originalSourceTreeSha256)
                    .append("`\n");
            md.append("- Current sourceTreeSha256: `")
                    .append(rerunSourceTreeSha256)
                    .append("`\n\n");
        }
        if (originalAlgorithmContract != null || rerunAlgorithmContract != null) {
            boolean same =
                    originalAlgorithmContract != null && originalAlgorithmContract.equals(rerunAlgorithmContract);
            md.append("- algorithmContract: ")
                    .append(same ? "identical on both sides" : "differs or missing on one side; see JSON report fields")
                    .append("\n\n");
        }

        if (failureReason != null) {
            md.append("## Failure Reason\n\n");
            md.append("```\n").append(failureReason).append("\n```\n\n");
            if (!failureDetails.isEmpty()) {
                md.append("Details:\n\n");
                for (String detail : failureDetails) {
                    md.append("- `").append(detail).append("`\n");
                }
                md.append("\n");
            }
        }

        md.append("## Input Resolution (").append(inputResolution.size()).append(" items)\n\n");
        if (inputResolution.isEmpty()) {
            md.append("(resolution not reached or failed; see failure reason)\n\n");
        } else {
            md.append("| # | Recorded Path | Resolved Path | Tier | sha256 Check |\n");
            md.append("|---|---|---|---|---|\n");
            for (int i = 0; i < inputResolution.size(); i++) {
                InputResolution entry = inputResolution.get(i);
                md.append("| ")
                        .append(i)
                        .append(" | `")
                        .append(entry.recordedPath)
                        .append("` | `")
                        .append(entry.resolvedPath)
                        .append("` | ")
                        .append(entry.tier)
                        .append(" | ")
                        .append(entry.verified ? "passed" : "failed")
                        .append(" |\n");
            }
            md.append("\n");
        }

        md.append("## Core Divergences (").append(coreDivergences.size()).append(")\n\n");
        appendDivergences(md, coreDivergences, "Core quantities are bit-identical; no divergences.");
        md.append("## Volatile Field Differences (recorded only, ")
                .append(volatileFieldsNoted.size())
                .append(")\n\n");
        appendDivergences(md, volatileFieldsNoted, "(whitelisted fields agree on both sides)");
        return md.toString();
    }

    private static void appendDivergences(StringBuilder md, List<DivergenceEntry> entries, String emptyNote) {
        if (entries.isEmpty()) {
            md.append(emptyNote).append("\n\n");
            return;
        }
        for (DivergenceEntry entry : entries) {
            md.append("- `")
                    .append(entry.pointer)
                    .append("`: `")
                    .append(entry.original)
                    .append("` → `")
                    .append(entry.rerun)
                    .append("`\n");
        }
        md.append("\n");
    }

    /** Parse a report from JSON text (consumed by tests and downstream tools). */
    public static JsonObject parseJson(String json) {
        return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
    }

    private static <T> List<T> immutable(List<T> list) {
        return list == null ? Collections.<T>emptyList() : Collections.unmodifiableList(new ArrayList<T>(list));
    }

    /** Resolved path and hash verification result of one input. */
    public static final class InputResolution {

        private final String recordedPath;
        private final String resolvedPath;
        private final int tier;
        private final String sha256;
        private final long sizeBytes;
        private final boolean verified;

        InputResolution(
                String recordedPath, String resolvedPath, int tier, String sha256, long sizeBytes, boolean verified) {
            this.recordedPath = recordedPath;
            this.resolvedPath = resolvedPath;
            this.tier = tier;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.verified = verified;
        }

        public String getRecordedPath() {
            return recordedPath;
        }

        public String getResolvedPath() {
            return resolvedPath;
        }

        /** Hit tier: 1 = study inputs/, 2 = recorded absolute path, 3 = workingDirectory rebasing. */
        public int getTier() {
            return tier;
        }

        public String getSha256() {
            return sha256;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        /** Whether sha256 and sizeBytes match the manifest record. */
        public boolean isVerified() {
            return verified;
        }
    }

    /** One divergence entry: JSON pointer plus both values (over-long values already truncated in the differ). */
    public static final class DivergenceEntry {

        private final String pointer;
        private final String original;
        private final String rerun;

        DivergenceEntry(String pointer, String original, String rerun) {
            this.pointer = pointer;
            this.original = original;
            this.rerun = rerun;
        }

        public String getPointer() {
            return pointer;
        }

        public String getOriginal() {
            return original;
        }

        public String getRerun() {
            return rerun;
        }
    }

    /** Report builder. */
    static final class Builder {

        private RerunVerdict verdict;
        private String runDirectory;
        private String outputDirectory;
        private String failureReason;
        private List<String> failureDetails;
        private List<InputResolution> inputResolution;
        private List<DivergenceEntry> coreDivergences;
        private List<DivergenceEntry> volatileFieldsNoted;
        private String codeIdentityNote;
        private String originalSourceTreeSha256;
        private String rerunSourceTreeSha256;
        private String originalAlgorithmContract;
        private String rerunAlgorithmContract;
        private String networkEvidenceCoverageNote;

        Builder verdict(RerunVerdict value) {
            this.verdict = value;
            return this;
        }

        Builder runDirectory(String value) {
            this.runDirectory = value;
            return this;
        }

        Builder outputDirectory(String value) {
            this.outputDirectory = value;
            return this;
        }

        Builder failure(String reason, List<String> details) {
            this.failureReason = reason;
            this.failureDetails = details;
            return this;
        }

        Builder inputResolution(List<InputResolution> value) {
            this.inputResolution = value;
            return this;
        }

        Builder divergences(EvidenceCoreDiffer.DiffResult diff) {
            this.coreDivergences = entriesOf(diff.getCoreDivergences());
            this.volatileFieldsNoted = entriesOf(diff.getVolatileNoted());
            this.originalSourceTreeSha256 = diff.getOriginalSourceTreeSha256();
            this.rerunSourceTreeSha256 = diff.getRerunSourceTreeSha256();
            this.originalAlgorithmContract = diff.getOriginalAlgorithmContract();
            this.rerunAlgorithmContract = diff.getRerunAlgorithmContract();
            return this;
        }

        Builder networkEvidenceCoverage(
                org.workflowsim.data.TransferTraceSnapshot.Status original,
                org.workflowsim.data.TransferTraceSnapshot.Status rerun) {
            this.networkEvidenceCoverageNote = original == org.workflowsim.data.TransferTraceSnapshot.Status.TRUNCATED
                            || rerun == org.workflowsim.data.TransferTraceSnapshot.Status.TRUNCATED
                    ? "⚠ The network ledger is truncated: even when the verdict is IDENTICAL_CORE, it only"
                            + " means the retained network records agree with the aggregate observations; it"
                            + " does not prove that the complete network service history is identical."
                    : null;
            return this;
        }

        Builder codeIdentityNote(String value) {
            this.codeIdentityNote = value;
            return this;
        }

        Builder originalIdentity(String sourceTreeSha256, String algorithmContract) {
            this.originalSourceTreeSha256 = sourceTreeSha256;
            this.originalAlgorithmContract = algorithmContract;
            return this;
        }

        RerunReport build() {
            if (verdict == null || runDirectory == null || outputDirectory == null) {
                throw new IllegalStateException("Verdict, runDirectory and outputDirectory are required");
            }
            return new RerunReport(this);
        }

        private static List<DivergenceEntry> entriesOf(List<EvidenceCoreDiffer.Divergence> divergences) {
            List<DivergenceEntry> entries = new ArrayList<DivergenceEntry>();
            for (EvidenceCoreDiffer.Divergence divergence : divergences) {
                entries.add(new DivergenceEntry(
                        divergence.getPointer(), divergence.getOriginalValue(), divergence.getRerunValue()));
            }
            return entries;
        }
    }

    /** Converts resolver results into report entries for the executor. */
    static List<InputResolution> inputResolutionsOf(RerunInputs inputs) {
        List<InputResolution> entries = new ArrayList<InputResolution>();
        if (inputs == null) {
            return entries;
        }
        for (RerunInputs.ResolvedInput input : inputs.getResolved()) {
            // A resolver return means sha256/sizeBytes were verified (a mismatch throws).
            entries.add(new InputResolution(
                    input.getRecordedPath(),
                    input.getResolvedPath().toString(),
                    input.getTier(),
                    input.getSha256(),
                    input.getSizeBytes(),
                    true));
        }
        return entries;
    }
}
