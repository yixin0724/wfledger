package org.workflowsim.experiment;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Writes the complete, self-describing evidence artifact set for one simulation run. */
public final class ExperimentArtifactWriter {

    /*
     * v3 provenance must distinguish "no study declared" from "field not written out", so null is
     * preserved.
     */
    private static final Gson GSON =
            new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final Gson JSONL_GSON = new Gson();

    private ExperimentArtifactWriter() {}

    /**
     * Writes {@code <runId>.manifest.json}, {@code <runId>.metrics.json} and
     * {@code <runId>.events.jsonl}; when recording is ON, also writes the separate network-ledger
     * JSON.
     *
     * <p>An existing file of the same name is replaced atomically only after the matching new file
     * has been written completely.</p>
     *
     * @param report immutable report of the completed simulation
     * @param outputDirectory output directory holding the established artifact trio and the
     *        optional network ledger
     * @param runId file-name-safe run identifier
     * @return absolute paths of the written artifacts; the network ledger path is null when OFF
     * @throws IOException when the directory or an artifact cannot be written
     * @throws IllegalArgumentException when the report, output directory or run identifier is
     *         invalid
     */
    public static ExperimentArtifacts write(SimulationReport report, Path outputDirectory, String runId)
            throws IOException {
        return write(report, outputDirectory, runId, null);
    }

    /**
     * Writes the complete evidence artifact set and records the optional reference or study
     * identity in the manifest.
     *
     * @param report immutable report of the completed simulation
     * @param outputDirectory output directory holding the established artifact trio and the
     *        optional network ledger
     * @param runId file-name-safe run identifier
     * @param evidenceContext optional reference or study research identity
     * @return absolute paths of the written artifacts; the network ledger path is null when OFF
     * @throws IOException when the directory or an artifact cannot be written
     * @throws IllegalArgumentException when the report, output directory or run identifier is
     *         invalid
     */
    public static ExperimentArtifacts write(
            SimulationReport report, Path outputDirectory, String runId, ExperimentEvidenceContext evidenceContext)
            throws IOException {
        requireExportableReport(report, outputDirectory, runId);
        org.workflowsim.data.NetworkEvidenceConfig option = report.getConfig().getNetworkEvidenceConfig();
        boolean group = option.getMode() == org.workflowsim.data.NetworkEvidenceConfig.Mode.FLUID_GROUP_LEDGER_V1,
                file = option.getMode() == org.workflowsim.data.NetworkEvidenceConfig.Mode.FILE_LIFECYCLE_V2,
                storage = option.getMode() == org.workflowsim.data.NetworkEvidenceConfig.Mode.FILE_STORAGE_LIFECYCLE_V3;
        if (group != (report.getNetworkEvidence() != null)
                || file != (report.getFileLifecycleEvidence() != null)
                || storage != (report.getStorageLifecycleEvidence() != null)) {
            throw new IOException("Network capture disagrees with recording configuration");
        }
        if (report.getConfig().getDataMovementModel().usesCoherentDataflowRuntime()
                != (report.getDataflowPlan() != null)) {
            throw new IOException("Coherent core file plan disagrees with model");
        }
        com.google.gson.JsonObject networkDocument = null, fileDocument = null, storageDocument = null;
        try {
            org.workflowsim.data.NetworkEvidenceConfigCodec.requireCompatible(
                    report.getConfig().getDataMovementModel(), option);
            if (group) {
                networkDocument = org.workflowsim.data.NetworkLedgerCodec.document(report.getNetworkEvidence());
            }
            if (file) {
                fileDocument = org.workflowsim.data.v2.FileLifecycleCodec.document(report.getFileLifecycleEvidence());
            }
            if (storage) {
                storageDocument =
                        org.workflowsim.data.v2.StorageLifecycleCodec.document(report.getStorageLifecycleEvidence());
            }
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new IOException("Invalid versioned network evidence before export", invalid);
        }
        validateSidecarContexts(report, group, file, storage, networkDocument, fileDocument, storageDocument);
        Path directory = outputDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory);

        Path metrics = directory.resolve(runId + ".metrics.json");
        Path events = directory.resolve(runId + ".events.jsonl");
        Path manifest = directory.resolve(runId + ".manifest.json");
        writeJson(metrics, metricsDocument(report));
        writeEvents(events, report.getEvents());

        List<Map<String, Object>> artifacts = new ArrayList<Map<String, Object>>();
        artifacts.add(artifact("metrics", metrics));
        artifacts.add(artifact("events", events));
        Path networkLedger = null;
        if (networkDocument != null) {
            networkLedger = directory.resolve(runId + ".network-ledger.json");
            writeJson(networkLedger, networkDocument);
            artifacts.add(artifact(org.workflowsim.data.NetworkLedgerCodec.ARTIFACT_ROLE, networkLedger));
        }
        Path fileLifecycle = null;
        if (fileDocument != null) {
            fileLifecycle = directory.resolve(runId + ".file-lifecycle.json");
            writeJson(fileLifecycle, fileDocument);
            artifacts.add(artifact(org.workflowsim.data.v2.FileLifecycleCodec.ARTIFACT_ROLE, fileLifecycle));
        }
        Path storageLifecycle = null;
        if (storageDocument != null) {
            storageLifecycle = directory.resolve(runId + ".storage-lifecycle.json");
            writeJson(storageLifecycle, storageDocument);
            artifacts.add(artifact(org.workflowsim.data.v2.StorageLifecycleCodec.ARTIFACT_ROLE, storageLifecycle));
        }
        ExperimentManifestWriter.writeJson(report, manifest, artifacts, evidenceContext);
        return new ExperimentArtifacts(manifest, metrics, events, networkLedger, fileLifecycle, storageLifecycle);
    }

    /**
     * 2026-10-26 complexity extraction from write; statements verbatim; order unchanged.
     * Scope: export preflight only (non-null arguments, valid runId, online binding requiring
     * complete lifecycle capture, sourceStorage matching the model).
     */
    private static void requireExportableReport(SimulationReport report, Path outputDirectory, String runId)
            throws IOException {
        if (report == null || outputDirectory == null) {
            throw new IllegalArgumentException("Report and output directory are required");
        }
        validateRunId(runId);
        if (report.getConfig().getDataflowAssignmentConfig().isEnabled()
                && !report.getConfig().getNetworkEvidenceConfig().isEnabled()) {
            throw new UnsupportedOperationException(
                    "Online assignment certification requires complete matching lifecycle capture; OFF runs cannot"
                            + "export certified actions");
        }
        if (report.getConfig().getDataMovementModel().isCoherentStorageDataflowV3()
                != (report.getPlatform().getSourceStorage() != null)) {
            throw new IOException("sourceStorage presence differs from model");
        }
    }

    /**
     * Sidecar context-validation chain extracted from
     * {@link #write(SimulationReport, Path, String, ExperimentEvidenceContext)}
     * (2026-10-23; statement order verbatim): the group ledger cross-check, the encoded V2/V3
     * lifecycle round-trip validation, or — for coherent runtime without a sidecar mode —
     * the plan-only context validation of the manifest snapshot.
     */
    private static void validateSidecarContexts(
            SimulationReport report,
            boolean group,
            boolean file,
            boolean storage,
            com.google.gson.JsonObject networkDocument,
            com.google.gson.JsonObject fileDocument,
            com.google.gson.JsonObject storageDocument)
            throws IOException {
        if (group) {
            NetworkLedgerContextValidator.validateReport(report);
        }
        if (file) {
            FileLifecycleContextValidator.validateReport(
                    report, org.workflowsim.data.v2.FileLifecycleCodec.decodeDocument(fileDocument));
        } else if (storage) {
            FileLifecycleContextValidator.validateReport(
                    report, org.workflowsim.data.v2.StorageLifecycleCodec.decodeDocument(storageDocument));
        } else if (report.getConfig().getDataMovementModel().usesCoherentDataflowRuntime()) {
            FileLifecycleContextValidator.validatePlanContext(
                    GSON.toJsonTree(ExperimentManifestWriter.fileLifecycleContextSnapshot(report))
                            .getAsJsonObject());
        }
    }

    /**
     * Atomically writes a caller-assembled research index or summary as formatted JSON.
     *
     * <p>Reference and study modules can use this method to write their own index; it does not
     * replace the complete single-run evidence artifact write performed by
     * {@link #write(SimulationReport, Path, String)}.</p>
     *
     * @param target JSON file to write
     * @param value value that Gson can serialize
     * @throws IOException when the file cannot be written or replaced
     * @throws IllegalArgumentException when the output path is null
     */
    public static void writeJson(Path target, Object value) throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("JSON output path is required");
        }
        writeText(target, GSON.toJson(value));
    }

    private static void writeEvents(Path target, List<SimulationEvent> events) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        boolean completed = false;
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            for (SimulationEvent event : events) {
                writer.write(JSONL_GSON.toJson(event));
                writer.newLine();
            }
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporary);
            }
        }
        moveAtomically(temporary, absolute);
    }

    private static void writeText(Path target, String content) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        boolean completed = false;
        try {
            Files.write(temporary, content.getBytes(StandardCharsets.UTF_8));
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporary);
            }
        }
        moveAtomically(temporary, absolute);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, Object> metricsDocument(SimulationReport report) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        // v2: the waiting-time and slowdown fields were renamed (meanJobWaitingTimeSeconds ->
        // meanJobVmQueueWaitingTimeSeconds, meanJobSlowdown -> meanJobVmLevelSlowdown) and
        // meanComputeTotalWaitingTimeSeconds / meanComputeTrueSlowdown were added; this is a
        // breaking contract change, so the schema version is bumped.
        values.put("schema", "workflowsim-simulation-metrics-v2");
        values.put("metrics", report.getMetrics());
        return values;
    }

    private static Map<String, Object> artifact(String role, Path path) throws IOException {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("role", role);
        values.put("path", path.getFileName().toString());
        values.put("sha256", ExperimentProvenance.fingerprint(path));
        values.put("sizeBytes", Files.size(path));
        return values;
    }

    private static void validateRunId(String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("Run id must be a non-empty file-name-safe identifier");
        }
    }

    /** Established artifact paths and the optional network ledger path produced by {@link #write}. */
    public static final class ExperimentArtifacts {
        private final Path manifest;
        private final Path metrics;
        private final Path events;
        private final Path networkLedger;
        private final Path fileLifecycle, storageLifecycle;

        private ExperimentArtifacts(
                Path manifest,
                Path metrics,
                Path events,
                Path networkLedger,
                Path fileLifecycle,
                Path storageLifecycle) {
            this.manifest = manifest;
            this.metrics = metrics;
            this.events = events;
            this.networkLedger = networkLedger;
            this.fileLifecycle = fileLifecycle;
            this.storageLifecycle = storageLifecycle;
        }

        public Path getManifest() {
            return manifest;
        }

        public Path getMetrics() {
            return metrics;
        }

        public Path getEvents() {
            return events;
        }
        /** @return network sidecar path, or null when recording was OFF */
        public Path getNetworkLedger() {
            return networkLedger;
        }
        /** @return V2 file lifecycle sidecar, null for OFF/V1 */
        public Path getFileLifecycle() {
            return fileLifecycle;
        }
        /** @return storage V3 sidecar, null for OFF/V1/V2 */
        public Path getStorageLifecycle() {
            return storageLifecycle;
        }
    }
}
