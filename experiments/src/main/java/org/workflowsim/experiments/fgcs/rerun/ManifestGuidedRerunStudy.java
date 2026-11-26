package org.workflowsim.experiments.fgcs.rerun;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.experiments.fgcs.FgcPlatforms;
import org.workflowsim.experiments.fgcs.FgcStudySupport;
import org.workflowsim.experiments.rerun.RerunDiffExecutor;
import org.workflowsim.experiments.rerun.RerunReport;
import org.workflowsim.experiments.rerun.RerunVerdict;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** FGCS RQ1 protocol for manifest-guided reconstruction, re-simulation, and exact core diffing. */
public final class ManifestGuidedRerunStudy {
    public static final String PROTOCOL = "FGCS_MANIFEST_GUIDED_RERUN_V1";
    public static final String RESULTS_SCHEMA = "fgcs-manifest-guided-rerun-results-v1";
    public static final String RESULTS_NAME = "results.json";

    private static final String CONTRACT = "docs/experiments/RERUN_DIFF_CONTRACT.md";
    private static final String V2_SCOPE = "manifest v4; one four-task Pegasus DAX; two pinned SPACE_SHARED VMs; "
            + "COHERENT_FILE_DATAFLOW_V2; complete FILE_LIFECYCLE_V2; exact non-exempt JSON core";
    private static final String V3_SCOPE = "manifest v4; one four-task Pegasus DAX; two pinned SPACE_SHARED VMs; "
            + "COHERENT_STORAGE_DATAFLOW_V3; complete FILE_STORAGE_LIFECYCLE_V3; exact non-exempt JSON core";

    private ManifestGuidedRerunStudy() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: ManifestGuidedRerunStudy [output-directory]");
        }
        Path output = args.length == 0 ? Paths.get("paper/tmp/fgcs-round2/rerun") : Paths.get(args[0]);
        JsonObject results = run(output);
        JsonObject summary = results.getAsJsonObject("summary");
        System.out.println("FGCS manifest-guided rerun cases=" + summary.get("caseCount")
                + " identicalCore=" + summary.get("actualIdenticalCore")
                + " unexpectedOutcomes=" + summary.get("unexpectedOutcomes")
                + " output=" + output.toAbsolutePath().normalize().resolve(RESULTS_NAME));
        if (summary.get("unexpectedOutcomes").getAsInt() != 0) {
            throw new IllegalStateException(
                    "Manifest-guided rerun outcomes diverged from the contract-derived protocol; inspect results.json");
        }
    }

    public static JsonObject run(Path outputDirectory) throws Exception {
        Path output = outputDirectory.toAbsolutePath().normalize();
        requireEmptyOrNonexistent(output);
        Files.createDirectories(output);
        Path evidence = output.resolve("evidence");
        List<JsonObject> cases = new ArrayList<JsonObject>();
        boolean loggingDisabled = Log.isDisabled();
        Log.disable();
        try {
            Path workflow = writeChain(evidence.resolve("inputs/chain.dax"));
            Path v2 = generate(workflow, evidence.resolve("genuine-v2"), false);
            Path v3 = generate(workflow, evidence.resolve("genuine-v3"), true);

            cases.add(executeCase(
                    new StudyCase(
                            "genuine-v2-control",
                            true,
                            "V2",
                            v2,
                            "None: direct SimulationRunner/ExperimentArtifactWriter evidence.",
                            "Unmodified writer output; input identity is resolved from the recorded absolute path.",
                            V2_SCOPE,
                            expect(
                                            RerunVerdict.IDENTICAL_CORE,
                                            "Execution and Snapshot Boundaries plus Comparison Rules: a valid v4"
                                                    + " bundle rebuilt and re-simulated by the same code must agree in"
                                                    + " every non-exempt JSON value; wall-clock and environment"
                                                    + " differences alone cannot make the control diverge.")
                                    .requireInputTier(2)),
                    output));
            cases.add(executeCase(
                    new StudyCase(
                            "genuine-v3-control",
                            true,
                            "V3",
                            v3,
                            "None: direct SimulationRunner/ExperimentArtifactWriter evidence.",
                            "Unmodified writer output; input identity is resolved from the recorded absolute path.",
                            V3_SCOPE,
                            expect(
                                            RerunVerdict.IDENTICAL_CORE,
                                            "Execution and Snapshot Boundaries plus Network, Lifecycle, and Online"
                                                    + " Actions: the V3 model, sourceStorage, full storage lifecycle,"
                                                    + " results, metrics, and events are rebuilt or compared; the"
                                                    + " unmodified control must be IDENTICAL_CORE.")
                                    .requireInputTier(2)),
                    output));

            Path relocationStudy = evidence.resolve("cases/relocation-study");
            RerunStudyBundle relocated = RerunStudyBundle.copy(v2, relocationStudy.resolve("runs/original"));
            Path relocatedInput = relocationStudy.resolve("inputs/chain.dax");
            Files.createDirectories(relocatedInput.getParent());
            Files.copy(workflow, relocatedInput);
            cases.add(executeCase(
                    new StudyCase(
                            "relocated-v2-input-path",
                            false,
                            "V2",
                            relocated.directory(),
                            "Copy the real V2 bundle beneath <study>/runs and the byte-identical DAX beneath "
                                    + "<study>/inputs, without changing its recorded identity.",
                            "Tier-1 study-input relocation; referenced artifact bytes are unchanged.",
                            V2_SCOPE + "; tier-1 input relocation only",
                            expect(
                                            RerunVerdict.IDENTICAL_CORE,
                                            "Input Resolution Rules and Fixed Volatile-Field Whitelist: tier 1 must"
                                                    + " verify the same SHA-256 and byte count before use. Only"
                                                    + " the three workflow path-text pointers are exempt; input hashes,"
                                                    + " sizes, array lengths, and all simulation core quantities remain"
                                                    + " exact.")
                                    .requireInputTier(1)
                                    .requireVolatilePointers(
                                            "/configuration/workflowPaths/0",
                                            "/inputs/0/path",
                                            "/result/workflowOutcomes/0/path")),
                    output));

            RerunStudyBundle volatileMetadata = RerunStudyBundle.copy(v3, evidence.resolve("cases/volatile-v3"));
            volatileMetadata.manifest().getAsJsonObject("runtime").addProperty("osName", "FGCS_VOLATILE_OS_NAME");
            volatileMetadata
                    .manifest()
                    .getAsJsonObject("provenance")
                    .getAsJsonObject("core")
                    .addProperty("codeSource", "file:/volatile-only/code/");
            volatileMetadata.writeManifest();
            cases.add(executeCase(
                    new StudyCase(
                            "volatile-v3-manifest-metadata",
                            false,
                            "V3",
                            volatileMetadata.directory(),
                            "Change only runtime.osName and provenance.core.codeSource in the original manifest.",
                            "Manifest-only metadata mutation; no referenced artifact or input identity changes.",
                            V3_SCOPE + "; root runtime/provenance volatility only",
                            expect(
                                            RerunVerdict.IDENTICAL_CORE,
                                            "Fixed Volatile-Field Whitelist: all subtrees of root /provenance and"
                                                    + " /runtime are recorded but exempt. The exemption does not extend"
                                                    + " into sidecar runtime/provenance namespaces, which stay exact"
                                                    + " and exactly compared here.")
                                    .requireInputTier(2)
                                    .requireVolatilePointers("/runtime/osName", "/provenance/core/codeSource")),
                    output));

            RerunStudyBundle scaled = RerunStudyBundle.copy(v2, evidence.resolve("cases/semantic-runtime-scale"));
            double originalScale = scaled.manifest()
                    .getAsJsonObject("configuration")
                    .get("runtimeScale")
                    .getAsDouble();
            require(
                    Double.compare(originalScale, 1.0) == 0,
                    "The hand-derived baseline must declare unit runtime scale");
            scaled.manifest().getAsJsonObject("configuration").addProperty("runtimeScale", 2.0);
            scaled.writeManifest();
            cases.add(executeCase(
                    new StudyCase(
                            "semantic-v2-runtime-scale",
                            false,
                            "V2",
                            scaled.directory(),
                            "Double configuration.runtimeScale from the baseline's declared 1.0 to 2.0 while retaining "
                                    + "the original unit-scale result, metrics, events, and lifecycle.",
                            "Manifest-only semantic condition change; no referenced artifact bytes are changed.",
                            V2_SCOPE + "; nonzero DAX runtimes 10/20/30/40 s; runtimeReferenceMips=1000",
                            expect(
                                            RerunVerdict.DIVERGED,
                                            "Configuration Rebuilding Rules forward runtimeScale. Under the recorded"
                                                    + " parser and WORK_CONSERVING_TASK_EXECUTION_V3 semantics, every"
                                                    + " nonzero effective Task length is runtimeSeconds ×"
                                                    + " runtimeReferenceMips × runtimeScale. Doubling the scale"
                                                    + " changes effectiveExecutionLengthMi and downstream timing core"
                                                    + " fields; exact JSON comparison uses no epsilon and must report"
                                                    + " DIVERGED.")
                                    .requireInputTier(2)
                                    .requireCorePointers("/result/tasks/0/effectiveExecutionLengthMi")),
                    output));

            RerunStudyBundle invalid = RerunStudyBundle.copy(v2, evidence.resolve("cases/evidence-invalid-makespan"));
            invalidateMakespan(invalid);
            cases.add(executeCase(
                    new StudyCase(
                            "evidence-invalid-v2-makespan",
                            false,
                            "V2",
                            invalid.directory(),
                            "Advance result.simulationEndSeconds and the sole SIMULATION_TERMINATED event by 0.5 s"
                                    + " while retaining the old makespanSeconds in the manifest and metrics sidecar.",
                            "Every declared artifact SHA-256 and size is recomputed after the semantic corruption.",
                            V2_SCOPE
                                    + "; manifest-v4 metric-recomputation validation gate; no successful rerun"
                                    + " expected",
                            expect(
                                            RerunVerdict.EVIDENCE_INVALID,
                                            "Report and Exit Codes (AUD-F05): v4 validation recomputes makespanSeconds"
                                                    + " from the manifest's own result records. A stale metric"
                                                    + " must be rejected before re-simulation; rehashing the corrupted"
                                                    + " artifacts cannot convert it to DIVERGED.")
                                    .requireFailureReason("makespanSeconds")),
                    output));

            RerunStudyBundle mismatch = RerunStudyBundle.copy(v2, evidence.resolve("cases/input-hash-mismatch"));
            Path corruptedInput = evidence.resolve("inputs/hash-mismatch-chain.dax");
            byte[] corruptedBytes = Files.readAllBytes(workflow);
            corruptedBytes[corruptedBytes.length - 1] ^= 0x01;
            Files.write(corruptedInput, corruptedBytes);
            require(
                    Files.size(workflow) == Files.size(corruptedInput),
                    "The hash-mismatch probe must preserve byte count");
            rewriteInputPath(mismatch, corruptedInput);
            JsonObject mismatchResult = executeCase(
                    new StudyCase(
                            "input-v2-hash-mismatch",
                            false,
                            "V2",
                            mismatch.directory(),
                            "Align all three recorded path fields to a same-size DAX whose final byte is flipped,"
                                    + " while retaining the original inputs[0] SHA-256 and size identity.",
                            "The candidate is a regular file with equal byte count but a different SHA-256; no"
                                    + " fallback candidate may be used to hide the mismatch.",
                            V2_SCOPE + "; first-located-candidate identity verification; no re-simulation expected",
                            expect(
                                            RerunVerdict.INPUT_HASH_MISMATCH,
                                            "Input Resolution Rules: as soon as a regular file is found, its SHA-256"
                                                    + " and byte count are checked immediately. A mismatch is"
                                                    + " INPUT_HASH_MISMATCH and probing stops.")
                                    .requireFailureReason("sha256/sizeBytes mismatch")
                                    .requireFailureDetails("expected sha256=", "actual   sha256=")),
                    output);
            mismatchResult
                    .getAsJsonObject("evidenceHashes")
                    .add("mutatedInputCandidate", RerunStudyBundle.fileHash(corruptedInput));
            cases.add(mismatchResult);

            RerunStudyBundle unresolved = RerunStudyBundle.copy(v3, evidence.resolve("cases/input-unresolved"));
            Path missingInput = evidence.resolve("missing/unresolved-v3-chain.dax");
            rewriteInputPath(unresolved, missingInput);
            cases.add(executeCase(
                    new StudyCase(
                            "input-v3-unresolved",
                            false,
                            "V3",
                            unresolved.directory(),
                            "Align all three recorded path fields to a unique nonexistent absolute DAX path while"
                                    + " retaining the original input identity.",
                            "The run is outside a <study>/runs layout, and the absolute missing path is outside the"
                                    + " recorded working-directory prefix, so only the direct-path candidate is"
                                    + " applicable.",
                            V3_SCOPE + "; input candidate exhaustion; no re-simulation expected",
                            expect(
                                            RerunVerdict.INPUT_UNRESOLVED,
                                            "Input Resolution Rules: when every applicable candidate is absent, the"
                                                    + " verdict is INPUT_UNRESOLVED and the report must preserve the"
                                                    + " candidates that were tried.")
                                    .requireFailureReason("Input could not be located")
                                    .requireFailureDetails("tier2:")),
                    output));

            RerunStudyBundle unsupported =
                    RerunStudyBundle.copy(v3, evidence.resolve("cases/unsupported-vm-scheduler"));
            unsupported
                    .manifest()
                    .getAsJsonObject("platform")
                    .getAsJsonArray("vms")
                    .get(0)
                    .getAsJsonObject()
                    .addProperty("schedulerMode", "NOT_A_SCHEDULER_MODE");
            unsupported.writeManifest();
            cases.add(executeCase(
                    new StudyCase(
                            "reconstruction-v3-unsupported-vm-scheduler",
                            false,
                            "V3",
                            unsupported.directory(),
                            "Replace platform.vms[0].schedulerMode with the unsupported enum label"
                                    + " NOT_A_SCHEDULER_MODE.",
                            "Manifest-only unsupported configuration; no VM scheduler substitution or fallback is"
                                    + " allowed.",
                            V3_SCOPE + "; platform rebuilding gate; no re-simulation expected",
                            expect(
                                            RerunVerdict.RECONSTRUCTION_REJECTED,
                                            "Configuration Rebuilding Rules: when a recorded parameter or enum is not"
                                                    + " accepted by code, rebuilding is RECONSTRUCTION_REJECTED."
                                                    + " The VM scheduler mode must never be swapped silently for a"
                                                    + " supported value.")
                                    .requireFailureReason("NOT_A_SCHEDULER_MODE")),
                    output));
        } finally {
            Log.setDisabled(loggingDisabled);
        }

        JsonObject results = resultDocument(cases);
        Path resultPath = output.resolve(RESULTS_NAME);
        Files.write(
                resultPath,
                (new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(results) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
        return results;
    }

    private static Path writeChain(Path output) throws IOException {
        StringBuilder dax = new StringBuilder("<adag version=\"3.3\" count=\"4\">\n");
        String[] runtimes = {"10", "20", "30", "40"};
        for (int task = 1; task <= 4; task++) {
            dax.append("<job id=\"j")
                    .append(task)
                    .append("\" name=\"task")
                    .append(task)
                    .append("\" runtime=\"")
                    .append(runtimes[task - 1])
                    .append("\">");
            dax.append("<uses name=\"f")
                    .append(task)
                    .append("\" link=\"output\" size=\"")
                    .append(task * 1000000)
                    .append("\"/>");
            if (task > 1) {
                dax.append("<uses name=\"f")
                        .append(task - 1)
                        .append("\" link=\"input\" size=\"")
                        .append((task - 1) * 1000000)
                        .append("\"/>");
            }
            dax.append("</job>\n");
        }
        for (int task = 2; task <= 4; task++) {
            dax.append("<child ref=\"j")
                    .append(task)
                    .append("\"><parent ref=\"j")
                    .append(task - 1)
                    .append("\"/></child>\n");
        }
        dax.append("</adag>\n");
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Files.write(output, dax.toString().getBytes(StandardCharsets.UTF_8));
        return output.toAbsolutePath().normalize();
    }

    private static Path generate(Path workflow, Path output, boolean storage) throws Exception {
        SimulationConfig config = FgcStudySupport.base(workflow.toString(), 2)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(
                        storage
                                ? DataMovementModel.coherentStorageDataflowV3()
                                : DataMovementModel.coherentFileDataflowV2())
                .networkEvidence(
                        storage
                                ? NetworkEvidenceConfig.storageLifecycleV3(10000)
                                : NetworkEvidenceConfig.fileLifecycleV2(10000))
                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        SimulationReport report =
                new SimulationRunner().run(config, storage ? FgcPlatforms.storage(2, 1) : FgcPlatforms.flat(2, 1));
        if (!report.getMetrics().isAllLogicalTasksCompletedSuccessfully()) {
            throw new IllegalStateException("The genuine rerun-study baseline did not complete every logical Task");
        }
        ExperimentArtifactWriter.write(report, output, "result");
        return output.toAbsolutePath().normalize();
    }

    private static void invalidateMakespan(RerunStudyBundle bundle) throws IOException {
        JsonObject result = bundle.manifest().getAsJsonObject("result");
        double corruptedEnd = result.get("simulationEndSeconds").getAsDouble() + 0.5;
        result.addProperty("simulationEndSeconds", corruptedEnd);
        List<JsonObject> events = bundle.readMainEvents();
        int terminals = 0;
        for (JsonObject event : events) {
            if ("SIMULATION_TERMINATED".equals(event.get("type").getAsString())) {
                event.addProperty("simulationTime", corruptedEnd);
                event.getAsJsonObject("attributes").addProperty("simulationEndSeconds", corruptedEnd);
                terminals++;
            }
        }
        require(terminals == 1, "The genuine V2 run must contain exactly one terminal event");
        bundle.writeMainEvents(events);
        bundle.commitArtifactDigests();
    }

    private static void rewriteInputPath(RerunStudyBundle bundle, Path path) throws IOException {
        String value = path.toAbsolutePath().normalize().toString();
        bundle.manifest().getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("path", value);
        bundle.manifest()
                .getAsJsonObject("configuration")
                .getAsJsonArray("workflowPaths")
                .set(0, new JsonPrimitive(value));
        bundle.manifest()
                .getAsJsonObject("result")
                .getAsJsonArray("workflowOutcomes")
                .get(0)
                .getAsJsonObject()
                .addProperty("path", value);
        bundle.writeManifest();
    }

    private static JsonObject executeCase(StudyCase testCase, Path outputRoot) throws Exception {
        Path caseOutput = outputRoot.resolve("executor").resolve(testCase.id);
        JsonObject originalBefore = RerunStudyBundle.evidenceHashes(testCase.runDirectory);
        RerunReport report = RerunDiffExecutor.execute(testCase.runDirectory, caseOutput);
        JsonObject originalAfter = RerunStudyBundle.evidenceHashes(testCase.runDirectory);
        boolean originalUnchanged = originalBefore.equals(originalAfter);

        JsonObject value = new JsonObject();
        value.addProperty("caseId", testCase.id);
        value.addProperty("kind", testCase.genuineControl ? "GENUINE_CONTROL" : "PROTOCOL_PROBE");
        value.addProperty("artifactFamily", testCase.family);
        value.addProperty(
                "method", "manifest-guided reconstruction + re-simulation + exact core diff (not event replay)");
        value.addProperty("executor", RerunDiffExecutor.class.getName());
        value.addProperty(
                "originalRunDirectory",
                testCase.runDirectory.toAbsolutePath().normalize().toString());
        value.addProperty(
                "executorOutputDirectory",
                caseOutput.toAbsolutePath().normalize().toString());
        value.addProperty("mutation", testCase.mutation);
        value.addProperty("mutationIntegrity", testCase.mutationIntegrity);
        value.addProperty("contract", CONTRACT);
        value.addProperty("contractRationale", testCase.expectation.rationale);
        value.addProperty("applicableScope", testCase.scope);
        value.addProperty("expectedVerdict", testCase.expectation.verdict.name());
        value.addProperty("actualVerdict", report.getVerdict().name());
        value.addProperty("actualExitCode", report.getExitCode());
        value.addProperty("verdictMatched", testCase.expectation.verdict == report.getVerdict());
        value.addProperty("originalEvidenceUnchanged", originalUnchanged);
        value.addProperty("failureReason", report.getFailureReason());
        value.add("failureDetails", strings(report.getFailureDetails()));
        value.add("inputResolution", inputResolutions(report));
        value.add("coreDivergences", divergences(report.getCoreDivergences()));
        value.add("volatileFieldsNoted", divergences(report.getVolatileFieldsNoted()));
        value.addProperty("codeIdentityNote", report.getCodeIdentityNote());
        value.addProperty("originalSourceTreeSha256", report.getOriginalSourceTreeSha256());
        value.addProperty("rerunSourceTreeSha256", report.getRerunSourceTreeSha256());
        value.addProperty("originalAlgorithmContract", report.getOriginalAlgorithmContract());
        value.addProperty("rerunAlgorithmContract", report.getRerunAlgorithmContract());
        value.addProperty("networkEvidenceCoverageNote", report.getNetworkEvidenceCoverageNote());

        JsonObject hashes = new JsonObject();
        hashes.add("original", originalAfter);
        Path rerunDirectory = caseOutput.resolve("rerun");
        if (Files.isRegularFile(rerunDirectory.resolve("result.manifest.json"))) {
            hashes.add("rerun", RerunStudyBundle.evidenceHashes(rerunDirectory));
        } else {
            hashes.add("rerun", JsonNull.INSTANCE);
        }
        hashes.add(
                "rerunReportJson", RerunStudyBundle.fileHash(caseOutput.resolve(RerunDiffExecutor.REPORT_JSON_NAME)));
        hashes.add(
                "rerunReportMarkdown",
                RerunStudyBundle.fileHash(caseOutput.resolve(RerunDiffExecutor.REPORT_MARKDOWN_NAME)));
        value.add("evidenceHashes", hashes);

        JsonArray unmet = assessExpectations(testCase, report, originalUnchanged, rerunDirectory);
        value.add("unmetExpectations", unmet);
        value.addProperty("expectationMet", unmet.size() == 0);
        value.add("expectedDiagnostics", testCase.expectation.toJson());
        return value;
    }

    private static JsonArray assessExpectations(
            StudyCase testCase, RerunReport report, boolean originalUnchanged, Path rerunDirectory) {
        List<String> failures = new ArrayList<String>();
        CaseExpectation expected = testCase.expectation;
        if (expected.verdict != report.getVerdict()) {
            failures.add("expected verdict " + expected.verdict + " but observed " + report.getVerdict());
        }
        if (!originalUnchanged) {
            failures.add("the executor modified the original evidence bundle");
        }
        for (String pointer : expected.corePointers) {
            if (!containsPointer(report.getCoreDivergences(), pointer)) {
                failures.add("missing required core divergence " + pointer);
            }
        }
        for (String pointer : expected.volatilePointers) {
            if (!containsPointer(report.getVolatileFieldsNoted(), pointer)) {
                failures.add("missing required volatile-field note " + pointer);
            }
        }
        if (expected.failureReasonFragment != null
                && (report.getFailureReason() == null
                        || !report.getFailureReason().contains(expected.failureReasonFragment))) {
            failures.add("failure reason does not contain " + expected.failureReasonFragment);
        }
        for (String fragment : expected.failureDetailFragments) {
            if (!containsString(report.getFailureDetails(), fragment)) {
                failures.add("failure details do not contain " + fragment);
            }
        }
        if (expected.inputTier != null) {
            if (report.getInputResolution().size() != 1) {
                failures.add("expected one verified input resolution, found "
                        + report.getInputResolution().size());
            } else {
                RerunReport.InputResolution input = report.getInputResolution().get(0);
                if (input.getTier() != expected.inputTier.intValue() || !input.isVerified()) {
                    failures.add("expected verified tier-" + expected.inputTier + " input resolution, found tier-"
                            + input.getTier() + " verified=" + input.isVerified());
                }
            }
        }
        if (expected.verdict == RerunVerdict.IDENTICAL_CORE
                && !report.getCoreDivergences().isEmpty()) {
            failures.add("IDENTICAL_CORE contains core divergences");
        }
        if (expected.verdict == RerunVerdict.DIVERGED
                && report.getCoreDivergences().isEmpty()) {
            failures.add("DIVERGED contains no core divergences");
        }
        boolean classifiedFailure =
                expected.verdict != RerunVerdict.IDENTICAL_CORE && expected.verdict != RerunVerdict.DIVERGED;
        if (classifiedFailure && Files.isRegularFile(rerunDirectory.resolve("result.manifest.json"))) {
            failures.add("classified failure emitted an apparently successful rerun manifest");
        }
        return strings(failures);
    }

    private static JsonObject resultDocument(List<JsonObject> cases) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", RESULTS_SCHEMA);
        root.addProperty("protocol", PROTOCOL);
        root.addProperty(
                "researchQuestion",
                "Can retained manifest-v4 evidence be rebuilt and re-simulated under the current code, with exact "
                        + "core agreement and explicit negative classifications rather than scalar-only reproduction?");
        root.addProperty("method", "manifest-guided reconstruction, re-simulation, and exact core diff");
        root.addProperty(
                "notEventReplay",
                "No recorded event stream is replayed into CloudSim; the standard SimulationRunner executes a rebuilt "
                        + "configuration and platform, after which the original and new evidence are compared.");
        root.addProperty("contract", CONTRACT);
        root.addProperty("executor", RerunDiffExecutor.class.getName());
        root.addProperty(
                "rerunDiffServiceIntegration",
                "No RerunDiffService type exists in the inspected tree; this driver calls the actual public "
                        + "RerunDiffExecutor.execute(Path,Path) API without mocks or a substitute service.");
        root.addProperty(
                "expectationSource",
                "Predeclared contract clauses and hand-derived runtime-length scaling; no expected verdict or required "
                        + "JSON pointer is inferred from an observed rerun report.");
        root.add("rq1", summary(cases, true));
        root.add("summary", summary(cases, false));
        JsonArray boundaries = new JsonArray();
        boundaries.add("IDENTICAL_CORE is scoped to exact non-exempt JSON values in these two small V2/V3 bundles; it "
                + "does not prove other workflows, platforms, code versions, or physical models reproduce.");
        boundaries.add("Complete V2/V3 lifecycles are compared in full, but they are not per-interval fluid-service, "
                + "packet, hardware, real-storage, failure-process, or independent random-number replay certificates.");
        boundaries.add("EVIDENCE_INVALID, input-resolution failures, and reconstruction refusal are negative protocol "
                + "outcomes; they do not represent successful re-simulation and must not be counted as consistency.");
        boundaries.add("The absent-vs-canonical-zero historical energy compatibility rule is not exercised by these "
                + "fresh powered manifests; no new volatility exemption or numeric tolerance is introduced.");
        boundaries.add("The comparison is not scalar worktree reproduction: it validates retained bundle identity and "
                + "covers configuration, platform, graph, results, metrics, ordered events, and declared sidecars.");
        root.add("coverageBoundaries", boundaries);
        JsonArray rows = new JsonArray();
        for (JsonObject testCase : cases) {
            rows.add(testCase);
        }
        root.add("cases", rows);
        return root;
    }

    private static JsonObject summary(List<JsonObject> cases, boolean controlsOnly) {
        JsonObject value = new JsonObject();
        JsonObject expectedCounts = verdictCounts();
        JsonObject actualCounts = verdictCounts();
        int count = 0;
        int matched = 0;
        int verdictMismatches = 0;
        int diagnosticMismatches = 0;
        int identicalControls = 0;
        int controlCount = 0;
        for (JsonObject testCase : cases) {
            boolean control = "GENUINE_CONTROL".equals(testCase.get("kind").getAsString());
            controlCount += control ? 1 : 0;
            if (controlsOnly && !control) {
                continue;
            }
            count++;
            String expected = testCase.get("expectedVerdict").getAsString();
            String actual = testCase.get("actualVerdict").getAsString();
            expectedCounts.addProperty(expected, expectedCounts.get(expected).getAsInt() + 1);
            actualCounts.addProperty(actual, actualCounts.get(actual).getAsInt() + 1);
            boolean verdictMatched = testCase.get("verdictMatched").getAsBoolean();
            boolean expectationMet = testCase.get("expectationMet").getAsBoolean();
            matched += expectationMet ? 1 : 0;
            verdictMismatches += verdictMatched ? 0 : 1;
            diagnosticMismatches += verdictMatched && !expectationMet ? 1 : 0;
            identicalControls += control && "IDENTICAL_CORE".equals(actual) && expectationMet ? 1 : 0;
        }
        if (controlsOnly) {
            value.addProperty("genuineControlCases", count);
            value.addProperty("genuineControlsIdenticalCore", identicalControls);
        } else {
            value.addProperty("caseCount", count);
            value.addProperty("genuineControlCases", controlCount);
            value.addProperty(
                    "actualIdenticalCore",
                    actualCounts.get(RerunVerdict.IDENTICAL_CORE.name()).getAsInt());
            value.addProperty(
                    "actualDiverged",
                    actualCounts.get(RerunVerdict.DIVERGED.name()).getAsInt());
        }
        value.addProperty("expectationMatches", matched);
        value.addProperty("unexpectedOutcomes", count - matched);
        value.addProperty("verdictMismatches", verdictMismatches);
        value.addProperty("diagnosticMismatches", diagnosticMismatches);
        value.add("expectedVerdictCounts", expectedCounts);
        value.add("actualVerdictCounts", actualCounts);
        return value;
    }

    private static JsonObject verdictCounts() {
        JsonObject counts = new JsonObject();
        for (RerunVerdict verdict : RerunVerdict.values()) {
            counts.addProperty(verdict.name(), 0);
        }
        return counts;
    }

    private static JsonArray inputResolutions(RerunReport report) {
        JsonArray values = new JsonArray();
        for (RerunReport.InputResolution input : report.getInputResolution()) {
            JsonObject value = new JsonObject();
            value.addProperty("recordedPath", input.getRecordedPath());
            value.addProperty("resolvedPath", input.getResolvedPath());
            value.addProperty("tier", input.getTier());
            value.addProperty("sha256", input.getSha256());
            value.addProperty("sizeBytes", input.getSizeBytes());
            value.addProperty("verified", input.isVerified());
            values.add(value);
        }
        return values;
    }

    private static JsonArray divergences(List<RerunReport.DivergenceEntry> divergences) {
        JsonArray values = new JsonArray();
        for (RerunReport.DivergenceEntry divergence : divergences) {
            JsonObject value = new JsonObject();
            value.addProperty("pointer", divergence.getPointer());
            value.addProperty("original", divergence.getOriginal());
            value.addProperty("rerun", divergence.getRerun());
            values.add(value);
        }
        return values;
    }

    private static boolean containsPointer(List<RerunReport.DivergenceEntry> entries, String pointer) {
        for (RerunReport.DivergenceEntry entry : entries) {
            if (pointer.equals(entry.getPointer())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsString(List<String> values, String fragment) {
        for (String value : values) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static void requireEmptyOrNonexistent(Path output) throws IOException {
        if (!Files.exists(output)) {
            return;
        }
        if (!Files.isDirectory(output)) {
            throw new IllegalStateException("Study output path exists but is not a directory: " + output);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(output)) {
            if (stream.iterator().hasNext()) {
                throw new IllegalStateException("Study output directory must be empty or nonexistent: " + output);
            }
        }
    }

    private static CaseExpectation expect(RerunVerdict verdict, String rationale) {
        return new CaseExpectation(verdict, rationale);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class StudyCase {
        private final String id;
        private final boolean genuineControl;
        private final String family;
        private final Path runDirectory;
        private final String mutation;
        private final String mutationIntegrity;
        private final String scope;
        private final CaseExpectation expectation;

        private StudyCase(
                String id,
                boolean genuineControl,
                String family,
                Path runDirectory,
                String mutation,
                String mutationIntegrity,
                String scope,
                CaseExpectation expectation) {
            this.id = id;
            this.genuineControl = genuineControl;
            this.family = family;
            this.runDirectory = runDirectory;
            this.mutation = mutation;
            this.mutationIntegrity = mutationIntegrity;
            this.scope = scope;
            this.expectation = expectation;
        }
    }

    private static final class CaseExpectation {
        private final RerunVerdict verdict;
        private final String rationale;
        private final List<String> corePointers = new ArrayList<String>();
        private final List<String> volatilePointers = new ArrayList<String>();
        private final List<String> failureDetailFragments = new ArrayList<String>();
        private String failureReasonFragment;
        private Integer inputTier;

        private CaseExpectation(RerunVerdict verdict, String rationale) {
            this.verdict = verdict;
            this.rationale = rationale;
        }

        private CaseExpectation requireCorePointers(String... pointers) {
            corePointers.addAll(Arrays.asList(pointers));
            return this;
        }

        private CaseExpectation requireVolatilePointers(String... pointers) {
            volatilePointers.addAll(Arrays.asList(pointers));
            return this;
        }

        private CaseExpectation requireFailureReason(String fragment) {
            failureReasonFragment = fragment;
            return this;
        }

        private CaseExpectation requireFailureDetails(String... fragments) {
            failureDetailFragments.addAll(Arrays.asList(fragments));
            return this;
        }

        private CaseExpectation requireInputTier(int tier) {
            inputTier = Integer.valueOf(tier);
            return this;
        }

        private JsonObject toJson() {
            JsonObject value = new JsonObject();
            value.addProperty("verdict", verdict.name());
            value.addProperty("contractRationale", rationale);
            value.add("requiredCorePointers", strings(corePointers));
            value.add("requiredVolatilePointers", strings(volatilePointers));
            value.addProperty("requiredFailureReasonFragment", failureReasonFragment);
            value.add("requiredFailureDetailFragments", strings(failureDetailFragments));
            value.addProperty("requiredVerifiedInputTier", inputTier);
            return value;
        }
    }
}
