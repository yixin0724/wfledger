package org.workflowsim.data.v2;

import java.util.Set;

import org.workflowsim.data.NetworkLedgerCodec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class FileLifecycleCodec {

    public static final String SCHEMA = "workflowsim-file-lifecycle-v2";

    public static final String ARTIFACT_ROLE = "file-lifecycle";

    static final String SHARED_KIND = "COHERENT_FILE_DATAFLOW_V2";
    static final String ISOLATED_KIND = "COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2";
    static final String MODE = "FILE_LIFECYCLE_V2";
    static final String SCOPE = "FILE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V2";
    static final String IDENTITY = "SCOPED_RESOLVED_WRITE_ONCE_FILES_V2";
    static final String RELEASE = "DEPENDENCY_READY_AT_OBSERVATION_V2";
    static final String VISIBILITY = "PER_FILE_SETTLEMENT_OBSERVATION_V2";
    static final String SELECTION = "VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2";
    static final String SOURCE_ACCESS = "UNBOUNDED_OFF_FABRIC_SOURCE_INPUT_V2";
    private static final Gson JSON =
            new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private FileLifecycleCodec() {}

    public static final class Decoded {
        private final FileLifecycleValidator.Result result;
        private final JsonObject document;

        private Decoded(FileLifecycleValidator.Result result, JsonObject document) {
            this.result = result;
            this.document = document.deepCopy();
        }

        public FileLifecycleEvidence getEvidence() {
            return result.evidence;
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
    }

    public static JsonObject document(FileLifecycleEvidence evidence) {
        return document(evidence, LifecycleContract.FILE_V2, false);
    }

    static JsonObject document(FileLifecycleEvidence evidence, LifecycleContract contract, boolean storeBackedInputs) {
        if (evidence == null) {
            throw new IllegalArgumentException("File lifecycle evidence is required");
        }
        if (contract == null || (!contract.storage && storeBackedInputs)) {
            throw new IllegalArgumentException("Invalid lifecycle contract");
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema", contract.schema);
        root.addProperty("modelKind", evidence.isShared() ? contract.sharedKind : contract.isolatedKind);
        JsonObject recording = new JsonObject();
        recording.addProperty("mode", contract.mode);
        recording.addProperty("maxTraceRecords", evidence.getMaxRecords());
        root.add("recording", recording);
        root.addProperty("certificateScope", contract.scope);
        JsonObject policies = new JsonObject();
        policies.addProperty("fileIdentity", IDENTITY);
        policies.addProperty("release", RELEASE);
        policies.addProperty("visibility", VISIBILITY);
        policies.addProperty("selection", contract.selection(storeBackedInputs));
        policies.addProperty("sourceAccess", contract.sourceAccess);
        policies.addProperty("sharing", evidence.isShared() ? "SHARED_MAX_MIN" : "ISOLATED_PATH_BOTTLENECK");
        if (contract.storage) {
            policies.addProperty(
                    "inputAccess",
                    storeBackedInputs
                            ? StorageLifecycleCodec.STORE_INPUT_ACCESS
                            : StorageLifecycleCodec.LOCAL_INPUT_ACCESS);
            policies.addProperty("outputCommit", StorageLifecycleCodec.OUTPUT_COMMIT);
        }
        root.add("policies", policies);
        JsonObject capture = new JsonObject();
        capture.addProperty("status", evidence.getStatus().name());
        capture.addProperty("observedThrough", evidence.getObservedThrough());
        capture.addProperty("retainedRecords", evidence.getEvents().size());
        capture.addProperty("droppedRecords", evidence.getDroppedRecords());
        root.add("capture", capture);
        root.add("filePlan", evidence.getFilePlan());
        root.add("fabric", evidence.getFabric());
        JsonArray events = new JsonArray();
        for (FileLifecycleEvent event : evidence.getEvents()) {
            JsonObject row = new JsonObject();
            row.addProperty("sequence", event.getSequence());
            row.addProperty("observedTime", event.getObservedTime());
            row.addProperty("type", event.getType().name());
            row.add("payload", event.getPayload());
            events.add(row);
        }
        root.add("events", events);
        FileLifecycleValidator.validate(root, contract);
        return root;
    }

    public static String encode(FileLifecycleEvidence evidence) {
        return JSON.toJson(document(evidence));
    }

    public static Decoded decode(String json) {
        return decodeDocument(NetworkLedgerCodec.parseDocument(json));
    }

    public static void validatePlanDocument(JsonObject plan) {
        FileLifecycleValidator.validatePlanDocument(plan);
    }

    public static Decoded decodeDocument(JsonObject document) {
        if (document == null) {
            throw new IllegalArgumentException("File lifecycle document is required");
        }
        return new Decoded(FileLifecycleValidator.validate(document), document);
    }
}
