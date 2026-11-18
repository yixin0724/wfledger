package org.workflowsim.data;

public final class DataMovementModel {

    public enum Kind {
        LEGACY_WORKFLOWSIM_V1,

        FIXED_ENDPOINT_NO_CONTENTION_V1,

        PRE_EXECUTION_TRANSFER_DELAY_V1,

        PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1,

        PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1,

        COHERENT_FILE_DATAFLOW_V2,

        COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2,

        COHERENT_STORAGE_DATAFLOW_V3,

        COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3
    }

    private static final DataMovementModel LEGACY = new DataMovementModel(Kind.LEGACY_WORKFLOWSIM_V1, 0.0, 0.0, 0.0);
    private static final DataMovementModel PRE_EXECUTION_TRANSFER_DELAY =
            new DataMovementModel(Kind.PRE_EXECUTION_TRANSFER_DELAY_V1, 0.0, 0.0, 0.0);
    private static final DataMovementModel PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION =
            new DataMovementModel(Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1, 0.0, 0.0, 0.0);
    private static final DataMovementModel FAT_TREE_CONTENTION =
            new DataMovementModel(Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1, 0.0, 0.0, 0.0);

    private static final DataMovementModel COHERENT_SHARED =
            new DataMovementModel(Kind.COHERENT_FILE_DATAFLOW_V2, 0, 0, 0);
    private static final DataMovementModel COHERENT_ISOLATED =
            new DataMovementModel(Kind.COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2, 0, 0, 0);

    public static DataMovementModel coherentFileDataflowV2() {
        return COHERENT_SHARED;
    }

    public static DataMovementModel coherentFileDataflowNoContentionV2() {
        return COHERENT_ISOLATED;
    }

    public boolean isCoherentFileDataflowV2() {
        return kind == Kind.COHERENT_FILE_DATAFLOW_V2 || kind == Kind.COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2;
    }

    public boolean isCoherentShared() {
        return kind == Kind.COHERENT_FILE_DATAFLOW_V2;
    }

    private static final DataMovementModel
            STORAGE_SHARED = new DataMovementModel(Kind.COHERENT_STORAGE_DATAFLOW_V3, 0, 0, 0),
            STORAGE_ISOLATED = new DataMovementModel(Kind.COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3, 0, 0, 0);

    public static DataMovementModel coherentStorageDataflowV3() {
        return STORAGE_SHARED;
    }

    public static DataMovementModel coherentStorageDataflowNoContentionV3() {
        return STORAGE_ISOLATED;
    }

    public boolean isCoherentStorageDataflowV3() {
        return kind == Kind.COHERENT_STORAGE_DATAFLOW_V3 || kind == Kind.COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3;
    }

    public boolean usesCoherentDataflowRuntime() {
        return isCoherentFileDataflowV2() || isCoherentStorageDataflowV3();
    }

    public boolean usesSharedDataflowResources() {
        return isCoherentShared() || kind == Kind.COHERENT_STORAGE_DATAFLOW_V3;
    }

    private final Kind kind;
    private final double accessLinkBandwidthMbPerSecond;
    private final double accessLinkLatencySeconds;
    private final double sourceEndpointBandwidthMbPerSecond;

    private DataMovementModel(
            Kind kind,
            double accessLinkBandwidthMbPerSecond,
            double accessLinkLatencySeconds,
            double sourceEndpointBandwidthMbPerSecond) {
        this.kind = kind;
        this.accessLinkBandwidthMbPerSecond = accessLinkBandwidthMbPerSecond;
        this.accessLinkLatencySeconds = accessLinkLatencySeconds;
        this.sourceEndpointBandwidthMbPerSecond = sourceEndpointBandwidthMbPerSecond;
    }

    public static DataMovementModel legacyWorkflowsimV1() {
        return LEGACY;
    }

    public static DataMovementModel preExecutionTransferDelayV1() {
        return PRE_EXECUTION_TRANSFER_DELAY;
    }

    public static DataMovementModel preExecutionTransferDelayWithContentionV1() {
        return PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION;
    }

    public static DataMovementModel fatTreeContentionV1() {
        return FAT_TREE_CONTENTION;
    }

    public static DataMovementModel fixedEndpointNoContention(
            double accessLinkBandwidthMbPerSecond,
            double accessLinkLatencySeconds,
            double sourceEndpointBandwidthMbPerSecond) {
        if (!positiveFinite(accessLinkBandwidthMbPerSecond)
                || !nonNegativeFinite(accessLinkLatencySeconds)
                || !positiveFinite(sourceEndpointBandwidthMbPerSecond)) {
            throw new IllegalArgumentException("Fixed endpoint data movement requires positive finite access and "
                    + "source bandwidths and a finite non-negative latency");
        }
        return new DataMovementModel(
                Kind.FIXED_ENDPOINT_NO_CONTENTION_V1,
                accessLinkBandwidthMbPerSecond,
                accessLinkLatencySeconds,
                sourceEndpointBandwidthMbPerSecond);
    }

    private static boolean positiveFinite(double value) {
        return value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static boolean nonNegativeFinite(double value) {
        return value >= 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    public Kind getKind() {
        return kind;
    }

    public double getAccessLinkBandwidthMbPerSecond() {
        return accessLinkBandwidthMbPerSecond;
    }

    public double getAccessLinkLatencySeconds() {
        return accessLinkLatencySeconds;
    }

    public double getSourceEndpointBandwidthMbPerSecond() {
        return sourceEndpointBandwidthMbPerSecond;
    }

    public boolean isLegacyWorkflowsimV1() {
        return kind == Kind.LEGACY_WORKFLOWSIM_V1;
    }

    public boolean isPreExecutionTransferDelayV1() {
        return kind == Kind.PRE_EXECUTION_TRANSFER_DELAY_V1;
    }

    public boolean isPreExecutionTransferDelayWithContentionV1() {
        return kind == Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1;
    }

    public boolean isFatTreeContentionV1() {
        return kind == Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1;
    }
}
