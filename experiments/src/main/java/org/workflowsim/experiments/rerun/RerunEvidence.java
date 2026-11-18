package org.workflowsim.experiments.rerun;

import java.nio.file.Path;

import com.google.gson.JsonObject;

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

    public Path getNetworkLedgerPath() {
        return networkLedgerPath;
    }

    public org.workflowsim.data.TransferTraceSnapshot.Status getNetworkCaptureStatus() {
        return networkCaptureStatus;
    }

    public Path getFileLifecyclePath() {
        return fileLifecyclePath;
    }

    public org.workflowsim.data.v2.FileLifecycleEvidence.Status getFileLifecycleCaptureStatus() {
        return fileLifecycleCaptureStatus;
    }

    public Path getStorageLifecyclePath() {
        return storageLifecyclePath;
    }

    public org.workflowsim.data.v2.FileLifecycleEvidence.Status getStorageLifecycleCaptureStatus() {
        return storageLifecycleCaptureStatus;
    }

    public int getEventCount() {
        return eventCount;
    }

    public JsonObject getManifest() {
        return manifest;
    }
}
