package org.workflowsim.experiments.rerun;

import java.nio.file.Path;

import com.google.gson.JsonObject;

/**
 * One validated v4 evidence bundle: the manifest/metrics/events triple, the optional network
 * ledger path/status, the event count, and the manifest.
 *
 * <p>This object is a read-only snapshot. The manifest is kept as-is as a Gson
 * {@code JsonObject} tree, and the later configuration rebuild and comparison both work on
 * that tree; the hashes, counts, and required content/context of metrics/events and of the
 * optional network-ledger have already been checked through the existing verifiers, which
 * {@link RerunEvidenceReader} delegates to.</p>
 */
public final class RerunEvidence {

    private final Path runDirectory;
    private final Path manifestPath;
    private final Path metricsPath;
    private final Path eventsPath;
    private final int eventCount;
    private final JsonObject manifest;
    private final Path networkLedgerPath;
    private final org.workflowsim.data.TransferTraceSnapshot.Status networkCaptureStatus;
    private final Path fileLifecyclePath;
    private final org.workflowsim.data.v2.FileLifecycleEvidence.Status fileLifecycleCaptureStatus;
    private final Path storageLifecyclePath;
    private final org.workflowsim.data.v2.FileLifecycleEvidence.Status storageLifecycleCaptureStatus;

    RerunEvidence(
            Path runDirectory,
            Path manifestPath,
            Path metricsPath,
            Path eventsPath,
            int eventCount,
            JsonObject manifest) {
        this(runDirectory, manifestPath, metricsPath, eventsPath, eventCount, manifest, null, null);
    }

    RerunEvidence(
            Path runDirectory,
            Path manifestPath,
            Path metricsPath,
            Path eventsPath,
            int eventCount,
            JsonObject manifest,
            Path networkLedgerPath,
            org.workflowsim.data.TransferTraceSnapshot.Status networkCaptureStatus) {
        this(
                runDirectory,
                manifestPath,
                metricsPath,
                eventsPath,
                eventCount,
                manifest,
                networkLedgerPath,
                networkCaptureStatus,
                null,
                null);
    }

    RerunEvidence(
            Path runDirectory,
            Path manifestPath,
            Path metricsPath,
            Path eventsPath,
            int eventCount,
            JsonObject manifest,
            Path networkLedgerPath,
            org.workflowsim.data.TransferTraceSnapshot.Status networkCaptureStatus,
            Path fileLifecyclePath,
            org.workflowsim.data.v2.FileLifecycleEvidence.Status fileLifecycleCaptureStatus) {
        this(
                runDirectory,
                manifestPath,
                metricsPath,
                eventsPath,
                eventCount,
                manifest,
                networkLedgerPath,
                networkCaptureStatus,
                fileLifecyclePath,
                fileLifecycleCaptureStatus,
                null,
                null);
    }

    /**
     * 2026-10-26 complexity extraction from RerunEvidence constructor; statements verbatim; order unchanged.
     * Scope: validates only that the five evidence components are non-null; a violation throws
     * the 'missing required evidence component' error.
     */
    private static void requireEvidenceComponents(
            Path runDirectory, Path manifestPath, Path metricsPath, Path eventsPath, JsonObject manifest) {
        if (runDirectory == null
                || manifestPath == null
                || metricsPath == null
                || eventsPath == null
                || manifest == null) {
            throw new IllegalArgumentException("All evidence components are required");
        }
    }

    /**
     * 2026-10-26 complexity extraction from RerunEvidence constructor; statements verbatim; order unchanged.
     * Scope: decodes only the optional network evidence configuration from
     * configuration.networkEvidence in the manifest (absent means off).
     */
    private static org.workflowsim.data.NetworkEvidenceConfig manifestNetworkEvidenceOption(JsonObject manifest) {
        org.workflowsim.data.NetworkEvidenceConfig option = org.workflowsim.data.NetworkEvidenceConfig.off();
        if (manifest.has("configuration")
                && manifest.get("configuration").isJsonObject()
                && manifest.getAsJsonObject("configuration").has("networkEvidence")) {
            option = org.workflowsim.data.NetworkEvidenceConfigCodec.decodeConfig(
                    manifest.getAsJsonObject("configuration").get("networkEvidence"));
        }
        return option;
    }

    /**
     * 2026-10-26 complexity extraction from RerunEvidence constructor; statements verbatim; order unchanged.
     * Scope: validates only the self-consistency of the declared mode with the path/status
     * combinations of the three sidecar kinds; a mismatch throws a pipeline configuration
     * exception.
     */
    private static void requireConsistentSidecarContract(
            org.workflowsim.data.NetworkEvidenceConfig option,
            Path networkLedgerPath,
            org.workflowsim.data.TransferTraceSnapshot.Status networkCaptureStatus,
            Path fileLifecyclePath,
            org.workflowsim.data.v2.FileLifecycleEvidence.Status fileLifecycleCaptureStatus,
            Path storageLifecyclePath,
            org.workflowsim.data.v2.FileLifecycleEvidence.Status storageLifecycleCaptureStatus) {
        boolean group = option.getMode() == org.workflowsim.data.NetworkEvidenceConfig.Mode.FLUID_GROUP_LEDGER_V1,
                file = option.getMode() == org.workflowsim.data.NetworkEvidenceConfig.Mode.FILE_LIFECYCLE_V2,
                storage = option.getMode() == org.workflowsim.data.NetworkEvidenceConfig.Mode.FILE_STORAGE_LIFECYCLE_V3;
        if (group != (networkLedgerPath != null)
                || (networkLedgerPath == null) != (networkCaptureStatus == null)
                || networkCaptureStatus == org.workflowsim.data.TransferTraceSnapshot.Status.DISABLED
                || file != (fileLifecyclePath != null)
                || (fileLifecyclePath == null) != (fileLifecycleCaptureStatus == null)
                || (fileLifecycleCaptureStatus != null
                        && fileLifecycleCaptureStatus != org.workflowsim.data.v2.FileLifecycleEvidence.Status.COMPLETE)
                || storage != (storageLifecyclePath != null)
                || (storageLifecyclePath == null) != (storageLifecycleCaptureStatus == null)
                || (storageLifecycleCaptureStatus != null
                        && storageLifecycleCaptureStatus
                                != org.workflowsim.data.v2.FileLifecycleEvidence.Status.COMPLETE)) {
            throw new IllegalArgumentException(
                    "Declared network evidence requires its version-specific validated path and capture status");
        }
    }

    RerunEvidence(
            Path runDirectory,
            Path manifestPath,
            Path metricsPath,
            Path eventsPath,
            int eventCount,
            JsonObject manifest,
            Path networkLedgerPath,
            org.workflowsim.data.TransferTraceSnapshot.Status networkCaptureStatus,
            Path fileLifecyclePath,
            org.workflowsim.data.v2.FileLifecycleEvidence.Status fileLifecycleCaptureStatus,
            Path storageLifecyclePath,
            org.workflowsim.data.v2.FileLifecycleEvidence.Status storageLifecycleCaptureStatus) {
        requireEvidenceComponents(runDirectory, manifestPath, metricsPath, eventsPath, manifest);
        this.runDirectory = runDirectory;
        this.manifestPath = manifestPath;
        this.metricsPath = metricsPath;
        this.eventsPath = eventsPath;
        this.eventCount = eventCount;
        org.workflowsim.data.NetworkEvidenceConfig option = manifestNetworkEvidenceOption(manifest);
        requireConsistentSidecarContract(
                option,
                networkLedgerPath,
                networkCaptureStatus,
                fileLifecyclePath,
                fileLifecycleCaptureStatus,
                storageLifecyclePath,
                storageLifecycleCaptureStatus);
        this.manifest = manifest;
        this.networkLedgerPath = networkLedgerPath;
        this.networkCaptureStatus = networkCaptureStatus;
        this.fileLifecyclePath = fileLifecyclePath;
        this.fileLifecycleCaptureStatus = fileLifecycleCaptureStatus;
        this.storageLifecyclePath = storageLifecyclePath;
        this.storageLifecycleCaptureStatus = storageLifecycleCaptureStatus;
    }

    public Path getRunDirectory() {
        return runDirectory;
    }

    public Path getManifestPath() {
        return manifestPath;
    }

    public Path getMetricsPath() {
        return metricsPath;
    }

    public Path getEventsPath() {
        return eventsPath;
    }

    /** @return validated optional network ledger path, or null when OFF */
    public Path getNetworkLedgerPath() {
        return networkLedgerPath;
    }

    /** @return validated optional capture status, or null when OFF */
    public org.workflowsim.data.TransferTraceSnapshot.Status getNetworkCaptureStatus() {
        return networkCaptureStatus;
    }
    /** @return validated V2 sidecar, null for OFF/V1 */
    public Path getFileLifecyclePath() {
        return fileLifecyclePath;
    }
    /** @return validated complete V2 capture, null for OFF/V1 */
    public org.workflowsim.data.v2.FileLifecycleEvidence.Status getFileLifecycleCaptureStatus() {
        return fileLifecycleCaptureStatus;
    }

    /** @return validated storage V3 sidecar, null for OFF/V1/V2 */
    public Path getStorageLifecyclePath() {
        return storageLifecyclePath;
    }
    /** @return validated complete storage capture, null for OFF/V1/V2 */
    public org.workflowsim.data.v2.FileLifecycleEvidence.Status getStorageLifecycleCaptureStatus() {
        return storageLifecycleCaptureStatus;
    }

    /** Number of events declared by the manifest and confirmed by the measured JSONL. */
    public int getEventCount() {
        return eventCount;
    }

    /** Parsed manifest tree (v4 schema). Callers must not modify it. */
    public JsonObject getManifest() {
        return manifest;
    }
}
