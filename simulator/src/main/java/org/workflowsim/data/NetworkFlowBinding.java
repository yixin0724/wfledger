package org.workflowsim.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class NetworkFlowBinding {

    public enum GroupKind {
        PARENT_GROUP_V1,

        EXTERNAL_GROUP_V1
    }

    public enum SourceScope {
        MODELED_CONSTRAINED_SOURCE
    }

    private final long externalTransferId;
    private final long admissionOrdinal;
    private final int jobId;
    private final List<Integer> taskIds;
    private final Integer parentJobId;
    private final GroupKind groupKind;
    private final SourceScope sourceScope;
    private final String sourceEndpoint;
    private final String destinationEndpoint;
    private final List<String> occupiedResources;

    private NetworkFlowBinding(
            long externalTransferId,
            long admissionOrdinal,
            int jobId,
            List<Integer> taskIds,
            Integer parentJobId,
            GroupKind groupKind,
            String sourceEndpoint,
            String destinationEndpoint,
            List<String> resources) {
        this.externalTransferId = externalTransferId;
        this.admissionOrdinal = admissionOrdinal;
        this.jobId = jobId;
        this.taskIds = Collections.unmodifiableList(new ArrayList<Integer>(taskIds));
        this.parentJobId = parentJobId;
        this.groupKind = groupKind;
        this.sourceScope = SourceScope.MODELED_CONSTRAINED_SOURCE;
        this.sourceEndpoint = sourceEndpoint;
        this.destinationEndpoint = destinationEndpoint;
        this.occupiedResources = Collections.unmodifiableList(new ArrayList<String>(resources));
    }

    public static NetworkFlowBinding of(
            long externalTransferId,
            long admissionOrdinal,
            int jobId,
            List<Integer> taskIds,
            Integer parentJobId,
            GroupKind groupKind,
            String sourceEndpoint,
            String destinationEndpoint,
            List<String> resources) {
        if (admissionOrdinal <= 0) {
            throw new IllegalArgumentException("Admission ordinal must be positive");
        }
        if (taskIds == null
                || groupKind == null
                || sourceEndpoint == null
                || destinationEndpoint == null
                || resources == null) {
            throw new IllegalArgumentException("Network flow binding contains a required null value");
        }
        if ((groupKind == GroupKind.PARENT_GROUP_V1 && parentJobId == null)
                || (groupKind == GroupKind.EXTERNAL_GROUP_V1 && parentJobId != null)) {
            throw new IllegalArgumentException("Parent Job ID must match the network group kind");
        }
        for (Integer taskId : taskIds) {
            if (taskId == null) {
                throw new IllegalArgumentException("Task IDs cannot contain null");
            }
        }
        for (String resource : resources) {
            if (resource == null) {
                throw new IllegalArgumentException("Occupied resources cannot contain null");
            }
        }
        return new NetworkFlowBinding(
                externalTransferId,
                admissionOrdinal,
                jobId,
                taskIds,
                parentJobId,
                groupKind,
                sourceEndpoint,
                destinationEndpoint,
                resources);
    }

    public long getExternalTransferId() {
        return externalTransferId;
    }

    public long getAdmissionOrdinal() {
        return admissionOrdinal;
    }

    public int getJobId() {
        return jobId;
    }

    public List<Integer> getTaskIds() {
        return taskIds;
    }

    public Integer getParentJobId() {
        return parentJobId;
    }

    public GroupKind getGroupKind() {
        return groupKind;
    }

    public SourceScope getSourceScope() {
        return sourceScope;
    }

    public String getSourceEndpoint() {
        return sourceEndpoint;
    }

    public String getDestinationEndpoint() {
        return destinationEndpoint;
    }

    public List<String> getOccupiedResources() {
        return occupiedResources;
    }
}
