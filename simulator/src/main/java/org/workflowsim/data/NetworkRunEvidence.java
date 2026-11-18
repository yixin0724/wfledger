package org.workflowsim.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NetworkRunEvidence {
    private final NetworkEvidenceConfig config;
    private final DataMovementModel.Kind modelKind;
    private final TransferTraceSnapshot traceSnapshot;
    private final List<NetworkFlowBinding> bindings;
    private final boolean engineCreated;
    private final NetworkInputDemandSnapshot inputDemand;

    private NetworkRunEvidence(
            NetworkEvidenceConfig config,
            DataMovementModel.Kind modelKind,
            TransferTraceSnapshot trace,
            List<NetworkFlowBinding> bindings,
            boolean engineCreated,
            NetworkInputDemandSnapshot inputDemand) {
        this.config = config;
        this.modelKind = modelKind;
        this.traceSnapshot = trace;
        this.bindings = bindings.isEmpty()
                ? Collections.<NetworkFlowBinding>emptyList()
                : Collections.unmodifiableList(new ArrayList<NetworkFlowBinding>(bindings));
        this.engineCreated = engineCreated;
        this.inputDemand = inputDemand;
    }

    public static NetworkRunEvidence empty(NetworkEvidenceConfig config, DataMovementModel.Kind modelKind) {
        requireEnabledContention(config, modelKind);
        return new NetworkRunEvidence(
                config,
                modelKind,
                new TransferTraceSnapshot(
                        TransferTraceSnapshot.Status.COMPLETE, Collections.<TransferTraceEvent>emptyList(), 0L, 0.0),
                Collections.<NetworkFlowBinding>emptyList(),
                false,
                NetworkInputDemandSnapshot.empty());
    }

    public static NetworkRunEvidence capture(
            NetworkEvidenceConfig config,
            DataMovementModel.Kind modelKind,
            TransferTraceSnapshot trace,
            List<NetworkFlowBinding> candidates) {
        return capture(config, modelKind, trace, candidates, null);
    }

    public static NetworkRunEvidence capture(
            NetworkEvidenceConfig config,
            DataMovementModel.Kind modelKind,
            TransferTraceSnapshot trace,
            List<NetworkFlowBinding> candidates,
            NetworkInputDemandSnapshot inputDemand) {
        requireEnabledContention(config, modelKind);
        if (trace == null || trace.getStatus() == TransferTraceSnapshot.Status.DISABLED || candidates == null) {
            throw new IllegalArgumentException(
                    "Enabled network evidence requires an enabled trace and candidate bindings");
        }
        int budget = config.getMaxTraceRecords();
        if (trace.getEvents().size() > budget || candidates.size() > budget) {
            throw new IllegalArgumentException("Network trace or candidate binding count exceeds the record budget");
        }
        Map<Long, NetworkFlowBinding> byOrdinal = indexBoundedBindings(candidates, budget);
        List<NetworkFlowBinding> retained = new ArrayList<NetworkFlowBinding>();
        for (TransferTraceEvent event : trace.getEvents()) {
            if (event.getType() != TransferTraceEvent.Type.START) {
                continue;
            }
            NetworkFlowBinding binding = byOrdinal.remove(event.getAdmissionOrdinal());
            if (binding == null
                    || binding.getExternalTransferId() != event.getTransferId().longValue()
                    || !binding.getOccupiedResources().equals(event.getStart().getOccupiedResources())) {
                throw new IllegalStateException("Retained START admission " + event.getAdmissionOrdinal()
                        + " has no matching network flow binding (external ID and ordered resources must match)");
            }
            retained.add(binding);
        }
        if (trace.getStatus() == TransferTraceSnapshot.Status.COMPLETE && !byOrdinal.isEmpty()) {
            throw new IllegalStateException(
                    "Complete network trace has candidate bindings without retained START records");
        }
        return new NetworkRunEvidence(config, modelKind, trace, retained, true, inputDemand);
    }

    private static Map<Long, NetworkFlowBinding> indexBoundedBindings(List<NetworkFlowBinding> candidates, int budget) {
        Map<Long, NetworkFlowBinding> byOrdinal = new LinkedHashMap<Long, NetworkFlowBinding>();
        for (NetworkFlowBinding binding : candidates) {
            if (binding == null || binding.getAdmissionOrdinal() > budget) {
                throw new IllegalArgumentException("Network binding lies outside the bounded admission prefix");
            }
            if (byOrdinal.put(binding.getAdmissionOrdinal(), binding) != null) {
                throw new IllegalArgumentException(
                        "Duplicate network binding admission ordinal: " + binding.getAdmissionOrdinal());
            }
        }
        return byOrdinal;
    }

    private static void requireEnabledContention(NetworkEvidenceConfig config, DataMovementModel.Kind kind) {
        if (config == null || !config.isEnabled()) {
            throw new IllegalArgumentException("Network run evidence requires an enabled capture option");
        }
        if (kind != DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1
                && kind != DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1) {
            throw new IllegalArgumentException(
                    "Grouped network evidence requires an existing fluid contention model: " + kind);
        }
    }

    public NetworkEvidenceConfig getConfig() {
        return config;
    }

    public DataMovementModel.Kind getModelKind() {
        return modelKind;
    }

    public TransferTraceSnapshot getTraceSnapshot() {
        return traceSnapshot;
    }

    public List<NetworkFlowBinding> getBindings() {
        return bindings;
    }

    public boolean isEngineCreated() {
        return engineCreated;
    }

    public NetworkInputDemandSnapshot getInputDemand() {
        return inputDemand;
    }
}
