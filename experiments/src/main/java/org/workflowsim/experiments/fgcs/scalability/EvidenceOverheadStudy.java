package org.workflowsim.experiments.fgcs.scalability;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiments.fgcs.FgcDatasets;
import org.workflowsim.experiments.fgcs.FgcPlatforms;
import org.workflowsim.experiments.fgcs.FgcStudySupport;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Round-two recording-overhead protocol; official measurements use one fresh JVM per measured scenario. */
public final class EvidenceOverheadStudy {
    public static final String PROTOCOL = "FGCS_OVERHEAD_ROUND2_V2";
    public static final String FUNCTIONAL_PROTOCOL = "FGCS_OVERHEAD_FUNCTIONAL_V2";
    private static final List<String> MODES = Collections.unmodifiableList(Arrays.asList("off", "events", "lifecycle"));
    private static final String PYTHON_EVENT_ONLY_SCOPE = "NOT_APPLICABLE_EVENT_ONLY_BUNDLE_WITHOUT_LIFECYCLE_CAPTURE";

    private EvidenceOverheadStudy() {}

    public static List<String> workflows() {
        return Arrays.asList(
                FgcDatasets.MONTAGE_50,
                FgcDatasets.MONTAGE_1000,
                "experiments/studies/makespan-scale/inputs/Montage_5000.dax");
    }

    public static List<String> modes() {
        return MODES;
    }

    /** A cyclic Latin-square rotation balances each mode position over the full 3-by-5 protocol. */
    public static List<String> modeOrder(String workflow, int repetition) {
        if (repetition < 0) {
            throw new IllegalArgumentException("Measured repetition must be nonnegative");
        }
        int workflowIndex = workflows().indexOf(workflow);
        if (workflowIndex < 0) {
            throw new IllegalArgumentException("Unknown overhead workflow " + workflow);
        }
        return rotatedModes(workflowIndex, repetition);
    }

    private static List<String> rotatedModes(int workflowIndex, int repetition) {
        int shift = Math.floorMod(workflowIndex + repetition, MODES.size());
        List<String> order = new ArrayList<String>(MODES.size());
        for (int position = 0; position < MODES.size(); position++) {
            order.add(MODES.get((shift + position) % MODES.size()));
        }
        return order;
    }

    public static FgcStudySupport.Measurement run(String workflow, String mode, int repetition, Path evidenceRoot)
            throws SimulationExecutionException {
        if (repetition < 0) {
            throw new IllegalArgumentException("Measured repetition must be nonnegative");
        }
        return runScenario(workflow, mode, repetition, evidenceRoot, false);
    }

    private static FgcStudySupport.Measurement runScenario(
            String workflow, String mode, int repetition, Path evidenceRoot, boolean warmupCompleted)
            throws SimulationExecutionException {
        int workflowIndex = workflows().indexOf(workflow);
        if (workflowIndex < 0) {
            throw new IllegalArgumentException("Unknown overhead workflow " + workflow);
        }
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("Unknown overhead mode " + mode);
        }
        String input;
        try {
            input = FgcDatasets.normalizeForStorage(workflow, evidenceRoot.resolve("inputs"))
                    .toString();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot derive bounded-storage overhead input", exception);
        }
        SimulationConfig.Builder builder = FgcStudySupport.base(input, 4)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.coherentStorageDataflowV3())
                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .eventRecordingEnabled(!"off".equals(mode))
                .networkEvidence(NetworkEvidenceConfig.off());
        if ("lifecycle".equals(mode)) {
            builder.networkEvidence(NetworkEvidenceConfig.storageLifecycleV3(2000000));
        }
        Path artifactDirectory = evidenceRoot.resolve("artifacts").resolve(scenario(workflow, mode, repetition));
        FgcStudySupport.Measurement measurement = FgcStudySupport.run(
                workflow + "-" + mode + "-r" + repetition,
                builder.build(),
                FgcPlatforms.storage(4, 1),
                artifactDirectory);
        Path manifest = artifactDirectory.resolve("result.manifest.json");
        JsonObject metadata = new JsonObject();
        metadata.addProperty("protocol", warmupCompleted ? PROTOCOL : FUNCTIONAL_PROTOCOL);
        metadata.addProperty("workflow", workflow);
        metadata.addProperty("mode", mode);
        metadata.addProperty("repetition", repetition);
        metadata.addProperty("workflowIndex", workflowIndex);
        List<String> order = rotatedModes(workflowIndex, repetition);
        metadata.addProperty("modeOrderIndex", order.indexOf(mode));
        JsonArray orderJson = new JsonArray();
        for (String item : order) {
            orderJson.add(item);
        }
        metadata.add("modeOrder", orderJson);
        metadata.addProperty("warmupCompleted", warmupCompleted);
        metadata.addProperty("normalizedInput", input);
        metadata.addProperty("platformProfile", "FGC_STORAGE_4_VMS_1_HOST");
        metadata.addProperty("artifactSizeScope", "COMMON_MANIFEST_METRICS_EVENTS_AND_MODE_APPLICABLE_LIFECYCLE_FILES");
        validateJava(manifest, metadata);
        validatePythonWhereApplicable(mode, manifest, metadata);
        measurement.addProtocolMetadata(metadata);
        return measurement;
    }

    private static String scenario(String workflow, String mode, int repetition) {
        String filename = Paths.get(workflow).getFileName().toString();
        if (filename.endsWith(".dax")) {
            filename = filename.substring(0, filename.length() - 4);
        }
        return filename + "-" + mode + "-r" + repetition;
    }

    private static void validateJava(Path manifest, JsonObject metadata) {
        long start = System.nanoTime();
        try {
            ExperimentArtifactValidator.ValidationResult validated = ExperimentArtifactValidator.validate(manifest);
            metadata.addProperty("javaValidatedEventCount", validated.getEventCount());
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Serialized Java validation failed for " + manifest, exception);
        }
        metadata.addProperty("javaValidationNanos", System.nanoTime() - start);
        metadata.addProperty("javaValidationApplicable", true);
        metadata.addProperty("javaValidationStatus", "VALID");
        metadata.addProperty(
                "javaValidationScope",
                "SERIALIZED_MANIFEST_V4_METRICS_EVENTS_AND_APPLICABLE_STORAGE_LIFECYCLE_CONTEXT_VALIDATION");
    }

    private static void validatePythonWhereApplicable(String mode, Path manifest, JsonObject metadata) {
        metadata.addProperty("pythonValidator", "scripts/verify-network-ledger.py");
        if ("events".equals(mode)) {
            metadata.addProperty("pythonValidationApplicable", false);
            metadata.add("pythonValidationNanos", JsonNull.INSTANCE);
            metadata.addProperty("pythonValidationStatus", "NA");
            metadata.addProperty("pythonValidationScope", PYTHON_EVENT_ONLY_SCOPE);
            return;
        }
        Path repository = repositoryRoot();
        Path script = repository.resolve("scripts/verify-network-ledger.py");
        long start = System.nanoTime();
        JsonObject report = runPythonValidator(repository, script, manifest);
        String status = requiredString(report, "status", manifest);
        if ("off".equals(mode)) {
            if (!"DISABLED".equals(status)
                    || !report.has("corePlanContextChecked")
                    || !report.get("corePlanContextChecked").getAsBoolean()) {
                throw new IllegalStateException(
                        "Python OFF bundle scope was not validated for " + manifest + ": " + report);
            }
        } else if (!"VALID_COMPLETE".equals(status)
                || !report.has("completeCaptureCertified")
                || !report.get("completeCaptureCertified").getAsBoolean()
                || !report.has("contextualRunChecked")
                || !report.get("contextualRunChecked").getAsBoolean()) {
            throw new IllegalStateException("Python lifecycle replay was incomplete for " + manifest + ": " + report);
        }
        metadata.addProperty("pythonValidationNanos", System.nanoTime() - start);
        metadata.addProperty("pythonValidationApplicable", true);
        metadata.addProperty("pythonValidationStatus", status);
        metadata.addProperty("pythonValidationScope", requiredString(report, "scope", manifest));
        metadata.addProperty("pythonValidationIncludesProcessStartup", true);
    }

    private static JsonObject runPythonValidator(Path repository, Path script, Path manifest) {
        Process process;
        try {
            process = new ProcessBuilder("python3", "-B", script.toString(), manifest.toString(), "--json")
                    .directory(repository.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start serialized Python validator for " + manifest, exception);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicReference<IOException> readFailure = new AtomicReference<IOException>();
        Thread reader = new Thread(
                () -> {
                    try (InputStream input = process.getInputStream()) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) >= 0) {
                            bytes.write(buffer, 0, count);
                        }
                    } catch (IOException exception) {
                        readFailure.set(exception);
                    }
                },
                "fgcs-overhead-python-validator-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Python validator timed out for " + manifest);
            }
            reader.join(5000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Interrupted during Python validation for " + manifest, interrupted);
        }
        if (reader.isAlive()) {
            process.destroyForcibly();
            throw new IllegalStateException("Cannot finish reading Python validator output for " + manifest);
        }
        if (readFailure.get() != null) {
            throw new IllegalStateException("Cannot read Python validator output for " + manifest, readFailure.get());
        }
        String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Python validator rejected or failed for " + manifest + " with exit "
                    + process.exitValue() + ": " + text);
        }
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(
                    "Python validator did not emit one JSON object for " + manifest + ": " + text, invalid);
        }
    }

    private static String requiredString(JsonObject object, String field, Path manifest) {
        if (!object.has(field)
                || !object.get(field).isJsonPrimitive()
                || !object.get(field).getAsJsonPrimitive().isString()
                || object.get(field).getAsString().trim().isEmpty()) {
            throw new IllegalStateException("Python validator omitted " + field + " for " + manifest + ": " + object);
        }
        return object.get(field).getAsString();
    }

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("scripts/verify-network-ledger.py"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate scripts/verify-network-ledger.py from the working directory");
    }

    /**
     * Functional in-process regression helper, not the official performance protocol. The Python matrix runner
     * invokes the five-argument main entry in a fresh JVM for every measured row.
     */
    public static List<FgcStudySupport.Measurement> run(Path evidenceRoot, int repetitions)
            throws SimulationExecutionException {
        if (repetitions < 1) {
            throw new IllegalArgumentException("Repetitions must be positive");
        }
        List<FgcStudySupport.Measurement> results = new ArrayList<FgcStudySupport.Measurement>();
        for (String workflow : workflows()) {
            for (int repetition = 0; repetition < repetitions; repetition++) {
                for (String mode : modeOrder(workflow, repetition)) {
                    results.add(run(workflow, mode, repetition, evidenceRoot));
                }
            }
        }
        return results;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 5) {
            Path output = Paths.get(args[0]);
            String workflow = args[1];
            String mode = args[2];
            int repetition = Integer.parseInt(args[3]);
            if (repetition < 0) {
                throw new IllegalArgumentException("Measured repetition must be nonnegative");
            }
            Path evidenceRoot = Paths.get(args[4]);
            // Exactly one full-pipeline warmup occurs in this invoking JVM and is never included in results.json.
            runScenario(workflow, mode, -1, evidenceRoot.resolve("warmup"), false);
            FgcStudySupport.Measurement measured =
                    runScenario(workflow, mode, repetition, evidenceRoot.resolve("measured"), true);
            FgcStudySupport.writeResults(output, Collections.singletonList(measured));
            System.out.println("FGCS round2 independent overhead scenario completed; warmupRows=0 measuredRows=1");
            return;
        }
        if (args.length > 2) {
            throw new IllegalArgumentException(
                    "Usage: EvidenceOverheadStudy [functional-results.json [repetitions]] or "
                            + "<result.json> <workflow> <mode> <repetition> <evidence-root>");
        }
        Path output = Paths.get(args.length == 0 ? "paper/tmp/fgcs-round2/overhead/results.json" : args[0]);
        int repetitions = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        Path absoluteOutput = output.toAbsolutePath();
        Path functionalEvidence = absoluteOutput.getParent().resolve("functional-evidence");
        FgcStudySupport.writeResults(output, run(functionalEvidence, repetitions));
        System.out.println("FGCS functional overhead matrix only; use run-fgcs-matrix.py for fresh-JVM measurements");
    }
}
