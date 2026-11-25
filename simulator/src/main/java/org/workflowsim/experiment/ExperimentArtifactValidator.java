package org.workflowsim.experiment;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

public final class ExperimentArtifactValidator {

    public static final String MANIFEST_SCHEMA_V2 = "workflowsim-experiment-manifest-v2";

    public static final String MANIFEST_SCHEMA_V3 = ExperimentManifestWriter.SCHEMA_V3;

    public static final String MANIFEST_SCHEMA_V4 = ExperimentManifestWriter.SCHEMA_V4;

    public static final String EVENTS_REVISION_CONTRACT = ExperimentManifestWriter.EVENTS_REVISION_CONTRACT;

    private static final double METRIC_RELATIVE_TOLERANCE = 1e-10;

    private static final double METRIC_ABSOLUTE_TOLERANCE = 1e-8;

    private ExperimentArtifactValidator() {}

    public static ValidationResult validate(Path manifestPath) throws IOException {
        JsonObject root = openManifest(manifestPath);
        String schema = requireString(root, "schema", "experiment manifest");
        Path manifest = manifestPath.toAbsolutePath().normalize();
        JsonObject eventSummary = requireObject(root, "events", "experiment manifest");
        int expectedEvents = requireInt(eventSummary, "eventCount", "manifest events");
        JsonObject manifestMetrics = requireObject(root, "metrics", "experiment manifest");
        JsonArray artifacts = requireArray(root, "artifacts", "experiment manifest");
        Map<String, Path> paths = validateReferences(manifest.getParent(), artifacts);
        Path metrics = required(paths, "metrics");
        Path events = required(paths, "events");
        JsonObject sidecarMetrics = validateMetrics(metrics);
        if (!manifestMetrics.equals(sidecarMetrics)) {
            throw new IOException("Manifest metrics do not match the metrics sidecar");
        }
        org.workflowsim.data.NetworkEvidenceConfig recording = decodeRecordingConfig(root);
        EvidenceBundles bundles = decodeEvidenceBundles(root, paths, recording, schema);
        org.workflowsim.data.NetworkLedgerCodec.Decoded decoded = bundles.decoded;
        org.workflowsim.data.v2.FileLifecycleCodec.Decoded fileDecoded = bundles.fileDecoded;
        org.workflowsim.data.v2.StorageLifecycleCodec.Decoded storageDecoded = bundles.storageDecoded;
        NetworkLedgerContextValidator networkContext = bundles.networkContext;
        FileLifecycleContextValidator fileContext = bundles.fileContext;
        Path networkLedger = bundles.networkLedger,
                fileLifecycle = bundles.fileLifecycle,
                storageLifecycle = bundles.storageLifecycle;
        boolean groupRequested = bundles.groupRequested;
        EventContractAudit contractAudit = new EventContractAudit();
        long[] eventStream = validateEvents(events, networkContext, fileContext, contractAudit);
        int actualEvents = (int) eventStream[0];
        if (actualEvents != expectedEvents) {
            throw new IOException("Event count mismatch: manifest declares " + expectedEvents + " but JSONL contains "
                    + actualEvents);
        }

        if (actualEvents > 0) {
            checkDeclaredSequenceBound(eventSummary, "firstSequence", eventStream[1]);
            checkDeclaredSequenceBound(eventSummary, "lastSequence", eventStream[2]);
        }

        if (MANIFEST_SCHEMA_V4.equals(schema)) {
            double end = requireFiniteNonNegativeNumber(
                    requireObject(root, "result", "experiment manifest"),
                    "simulationEndSeconds",
                    "experiment manifest result");
            verifyEventContract(eventSummary, contractAudit, end);
            verifyMetricsRecompute(root, manifestMetrics, end);
            verifyR6MetricsRecompute(root, manifestMetrics, contractAudit);
        }
        if (networkContext != null) {
            networkContext.finish();
        }
        if (fileContext != null) {
            fileContext.finish();
        }
        return buildValidationResult(
                manifest,
                metrics,
                events,
                actualEvents,
                networkLedger,
                decoded,
                fileLifecycle,
                fileDecoded,
                storageLifecycle,
                storageDecoded,
                root,
                fileContext);
    }

    private static JsonObject openManifest(Path manifestPath) throws IOException {
        if (manifestPath == null) {
            throw new IllegalArgumentException("Manifest path is required");
        }
        Path manifest = manifestPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("Experiment manifest does not exist: " + manifest);
        }
        byte[] manifestBytes = Files.readAllBytes(manifest);
        JsonObject root = object(manifestBytes, "experiment manifest");
        if (isCoherentManifest(root)) {
            try {
                root = org.workflowsim.data.NetworkLedgerCodec.parseDocument(strictUtf8(manifestBytes));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid strict V2 manifest JSON", invalid);
            }
        }
        String schema = requireString(root, "schema", "experiment manifest");
        if (!MANIFEST_SCHEMA_V2.equals(schema)
                && !MANIFEST_SCHEMA_V3.equals(schema)
                && !MANIFEST_SCHEMA_V4.equals(schema)) {
            throw new IOException("Unsupported experiment manifest schema: " + schema);
        }
        requireV4ForCoherentFeatures(root, schema);
        validateManifestTopLevelShape(root);
        if (MANIFEST_SCHEMA_V3.equals(schema) || MANIFEST_SCHEMA_V4.equals(schema)) {
            validateV3Provenance(requireObject(root, "provenance", "experiment manifest"));
        }
        if (MANIFEST_SCHEMA_V4.equals(schema)) {
            ManifestV4Validator.validate(root);
        }
        return root;
    }

    private static void requireV4ForCoherentFeatures(JsonObject root, String schema) throws IOException {
        if ((isCoherentManifest(root)
                        || root.has("dataflowPlan")
                        || (root.has("platform")
                                && root.get("platform").isJsonObject()
                                && root.getAsJsonObject("platform").has("sourceStorage")))
                && !MANIFEST_SCHEMA_V4.equals(schema)) {
            throw new IOException("Coherent V2 dataflow requires manifest v4");
        }
    }

    private static ValidationResult buildValidationResult(
            Path manifest,
            Path metrics,
            Path events,
            int actualEvents,
            Path networkLedger,
            org.workflowsim.data.NetworkLedgerCodec.Decoded decoded,
            Path fileLifecycle,
            org.workflowsim.data.v2.FileLifecycleCodec.Decoded fileDecoded,
            Path storageLifecycle,
            org.workflowsim.data.v2.StorageLifecycleCodec.Decoded storageDecoded,
            JsonObject root,
            FileLifecycleContextValidator fileContext) {
        return new ValidationResult(
                manifest,
                metrics,
                events,
                actualEvents,
                networkLedger,
                decoded == null
                        ? null
                        : decoded.getEvidence().getTraceSnapshot().getStatus(),
                decoded,
                fileLifecycle,
                fileDecoded,
                storageLifecycle,
                storageDecoded,
                root,
                fileContext == null ? null : fileContext.getDataflowAssignmentResult());
    }

    private static org.workflowsim.data.NetworkEvidenceConfig decodeRecordingConfig(JsonObject root)
            throws IOException {
        org.workflowsim.data.NetworkEvidenceConfig recording = org.workflowsim.data.NetworkEvidenceConfig.off();
        if (root.getAsJsonObject("configuration").has("networkEvidence")) {
            try {
                recording = org.workflowsim.data.NetworkEvidenceConfigCodec.decodeConfig(
                        root.getAsJsonObject("configuration").get("networkEvidence"));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid network recording mode", invalid);
            }
        }
        return recording;
    }

    private static EvidenceBundles decodeEvidenceBundles(
            JsonObject root,
            Map<String, Path> paths,
            org.workflowsim.data.NetworkEvidenceConfig recording,
            String schema)
            throws IOException {
        EvidenceBundles bundles = new EvidenceBundles();
        boolean
                groupRequested =
                        recording.getMode() == org.workflowsim.data.NetworkEvidenceConfig.Mode.FLUID_GROUP_LEDGER_V1,
                fileRequested =
                        recording.getMode() == org.workflowsim.data.NetworkEvidenceConfig.Mode.FILE_LIFECYCLE_V2,
                storageRequested =
                        recording.getMode()
                                == org.workflowsim.data.NetworkEvidenceConfig.Mode.FILE_STORAGE_LIFECYCLE_V3;
        Path networkLedger = paths.get(org.workflowsim.data.NetworkLedgerCodec.ARTIFACT_ROLE),
                fileLifecycle = paths.get(org.workflowsim.data.v2.FileLifecycleCodec.ARTIFACT_ROLE),
                storageLifecycle = paths.get(org.workflowsim.data.v2.StorageLifecycleCodec.ARTIFACT_ROLE);
        if (groupRequested != (networkLedger != null)
                || fileRequested != (fileLifecycle != null)
                || storageRequested != (storageLifecycle != null)) {
            throw new IOException("Network recording configuration and versioned artifact roles disagree");
        }
        if (recording.isEnabled() && !MANIFEST_SCHEMA_V4.equals(schema)) {
            throw new IOException("Versioned network evidence requires manifest v4");
        }
        if (groupRequested) {
            try {
                bundles.decoded = org.workflowsim.data.NetworkLedgerCodec.decode(strictUtf8(networkLedger));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid network ledger content", invalid);
            }
            bundles.networkContext = new NetworkLedgerContextValidator(root, bundles.decoded.getEvidence());
        }
        if (fileRequested) {
            try {
                bundles.fileDecoded = org.workflowsim.data.v2.FileLifecycleCodec.decode(strictUtf8(fileLifecycle));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid V2 file lifecycle content", invalid);
            }
            bundles.fileContext = new FileLifecycleContextValidator(root, bundles.fileDecoded);
        }
        if (storageRequested) {
            try {
                bundles.storageDecoded =
                        org.workflowsim.data.v2.StorageLifecycleCodec.decode(strictUtf8(storageLifecycle));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid V3 storage lifecycle content", invalid);
            }
            bundles.fileContext = new FileLifecycleContextValidator(root, bundles.storageDecoded);
        }
        bundles.networkLedger = networkLedger;
        bundles.fileLifecycle = fileLifecycle;
        bundles.storageLifecycle = storageLifecycle;
        bundles.groupRequested = groupRequested;
        return bundles;
    }

    private static final class EvidenceBundles {
        private org.workflowsim.data.NetworkLedgerCodec.Decoded decoded;
        private org.workflowsim.data.v2.FileLifecycleCodec.Decoded fileDecoded;
        private org.workflowsim.data.v2.StorageLifecycleCodec.Decoded storageDecoded;
        private NetworkLedgerContextValidator networkContext;
        private FileLifecycleContextValidator fileContext;
        private Path networkLedger;
        private Path fileLifecycle;
        private Path storageLifecycle;
        private boolean groupRequested;
    }

    private static void validateManifestTopLevelShape(JsonObject root) throws IOException {
        requireObject(root, "configuration", "experiment manifest");
        requireObject(root, "platform", "experiment manifest");
        requireArray(root, "inputs", "experiment manifest");
        requireObject(root, "workflowProfile", "experiment manifest");
        requireObject(root, "result", "experiment manifest");
        requireObject(root, "metrics", "experiment manifest");
        requireObject(root, "events", "experiment manifest");
        requireArray(root, "artifacts", "experiment manifest");
        requireObject(root, "provenance", "experiment manifest");
        requireObject(root, "runtime", "experiment manifest");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ExperimentArtifactValidator <manifest.json>");
        }
        ValidationResult result = validate(Paths.get(args[0]));
        System.out.println("EXPERIMENT_ARTIFACT_VALIDATION PASSED manifest=" + result.getManifest() + " events="
                + result.getEventCount());
    }

    private static void validateV3Provenance(JsonObject provenance) throws IOException {
        if (!ExperimentProvenance.SCHEMA_V3.equals(requireString(provenance, "schema", "provenance"))) {
            throw new IOException("Unsupported provenance schema");
        }
        validateComponent(requireObject(provenance, "core", "provenance"), "provenance core");
        if (!provenance.has("study")) {
            throw new IOException("provenance is missing explicit study field");
        }
        JsonElement study = provenance.get("study");
        if (!study.isJsonNull()) {
            if (!study.isJsonObject()) {
                throw new IOException("provenance study must be an object or null");
            }
            validateStudy(study.getAsJsonObject());
        }
        JsonObject execution = requireObject(provenance, "execution", "provenance");
        requireSha256(execution, "javaClassPathSha256", "provenance execution");
        requireString(execution, "workingDirectory", "provenance execution");
        requireNullableSha256(execution, "reactorPomSha256", "provenance execution");
    }

    private static void validateStudy(JsonObject study) throws IOException {
        requireString(study, "id", "provenance study");
        validateComponent(requireObject(study, "component", "provenance study"), "provenance study component");

        JsonObject protocol = requireObject(study, "protocol", "provenance study");
        requireString(protocol, "logicalId", "provenance study protocol");
        boolean protocolAvailable = requireBoolean(protocol, "available", "provenance study protocol");
        String protocolSha256 = requireNullableSha256(protocol, "sha256", "provenance study protocol");
        if (protocolAvailable != (protocolSha256 != null)) {
            throw new IOException("provenance study protocol available/sha256 fields disagree");
        }

        JsonObject dataset = requireObject(study, "dataset", "provenance study");
        String datasetRoot = requireString(dataset, "root", "provenance study dataset");
        if (!isAbsolutePath(datasetRoot)) {
            throw new IOException("provenance study dataset root is not absolute: " + datasetRoot);
        }
        if (!"EXPLICIT_ABSOLUTE_DATASET_ROOT"
                .equals(requireString(dataset, "resolutionPolicy", "provenance study dataset"))) {
            throw new IOException("provenance study dataset has an unsupported resolution policy");
        }
    }

    private static void validateComponent(JsonObject component, String subject) throws IOException {
        requireString(component, "groupId", subject);
        requireString(component, "artifactId", subject);
        requireString(component, "version", subject);
        requireString(component, "anchorClass", subject);
        requireNullableString(component, "codeSource", subject);
        requireNullableSha256(component, "binarySha256", subject);
        requireNullableSha256(component, "modulePomSha256", subject);
        requireNullableSha256(component, "sourceTreeSha256", subject);
        requireNonNegativeInt(component, "sourceFileCount", subject);
        requireString(component, "sourceTreeScope", subject);
    }

    private static Map<String, Path> validateReferences(Path directory, JsonArray artifacts) throws IOException {
        Map<String, Path> result = new HashMap<String, Path>();
        for (JsonElement element : artifacts) {
            if (!element.isJsonObject()) {
                throw new IOException("Manifest artifact entry must be an object");
            }
            JsonObject item = element.getAsJsonObject();
            String role = requireString(item, "role", "manifest artifact");
            String relativePath = requireString(item, "path", "manifest artifact");
            Path rawPath;
            try {
                rawPath = Paths.get(relativePath);
            } catch (InvalidPathException exception) {
                throw new IOException("Manifest artifact path is invalid: " + relativePath, exception);
            }
            if (rawPath.isAbsolute() || rawPath.getNameCount() != 1) {
                throw new IOException("Manifest artifact path is invalid; it must be a file name: " + relativePath);
            }
            Path resolved = directory.resolve(rawPath).normalize();
            if (!resolved.getParent().equals(directory) || !Files.isRegularFile(resolved)) {
                throw new IOException("Manifest artifact path is invalid or missing: " + relativePath);
            }
            if (!requireSha256(item, "sha256", "manifest artifact")
                    .equals(ExperimentProvenance.fingerprint(resolved))) {
                throw new IOException("SHA-256 mismatch for manifest artifact " + relativePath);
            }
            long expectedSize = requireLong(item, "sizeBytes", "manifest artifact");
            if (expectedSize < 0L || Files.size(resolved) != expectedSize) {
                throw new IOException("Size mismatch for manifest artifact " + relativePath);
            }
            if (result.put(role, resolved) != null) {
                throw new IOException("Duplicate manifest artifact role: " + role);
            }
        }
        return result;
    }

    private static final String[] REQUIRED_METRIC_FIELDS = {
        "makespanSeconds",
        "meanJobVmQueueWaitingTimeSeconds",
        "meanJobVmLevelSlowdown",
        "meanJobResponseTimeSeconds",
        "meanComputeTotalWaitingTimeSeconds",
        "meanComputeTrueSlowdown",
    };

    private static JsonObject validateMetrics(Path path) throws IOException {
        JsonObject document = object(Files.readAllBytes(path), "metrics sidecar");
        if (!document.has("schema")
                || !document.has("metrics")
                || !document.get("metrics").isJsonObject()) {
            throw new IOException("Invalid metrics sidecar: " + path.getFileName());
        }
        String schema = document.get("schema").getAsString();
        if (!"workflowsim-simulation-metrics-v2".equals(schema)) {
            throw new IOException("Unsupported metrics schema \"" + schema
                    + "\" (expected workflowsim-simulation-metrics-v2): " + path.getFileName());
        }
        JsonObject metrics = document.getAsJsonObject("metrics");
        for (String field : REQUIRED_METRIC_FIELDS) {
            if (!metrics.has(field)) {
                throw new IOException("Metrics sidecar is missing required field \"" + field + "\" (schema v2): "
                        + path.getFileName());
            }
        }
        return metrics;
    }

    private static String strictUtf8(Path path) throws IOException {
        return strictUtf8(Files.readAllBytes(path));
    }

    private static String strictUtf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
    }

    private static boolean isCoherentManifest(JsonObject root) {
        if (root.has("dataflowComputeRequests")) {
            return true;
        }
        JsonElement config = root.get("configuration");
        if (config == null || !config.isJsonObject()) {
            return false;
        }
        if (config.getAsJsonObject().has("dataflowAssignment")) {
            return true;
        }
        JsonElement model = config.getAsJsonObject().get("dataMovementModel");
        if (model == null || !model.isJsonObject()) {
            return false;
        }
        JsonElement kind = model.getAsJsonObject().get("kind");
        if (kind == null
                || !kind.isJsonPrimitive()
                || !kind.getAsJsonPrimitive().isString()) {
            return false;
        }
        return "COHERENT_FILE_DATAFLOW_V2".equals(kind.getAsString())
                || "COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2".equals(kind.getAsString())
                || "COHERENT_STORAGE_DATAFLOW_V3".equals(kind.getAsString())
                || "COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3".equals(kind.getAsString());
    }

    private static long[] validateEvents(
            Path path,
            NetworkLedgerContextValidator networkContext,
            FileLifecycleContextValidator fileContext,
            EventContractAudit contractAudit)
            throws IOException {
        int count = 0;
        long firstSequence = -1L;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    throw new IOException("Events JSONL contains a blank line: " + path.getFileName());
                }
                JsonObject event;
                try {
                    event = fileContext == null
                            ? JsonParser.parseString(line).getAsJsonObject()
                            : org.workflowsim.data.NetworkLedgerCodec.parseDocument(line);
                } catch (RuntimeException exception) {
                    throw new IOException("Invalid JSONL event " + count + " in " + path.getFileName(), exception);
                }
                long sequence = requireLong(event, "sequence", "JSONL event");
                if (count == 0) {
                    firstSequence = sequence;
                }
                if (sequence != count) {
                    throw new IOException("JSONL event sequence mismatch at line " + (count + 1) + ": expected " + count
                            + " but found " + sequence);
                }
                String type = null;
                if (event.has("type") && event.get("type").isJsonPrimitive()) {
                    type = event.get("type").getAsString();
                }
                if (fileContext == null && "DATAFLOW_VM_ASSIGNED".equals(type)) {
                    throw new IOException("Online assignment events require their full lifecycle context");
                }

                contractAudit.acceptEvent(type, event);
                if (networkContext != null) {
                    networkContext.acceptEvent(event);
                }
                if (fileContext != null) {
                    fileContext.acceptEvent(event);
                }
                count++;
            }
        }
        long lastSequence = count == 0 ? -1L : count - 1L;
        return new long[] {count, firstSequence, lastSequence};
    }

    private static void checkDeclaredSequenceBound(JsonObject summary, String key, long actual) throws IOException {
        if (!summary.has(key) || summary.get(key).isJsonNull()) {
            return;
        }
        long declared = summary.get(key).getAsLong();
        if (declared != actual) {
            throw new IOException(
                    "Declared " + key + " " + declared + " does not match the event stream bound " + actual);
        }
    }

    private static void verifyEventContract(JsonObject eventSummary, EventContractAudit audit, double end)
            throws IOException {
        String declaredContract = declaredRevisionContract(eventSummary);
        if (declaredContract != null && !EVENTS_REVISION_CONTRACT.equals(declaredContract)) {
            throw new IOException("Unsupported events revisionContract \"" + declaredContract
                    + "\"; this validator implements " + EVENTS_REVISION_CONTRACT);
        }
        boolean contractDeclared = declaredContract != null;
        if (contractDeclared && audit.terminalCount != 1) {
            throw new IOException("The declared events revisionContract " + EVENTS_REVISION_CONTRACT
                    + " requires exactly one SIMULATION_TERMINATED event, but the stream contains "
                    + audit.terminalCount);
        }
        if (audit.terminalCount > 0) {
            if (audit.terminalCount != 1) {
                throw new IOException("Duplicate SIMULATION_TERMINATED events: " + audit.terminalCount);
            }
            if (audit.terminalClock != end || audit.terminalDeclaredEnd != end) {
                throw new IOException("Terminal clock identity violated: event time " + audit.terminalClock
                        + ", declared end " + audit.terminalDeclaredEnd + ", manifest end " + end);
            }
            if (!"SIMULATION_TERMINATED".equals(audit.lastType)) {
                throw new IOException("Terminal event position violated: last event type is " + audit.lastType);
            }
        }
        for (Map.Entry<Long, double[]> entry : audit.startedDerivations.entrySet()) {
            double[] derivation = entry.getValue();
            if (derivation[2] != derivation[0] + derivation[1]) {
                throw new IOException("Job start derivation violated for job " + entry.getKey() + ": modeled "
                        + derivation[2] + " != envelope " + derivation[0] + " + effective stage " + derivation[1]);
            }
        }
        if (contractDeclared || !audit.startedDerivations.isEmpty() || audit.terminalCount > 0) {
            List<Long> uncovered = new ArrayList<>();
            for (Long executed : audit.executedJobs) {
                if (!audit.startedDerivations.containsKey(executed)) {
                    uncovered.add(executed);
                }
            }
            if (!uncovered.isEmpty()) {
                Collections.sort(uncovered);
                throw new IOException("Job start coverage violated: executed jobs without a JOB_EXECUTION_STARTED "
                        + "marker " + uncovered);
            }
        }
    }

    private static String declaredRevisionContract(JsonObject eventSummary) throws IOException {
        if (!eventSummary.has("revisionContract")
                || eventSummary.get("revisionContract").isJsonNull()) {
            return null;
        }
        JsonElement declared = eventSummary.get("revisionContract");
        if (!declared.isJsonPrimitive() || !declared.getAsJsonPrimitive().isString()) {
            throw new IOException("events revisionContract must be a string when present");
        }
        return declared.getAsString();
    }

    private static void verifyMetricsRecompute(JsonObject root, JsonObject metrics, double end) throws IOException {
        JsonObject result = requireObject(root, "result", "experiment manifest");
        JsonArray jobs = requireArray(result, "jobs", "experiment manifest result");
        int stageInClass = org.workflowsim.utils.Parameters.ClassType.STAGE_IN.value;
        int computeClass = org.workflowsim.utils.Parameters.ClassType.COMPUTE.value;
        double queueWaitSum = 0.0;
        int computeCount = 0;
        Set<Long> seenJobIds = new HashSet<>();
        for (int index = 0; index < jobs.size(); index++) {
            JsonElement element = jobs.get(index);
            if (!element.isJsonObject()) {
                throw new IOException("Experiment manifest result job " + index + " is not an object");
            }
            JsonObject job = element.getAsJsonObject();
            long jobId = requireLong(job, "jobId", "experiment manifest result job");
            if (!seenJobIds.add(Long.valueOf(jobId))) {
                throw new IOException("Duplicate experiment manifest result job id: " + jobId);
            }
            int classType = requireInt(job, "classType", "experiment manifest result job");
            if (classType != stageInClass && classType != computeClass) {
                throw new IOException(
                        "Unsupported experiment manifest result job class type " + classType + " for job " + jobId);
            }
            double submissionTime =
                    requireFiniteNonNegativeNumber(job, "submissionTime", "experiment manifest result job");
            double startTime = requireFiniteNonNegativeNumber(job, "startTime", "experiment manifest result job");
            if (classType == computeClass) {
                queueWaitSum += Math.max(0.0, startTime - submissionTime);
                computeCount++;
            }
        }
        double expectedQueueWait = computeCount == 0 ? 0.0 : queueWaitSum / computeCount;
        verifyMetricClose(metrics, "makespanSeconds", end);
        verifyMetricClose(metrics, "meanJobVmQueueWaitingTimeSeconds", expectedQueueWait);
    }

    /**
     * Recomputes the FGCS-R6 metric additions that the manifest and event stream determine.
     *
     * <p>FGCS-R6 (2026-11-25): a metrics document that declares {@code boundedSlowdownThresholdSeconds} must
     * agree with the declared {@code configuration.boundedSlowdownThresholdSeconds} (finite, positive), and a
     * declared {@code totalInputReadSecondsInEnvelope} must equal the sum of {@code effectiveDataStageSeconds}
     * over the {@code JOB_EXECUTION_STARTED} events of result Jobs, with the compute-only remainder and the
     * share following from the busy total. Pre-R6 documents declare neither field and keep their authority, and
     * so does a stream that carries no start marker.
     */
    private static void verifyR6MetricsRecompute(JsonObject root, JsonObject metrics, EventContractAudit audit)
            throws IOException {
        if (metrics.has("boundedSlowdownThresholdSeconds")) {
            JsonObject config = requireObject(root, "configuration", "experiment manifest");
            double tau = requireFiniteNonNegativeNumber(
                    config, "boundedSlowdownThresholdSeconds", "experiment manifest configuration");
            if (!(tau > 0.0)) {
                throw new IOException("configuration.boundedSlowdownThresholdSeconds must be positive: " + tau);
            }
            verifyMetricClose(metrics, "boundedSlowdownThresholdSeconds", tau);
        }
        if (!metrics.has("totalInputReadSecondsInEnvelope") || audit.startedDerivations.isEmpty()) {
            // A stream without JOB_EXECUTION_STARTED markers keeps the manifest-authority boundary, exactly
            // as the F-006/F-007 cross-checks do for undeclared marker-less streams.
            return;
        }
        JsonArray jobs = requireArray(
                requireObject(root, "result", "experiment manifest"), "jobs", "experiment manifest result");
        double read = 0.0;
        int observations = 0;
        for (JsonElement element : jobs) {
            long jobId = requireLong(element.getAsJsonObject(), "jobId", "experiment manifest result job");
            double[] derivation = audit.startedDerivations.get(Long.valueOf(jobId));
            if (derivation != null) {
                read += derivation[1];
                observations++;
            }
        }
        double busy = requireFiniteNonNegativeNumber(metrics, "totalVmModeledBusyIntervalSeconds", "metrics");
        verifyMetricClose(metrics, "totalInputReadSecondsInEnvelope", read);
        verifyMetricClose(metrics, "totalComputeOnlyBusySeconds", busy - read);
        verifyMetricClose(metrics, "inputReadShareOfBusyTime", busy > 0.0 ? read / busy : 0.0);
        verifyMetricClose(metrics, "inputReadInEnvelopeObservationCount", observations);
    }

    private static void verifyMetricClose(JsonObject metrics, String field, double expected) throws IOException {
        JsonElement element = metrics.get(field);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Metrics field \"" + field + "\" is not a number");
        }
        double declared = element.getAsDouble();
        if (!Double.isFinite(declared)) {
            throw new IOException("Metrics field \"" + field + "\" is not finite: " + declared);
        }
        double tolerance = Math.max(
                METRIC_RELATIVE_TOLERANCE * Math.max(Math.abs(declared), Math.abs(expected)),
                METRIC_ABSOLUTE_TOLERANCE);
        if (Math.abs(declared - expected) > tolerance) {
            throw new IOException("Recomputed " + field + " " + expected + " does not match the declared metrics "
                    + "value " + declared + " (rel_tol " + METRIC_RELATIVE_TOLERANCE + ", abs_tol "
                    + METRIC_ABSOLUTE_TOLERANCE + ")");
        }
    }

    private static double requireFiniteNonNegativeNumber(JsonObject object, String key, String subject)
            throws IOException {
        if (!object.has(key)
                || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isNumber()) {
            throw new IOException(subject + " is missing number " + key);
        }
        double value = object.get(key).getAsDouble();
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IOException(subject + " has invalid number " + key + ": " + value);
        }
        return value;
    }

    private static Path required(Map<String, Path> paths, String role) throws IOException {
        Path value = paths.get(role);
        if (value == null) {
            throw new IOException("Manifest is missing required artifact role: " + role);
        }
        return value;
    }

    private static JsonObject object(byte[] bytes, String subject) throws IOException {
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid JSON " + subject, exception);
        }
    }

    private static JsonObject requireObject(JsonObject object, String key, String subject) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonObject()) {
            throw new IOException(subject + " is missing object " + key);
        }
        return object.get(key).getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject object, String key, String subject) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw new IOException(subject + " is missing array " + key);
        }
        return object.get(key).getAsJsonArray();
    }

    private static String requireString(JsonObject object, String key, String subject) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IOException(subject + " is missing string " + key);
        }
        JsonPrimitive primitive = object.get(key).getAsJsonPrimitive();
        if (!primitive.isString() || primitive.getAsString().trim().isEmpty()) {
            throw new IOException(subject + " has invalid string " + key);
        }
        return primitive.getAsString();
    }

    private static String requireNullableString(JsonObject object, String key, String subject) throws IOException {
        if (!object.has(key)) {
            throw new IOException(subject + " is missing nullable string " + key);
        }
        JsonElement element = object.get(key);
        if (element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()
                || element.getAsString().trim().isEmpty()) {
            throw new IOException(subject + " has invalid nullable string " + key);
        }
        return element.getAsString();
    }

    private static boolean requireBoolean(JsonObject object, String key, String subject) throws IOException {
        if (!object.has(key)
                || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isBoolean()) {
            throw new IOException(subject + " has invalid boolean " + key);
        }
        return object.get(key).getAsBoolean();
    }

    private static int requireNonNegativeInt(JsonObject object, String key, String subject) throws IOException {
        int value = requireInt(object, key, subject);
        if (value < 0) {
            throw new IOException(subject + " has negative integer " + key);
        }
        return value;
    }

    private static int requireInt(JsonObject object, String key, String subject) throws IOException {
        long value = requireLong(object, key, subject);
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw new IOException(subject + " has out-of-range integer " + key);
        }
        return (int) value;
    }

    private static long requireLong(JsonObject object, String key, String subject) throws IOException {
        if (!object.has(key)
                || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isNumber()) {
            throw new IOException(subject + " is missing number " + key);
        }
        try {
            return object.get(key).getAsBigDecimal().longValueExact();
        } catch (RuntimeException exception) {
            throw new IOException(subject + " has invalid number " + key, exception);
        }
    }

    private static String requireSha256(JsonObject object, String key, String subject) throws IOException {
        String value = requireString(object, key, subject);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IOException(subject + " has invalid SHA-256 " + key);
        }
        return value;
    }

    private static String requireNullableSha256(JsonObject object, String key, String subject) throws IOException {
        String value = requireNullableString(object, key, subject);
        if (value != null && !value.matches("[0-9a-f]{64}")) {
            throw new IOException(subject + " has invalid nullable SHA-256 " + key);
        }
        return value;
    }

    private static boolean isAbsolutePath(String value) {
        try {
            return Paths.get(value).isAbsolute();
        } catch (InvalidPathException exception) {
            return false;
        }
    }

    private static final class EventContractAudit {
        private int terminalCount;
        private double terminalClock;
        private double terminalDeclaredEnd;
        private String lastType;
        private final Map<Long, double[]> startedDerivations = new LinkedHashMap<>();
        private final Set<Long> executedJobs = new HashSet<>();

        private void acceptEvent(String type, JsonObject event) throws IOException {
            lastType = type;
            if ("SIMULATION_TERMINATED".equals(type)) {
                terminalCount++;
                terminalClock = requireFiniteNonNegativeNumber(event, "simulationTime", "terminal event");
                terminalDeclaredEnd = requireFiniteNonNegativeNumber(
                        requireObject(event, "attributes", "terminal event"),
                        "simulationEndSeconds",
                        "terminal event attributes");
            } else if ("JOB_EXECUTION_STARTED".equals(type)) {
                long jobId = requireLong(event, "jobId", "JSONL event");
                JsonObject attributes = requireObject(event, "attributes", "started event");
                double envelope =
                        requireFiniteNonNegativeNumber(attributes, "jobEnvelopeStartSeconds", "started event");
                double effective =
                        requireFiniteNonNegativeNumber(attributes, "effectiveDataStageSeconds", "started event");
                requireFiniteNonNegativeNumber(attributes, "requestedDataStageSeconds", "started event");
                double modeled = requireFiniteNonNegativeNumber(attributes, "modeledJobStartSeconds", "started event");
                if (startedDerivations.put(Long.valueOf(jobId), new double[] {envelope, effective, modeled}) != null) {
                    throw new IOException("Duplicate JOB_EXECUTION_STARTED event for job " + jobId);
                }
            } else if ("TASK_EXECUTION_MODELED".equals(type)) {
                executedJobs.add(Long.valueOf(requireLong(event, "jobId", "JSONL event")));
            }
        }
    }

    public static final class ValidationResult {
        private final Path manifest;
        private final Path metrics;
        private final Path events;
        private final int eventCount;
        private final Path networkLedger;
        private final org.workflowsim.data.TransferTraceSnapshot.Status networkCaptureStatus;
        private final org.workflowsim.data.NetworkLedgerCodec.Decoded decodedNetworkLedger;
        private final JsonObject validatedManifest;
        private final Path fileLifecycle;
        private final org.workflowsim.data.v2.FileLifecycleCodec.Decoded decodedFileLifecycle;
        private final Path storageLifecycle;
        private final org.workflowsim.data.v2.StorageLifecycleCodec.Decoded decodedStorageLifecycle;
        private final org.workflowsim.data.v2.DataflowAssignmentValidator.Result dataflowAssignment;

        private ValidationResult(
                Path manifest,
                Path metrics,
                Path events,
                int eventCount,
                Path networkLedger,
                org.workflowsim.data.TransferTraceSnapshot.Status networkCaptureStatus,
                org.workflowsim.data.NetworkLedgerCodec.Decoded decodedNetworkLedger,
                Path fileLifecycle,
                org.workflowsim.data.v2.FileLifecycleCodec.Decoded decodedFileLifecycle,
                Path storageLifecycle,
                org.workflowsim.data.v2.StorageLifecycleCodec.Decoded decodedStorageLifecycle,
                JsonObject validatedManifest,
                org.workflowsim.data.v2.DataflowAssignmentValidator.Result dataflowAssignment) {
            this.manifest = manifest;
            this.metrics = metrics;
            this.events = events;
            this.eventCount = eventCount;
            this.networkLedger = networkLedger;
            this.networkCaptureStatus = networkCaptureStatus;
            this.decodedNetworkLedger = decodedNetworkLedger;
            this.validatedManifest = validatedManifest;
            this.fileLifecycle = fileLifecycle;
            this.decodedFileLifecycle = decodedFileLifecycle;
            this.storageLifecycle = storageLifecycle;
            this.decodedStorageLifecycle = decodedStorageLifecycle;
            this.dataflowAssignment = dataflowAssignment;
        }

        public org.workflowsim.data.v2.DataflowAssignmentValidator.Result getDataflowAssignment() {
            return dataflowAssignment;
        }

        public Path getManifest() {
            return manifest;
        }

        public JsonObject getManifestSnapshot() {
            return validatedManifest.deepCopy();
        }

        public Path getMetrics() {
            return metrics;
        }

        public Path getEvents() {
            return events;
        }

        public int getEventCount() {
            return eventCount;
        }

        public Path getNetworkLedger() {
            return networkLedger;
        }

        public org.workflowsim.data.TransferTraceSnapshot.Status getNetworkCaptureStatus() {
            return networkCaptureStatus;
        }

        public org.workflowsim.data.NetworkLedgerCodec.Decoded getDecodedNetworkLedger() {
            return decodedNetworkLedger;
        }

        public Path getFileLifecycle() {
            return fileLifecycle;
        }

        public org.workflowsim.data.v2.FileLifecycleCodec.Decoded getDecodedFileLifecycle() {
            return decodedFileLifecycle;
        }

        public org.workflowsim.data.v2.FileLifecycleEvidence.Status getFileLifecycleCaptureStatus() {
            return decodedFileLifecycle == null
                    ? null
                    : decodedFileLifecycle.getEvidence().getStatus();
        }

        public Path getStorageLifecycle() {
            return storageLifecycle;
        }

        public org.workflowsim.data.v2.StorageLifecycleCodec.Decoded getDecodedStorageLifecycle() {
            return decodedStorageLifecycle;
        }

        public org.workflowsim.data.v2.FileLifecycleEvidence.Status getStorageLifecycleCaptureStatus() {
            return decodedStorageLifecycle == null
                    ? null
                    : decodedStorageLifecycle.getEvidence().getCapture().getStatus();
        }
    }
}
