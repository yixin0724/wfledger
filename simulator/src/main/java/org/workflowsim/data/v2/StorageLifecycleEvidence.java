package org.workflowsim.data.v2;

public final class StorageLifecycleEvidence {
    private final FileLifecycleEvidence capture;
    private final boolean storeBackedInputs;

    private StorageLifecycleEvidence(FileLifecycleEvidence capture, boolean storeBackedInputs) {
        this.capture = capture;
        this.storeBackedInputs = storeBackedInputs;
    }

    public static StorageLifecycleEvidence capture(FileLifecycleEvidence capture, boolean storeBackedInputs) {
        if (capture == null || !capture.getFabric().has("sourceStorage")) {
            throw new IllegalArgumentException("Storage lifecycle requires an explicit bounded fabric");
        }
        return new StorageLifecycleEvidence(capture, storeBackedInputs);
    }

    public FileLifecycleEvidence getCapture() {
        return capture;
    }

    public boolean isStoreBackedInputs() {
        return storeBackedInputs;
    }
}
