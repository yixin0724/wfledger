package org.workflowsim.data.v2;

import java.util.Set;

import org.workflowsim.data.NetworkLedgerCodec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public final class StorageLifecycleCodec {

    public static final String SCHEMA = "workflowsim-storage-lifecycle-v3";

    public static final String ARTIFACT_ROLE = "storage-lifecycle";

    static final String SHARED_KIND = "COHERENT_STORAGE_DATAFLOW_V3";
    static final String ISOLATED_KIND = "COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3";
    static final String MODE = "FILE_STORAGE_LIFECYCLE_V3";
    static final String SCOPE = "STORAGE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V3";
    static final String SOURCE_ACCESS = "BOUNDED_STORE_READ_WRITE_NIC_HOST_ATTACHMENT_V3";
    static final String LOCAL_INPUT_ACCESS = "LOCAL_VISIBLE_REPLICA_V3";
    static final String STORE_INPUT_ACCESS = "COMMITTED_STORE_THEN_VM_READ_CACHE_V3";
    static final String OUTPUT_COMMIT = "ALL_SUCCESSFUL_OUTPUTS_TO_SOURCE_V3";
    private static final Gson JSON =
            new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private StorageLifecycleCodec() {}

    public static final class Decoded {
        private final FileLifecycleValidator.Result result;
        private final StorageLifecycleEvidence evidence;
        private final JsonObject document;

        private Decoded(FileLifecycleValidator.Result result, JsonObject document) {
            this.result = result;
            evidence = StorageLifecycleEvidence.capture(result.evidence, result.storeBackedInputs);
            this.document = document.deepCopy();
        }

        public StorageLifecycleEvidence getEvidence() {
            return evidence;
        }

        public JsonObject getDocument() {
            return document.deepCopy();
        }

        public boolean isQuiescent() {
            return result.quiescent;
        }

        public int getRequestedJobCount() {
            return result.requestedJobs;
        }

        public int getCompletedJobCount() {
            return result.completedJobs;
        }

        public long getCopyCount() {
            return result.copies;
        }

        public long getCompletedCopyCount() {
            return result.completedCopies;
        }

        public int getActiveCopyCount() {
            return result.activeCopies;
        }

        public Set<Integer> getSuccessfullyCompletedTaskIds() {
            return result.successfulTasks;
        }

        public int getPendingOutputFileCount() {
            return result.pendingOutputFiles;
        }

        public int getWaitingStoreInputCount() {
            return result.waitingStoreInputs;
        }
    }

    public static JsonObject document(StorageLifecycleEvidence evidence) {
        if (evidence == null) {
            throw new IllegalArgumentException("Storage lifecycle evidence is required");
        }
        return FileLifecycleCodec.document(
                evidence.getCapture(), LifecycleContract.STORAGE_V3, evidence.isStoreBackedInputs());
    }

    public static String encode(StorageLifecycleEvidence evidence) {
        return JSON.toJson(document(evidence));
    }

    public static Decoded decode(String json) {
        return decodeDocument(NetworkLedgerCodec.parseDocument(json));
    }

    public static Decoded decodeDocument(JsonObject document) {
        if (document == null) {
            throw new IllegalArgumentException("Storage lifecycle document is required");
        }
        return new Decoded(FileLifecycleValidator.validate(document, LifecycleContract.STORAGE_V3), document);
    }

    public static void validatePlanDocument(JsonObject plan) {
        FileLifecycleCodec.validatePlanDocument(plan);
    }
}
