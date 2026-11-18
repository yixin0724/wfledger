package org.workflowsim.data.v2;

import java.util.List;

public final class ReplicaTransferSelector {

    public static final String POLICY = "VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2";

    private ReplicaTransferSelector() {}

    public enum Outcome {
        WAITING_FOR_SOURCE,
        LOCAL_PRESENT,
        ZERO_BYTE_REFERENCE,
        NETWORK_COPY
    }

    public static final class Decision {
        private final DataflowFilePlan.FileDefinition file;
        private final DataLocation destination;
        private final double selectedAt;
        private final Outcome outcome;
        private final DataReplicaState.Replica source;
        private final DataTransferFabric.Route route;
        private final Double isolatedSeconds, isolatedCompletionTime;
        private final String policy;

        private Decision(
                DataflowFilePlan.FileDefinition file,
                DataLocation destination,
                double at,
                Outcome outcome,
                DataReplicaState.Replica source,
                DataTransferFabric.Route route,
                Double seconds,
                Double finish) {
            this(file, destination, at, outcome, source, route, seconds, finish, POLICY);
        }

        private Decision(
                DataflowFilePlan.FileDefinition file,
                DataLocation destination,
                double at,
                Outcome outcome,
                DataReplicaState.Replica source,
                DataTransferFabric.Route route,
                Double seconds,
                Double finish,
                String policy) {
            this.file = file;
            this.destination = destination;
            selectedAt = at;
            this.outcome = outcome;
            this.source = source;
            this.route = route;
            isolatedSeconds = seconds;
            isolatedCompletionTime = finish;
            this.policy = policy;
        }

        public String getPolicy() {
            return policy;
        }

        public DataflowFilePlan.FileDefinition getFile() {
            return file;
        }

        public DataLocation getDestination() {
            return destination;
        }

        public double getSelectedAt() {
            return selectedAt;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        public DataReplicaState.Replica getSourceReplica() {
            return source;
        }

        public DataTransferFabric.Route getRoute() {
            return route;
        }

        public Double getIsolatedSeconds() {
            return isolatedSeconds;
        }

        public Double getIsolatedCompletionTime() {
            return isolatedCompletionTime;
        }
    }

    public static Decision select(
            DataReplicaState.Snapshot snapshot,
            DataTransferFabric fabric,
            DataflowFilePlan.FileId fileId,
            DataLocation destination) {
        requireSelectableInputs(snapshot, fabric, destination);
        DataflowFilePlan.FileDefinition file = snapshot.getFile(fileId);
        double now = snapshot.getObservedThrough();
        DataReplicaState.Replica local = snapshot.getReplica(fileId, destination);
        if (local != null) {
            return new Decision(file, destination, now, Outcome.LOCAL_PRESENT, local, null, 0.0, now);
        }
        List<DataReplicaState.Replica> candidates = snapshot.getReplicas(fileId);
        if (candidates.isEmpty()) {
            return new Decision(file, destination, now, Outcome.WAITING_FOR_SOURCE, null, null, null, null);
        }
        DataReplicaState.Replica selected = null;
        DataTransferFabric.Route selectedRoute = null;
        for (DataReplicaState.Replica candidate : candidates) {
            fabric.requireLocation(candidate.getLocation());
            if (candidate.getVisibleAt() > now) {
                throw bad("Snapshot contains a future replica");
            }
            if (file.getBytes() == 0) {
                if (selected == null || candidate.getLocation().compareTo(selected.getLocation()) < 0) {
                    selected = candidate;
                }
                continue;
            }
            DataTransferFabric.Route route = fabric.route(candidate.getLocation(), destination);
            if (selectedRoute == null
                    || route.getStandaloneRateBytesPerSecond() > selectedRoute.getStandaloneRateBytesPerSecond()
                    || (route.getStandaloneRateBytesPerSecond() == selectedRoute.getStandaloneRateBytesPerSecond()
                            && candidate.getLocation().compareTo(selected.getLocation()) < 0)) {
                selected = candidate;
                selectedRoute = route;
            }
        }
        if (file.getBytes() == 0) {
            return new Decision(file, destination, now, Outcome.ZERO_BYTE_REFERENCE, selected, null, 0.0, now);
        }
        return positiveCopyDecision(file, destination, now, selected, selectedRoute);
    }

    private static void requireSelectableInputs(
            DataReplicaState.Snapshot snapshot, DataTransferFabric fabric, DataLocation destination) {
        if (snapshot == null || fabric == null) {
            throw bad("Visibility snapshot and fabric are required");
        }
        fabric.requireLocation(destination);
        if (destination.getKind() != DataLocation.Kind.VM) {
            throw bad("NF003 input destination must be a VM");
        }
    }

    private static Decision positiveCopyDecision(
            DataflowFilePlan.FileDefinition file,
            DataLocation destination,
            double now,
            DataReplicaState.Replica selected,
            DataTransferFabric.Route selectedRoute) {
        double seconds = file.getBytes() / selectedRoute.getStandaloneRateBytesPerSecond();
        double finish = now + seconds;
        if (!Double.isFinite(seconds) || seconds <= 0 || !Double.isFinite(finish) || finish <= now) {
            throw bad("Selected positive transfer duration/absolute finish is not representable in the supported"
                    + "binary64 domain");
        }
        return new Decision(file, destination, now, Outcome.NETWORK_COPY, selected, selectedRoute, seconds, finish);
    }

    public static Decision selectStoreBacked(
            DataReplicaState.Snapshot snapshot,
            DataTransferFabric fabric,
            DataflowFilePlan.FileId fileId,
            DataLocation destination) {
        if (snapshot == null || fabric == null || fabric.getSourceStorage() == null) {
            throw bad("Store-backed selection needs bounded storage");
        }
        fabric.requireLocation(destination);
        if (destination.getKind() != DataLocation.Kind.VM) {
            throw bad("Input destination must be VM");
        }
        DataflowFilePlan.FileDefinition file = snapshot.getFile(fileId);
        double now = snapshot.getObservedThrough();
        DataReplicaState.Replica store = snapshot.getReplica(fileId, CoherentDataflowRuntime.SOURCE);
        String policy = "COMMITTED_STORE_THEN_VM_READ_CACHE_V3";
        if (store == null) {
            return new Decision(file, destination, now, Outcome.WAITING_FOR_SOURCE, null, null, null, null, policy);
        }
        DataReplicaState.Replica local = snapshot.getReplica(fileId, destination);
        if (local != null) {
            return new Decision(file, destination, now, Outcome.LOCAL_PRESENT, local, null, 0.0, now, policy);
        }
        return fixedSource(store, fabric, destination, now, policy);
    }

    public static Decision materializeToStore(DataReplicaState.Replica source, DataTransferFabric fabric, double now) {
        if (source == null
                || source.getFile().isExternal()
                || source.getLocation().getKind() != DataLocation.Kind.VM
                || fabric == null
                || fabric.getSourceStorage() == null) {
            throw bad("Output materialization needs a produced VM replica and bounded store");
        }
        return fixedSource(source, fabric, CoherentDataflowRuntime.SOURCE, now, "SUCCESSFUL_OUTPUT_MATERIALIZATION_V3");
    }

    private static Decision fixedSource(
            DataReplicaState.Replica source,
            DataTransferFabric fabric,
            DataLocation destination,
            double now,
            String policy) {
        fabric.requireLocation(source.getLocation());
        fabric.requireLocation(destination);
        if (!Double.isFinite(now) || now < source.getVisibleAt()) {
            throw bad("Source is not visible at the requested observation");
        }
        DataflowFilePlan.FileDefinition file = source.getFile();
        if (file.getBytes() == 0) {
            return new Decision(file, destination, now, Outcome.ZERO_BYTE_REFERENCE, source, null, 0.0, now, policy);
        }
        DataTransferFabric.Route route = fabric.route(source.getLocation(), destination);
        double seconds = file.getBytes() / route.getStandaloneRateBytesPerSecond(), finish = now + seconds;
        if (!Double.isFinite(seconds) || seconds <= 0 || !Double.isFinite(finish) || finish <= now) {
            throw bad("Unrepresentable storage transfer time");
        }
        return new Decision(file, destination, now, Outcome.NETWORK_COPY, source, route, seconds, finish, policy);
    }

    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }
}
