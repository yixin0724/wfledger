package org.workflowsim.data;

public final class NetworkEvidenceConfig {

    public enum Mode {
        OFF,

        FLUID_GROUP_LEDGER_V1,

        FILE_LIFECYCLE_V2,

        FILE_STORAGE_LIFECYCLE_V3
    }

    private static final NetworkEvidenceConfig OFF = new NetworkEvidenceConfig(Mode.OFF, 0);
    private final Mode mode;
    private final int maxTraceRecords;

    private NetworkEvidenceConfig(Mode mode, int maxTraceRecords) {
        this.mode = mode;
        this.maxTraceRecords = maxTraceRecords;
    }

    public static NetworkEvidenceConfig off() {
        return OFF;
    }

    public static NetworkEvidenceConfig fluidGroupLedger(int maxTraceRecords) {
        if (maxTraceRecords <= 0) {
            throw new IllegalArgumentException("Network trace record budget must be positive");
        }
        return new NetworkEvidenceConfig(Mode.FLUID_GROUP_LEDGER_V1, maxTraceRecords);
    }

    public static NetworkEvidenceConfig fileLifecycleV2(int maxTraceRecords) {
        if (maxTraceRecords <= 0) {
            throw new IllegalArgumentException("File lifecycle record budget must be positive");
        }
        return new NetworkEvidenceConfig(Mode.FILE_LIFECYCLE_V2, maxTraceRecords);
    }

    public static NetworkEvidenceConfig storageLifecycleV3(int budget) {
        if (budget <= 0) {
            throw new IllegalArgumentException("Storage lifecycle record budget must be positive");
        }
        return new NetworkEvidenceConfig(Mode.FILE_STORAGE_LIFECYCLE_V3, budget);
    }

    public Mode getMode() {
        return mode;
    }

    public int getMaxTraceRecords() {
        return maxTraceRecords;
    }

    public boolean isEnabled() {
        return mode != Mode.OFF;
    }
}
