package org.workflowsim.data.v2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.workflowsim.network.FatTreeTopology;

public final class DataTransferFabric {

    public static final class Route {
        private final DataLocation source, destination;
        private final List<String> resources;
        private final double rate;
        private final boolean sourceBypass;

        private Route(
                DataLocation source, DataLocation destination, List<String> path, Map<String, Double> capacities) {
            this(source, destination, path, capacities, source.getKind() == DataLocation.Kind.SOURCE);
        }

        private Route(
                DataLocation source,
                DataLocation destination,
                List<String> path,
                Map<String, Double> capacities,
                boolean bypass) {
            this.source = source;
            this.destination = destination;
            sourceBypass = bypass;
            resources = Collections.unmodifiableList(new ArrayList<>(path));
            Map<String, Integer> multiplicities = new LinkedHashMap<>();
            for (String key : resources) {
                if (!capacities.containsKey(key)) {
                    throw bad("Unknown physical resource on selected route: " + key);
                }
                Integer old = multiplicities.get(key);
                multiplicities.put(key, old == null ? 1 : Math.addExact(old, 1));
            }
            if (resources.isEmpty()) {
                throw bad("A positive remote input route must constrain its destination");
            }
            double bottleneck = Double.MAX_VALUE;
            for (Map.Entry<String, Integer> entry : multiplicities.entrySet()) {
                bottleneck = Math.min(bottleneck, capacities.get(entry.getKey()) / entry.getValue());
            }
            requireCapacity(bottleneck);
            rate = bottleneck;
        }

        public DataLocation getSource() {
            return source;
        }

        public DataLocation getDestination() {
            return destination;
        }

        public List<String> getResources() {
            return resources;
        }

        public double getStandaloneRateBytesPerSecond() {
            return rate;
        }

        public boolean isUnboundedOffFabricSource() {
            return sourceBypass;
        }
    }

    private final Set<DataLocation> locations;
    private final Map<String, Double> capacities;
    private final Map<Integer, Integer> hosts;
    private final FatTreeTopology topology;
    private final DataflowStorageSpec sourceStorage;
    public static final String STORE_READ = "STORE:source:READ",
            STORE_WRITE = "STORE:source:WRITE",
            STORE_NIC = "STORE:source:NIC";

    private DataTransferFabric(
            Map<Integer, Double> endpointBytes,
            Map<Integer, Integer> actualHosts,
            FatTreeTopology topology,
            Collection<String> sources) {
        if (endpointBytes == null || sources == null) {
            throw bad("VM capacities and explicit SOURCE locations are required");
        }
        Set<DataLocation> known = new TreeSet<>();
        Map<String, Double> values = new TreeMap<>();
        for (Map.Entry<Integer, Double> entry : endpointBytes.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw bad("VM endpoint capacity entries cannot be null");
            }
            DataLocation vm = DataLocation.vm(entry.getKey());
            requireCapacity(entry.getValue());
            known.add(vm);
            values.put(vm.toString(), entry.getValue());
        }
        for (String id : sources) {
            if (!known.add(DataLocation.source(id))) {
                throw bad("Duplicate SOURCE location");
            }
        }
        Map<Integer, Integer> placement = resolveTopologyPlacement(topology, endpointBytes, actualHosts, values);
        locations = Collections.unmodifiableSet(known);
        capacities = Collections.unmodifiableMap(values);
        hosts = Collections.unmodifiableMap(placement);
        this.topology = topology;
        sourceStorage = null;
    }

    private static Map<Integer, Integer> resolveTopologyPlacement(
            FatTreeTopology topology,
            Map<Integer, Double> endpointBytes,
            Map<Integer, Integer> actualHosts,
            Map<String, Double> values) {
        Map<Integer, Integer> placement = new TreeMap<>();
        if (topology != null) {
            if (actualHosts == null || !actualHosts.keySet().equals(endpointBytes.keySet())) {
                throw bad("Actual host mapping must cover exactly every VM endpoint");
            }
            Set<Integer> placed = topology.getHostPlacements().keySet();
            requireCapacity(topology.getLinkBandwidthBytesPerSecond());
            for (Map.Entry<Integer, Integer> entry : actualHosts.entrySet()) {
                Integer host = entry.getValue();
                if (host == null || host < 0 || !placed.contains(host)) {
                    throw bad("VM is not assigned to a known physical host");
                }
                placement.put(entry.getKey(), host);
            }
            for (String link : topology.getLinkIds()) {
                if (values.put(link, topology.getLinkBandwidthBytesPerSecond()) != null) {
                    throw bad("Physical resource keys collide");
                }
            }
        } else if (actualHosts != null) {
            throw bad("Host mapping without a topology is unsupported");
        }
        return placement;
    }

    public static DataTransferFabric endpoints(
            Map<Integer, Double> vmCapacityBytesPerSecond, Collection<String> unboundedSources) {
        return new DataTransferFabric(vmCapacityBytesPerSecond, null, null, unboundedSources);
    }

    public static DataTransferFabric fatTree(
            Map<Integer, Double> vmCapacityBytesPerSecond,
            Map<Integer, Integer> actualVmHosts,
            FatTreeTopology topology,
            Collection<String> unboundedSources) {
        if (topology == null) {
            throw bad("Fat-tree view is required");
        }
        return new DataTransferFabric(vmCapacityBytesPerSecond, actualVmHosts, topology, unboundedSources);
    }

    private DataTransferFabric(
            DataTransferFabric base, Map<Integer, Integer> actualHosts, DataflowStorageSpec storage) {
        if (storage == null || actualHosts == null) {
            throw bad("Bounded store and actual VM hosts are required");
        }
        Set<Integer> vmIds = new TreeSet<>();
        for (DataLocation location : base.locations) {
            if (location.getKind() == DataLocation.Kind.VM) {
                vmIds.add(location.getVmId());
            }
        }
        if (!vmIds.equals(actualHosts.keySet())) {
            throw bad("Storage fabric needs every actual VM host");
        }
        Map<Integer, Integer> placement = new TreeMap<>();
        for (Map.Entry<Integer, Integer> entry : actualHosts.entrySet()) {
            if (entry.getValue() == null || entry.getValue() < 0) {
                throw bad("Invalid actual VM host");
            }
            placement.put(entry.getKey(), entry.getValue());
        }
        if (base.topology != null && !base.topology.getHostPlacements().containsKey(storage.getAttachmentHostId())) {
            throw bad("Store attachment host is not placed in topology");
        }
        Map<String, Double> resources = new TreeMap<>(base.capacities);
        resources.put(STORE_READ, storage.getReadCapacityBytesPerSecond());
        resources.put(STORE_WRITE, storage.getWriteCapacityBytesPerSecond());
        resources.put(STORE_NIC, storage.getNetworkCapacityBytesPerSecond());
        locations = base.locations;
        topology = base.topology;
        hosts = Collections.unmodifiableMap(placement);
        capacities = Collections.unmodifiableMap(resources);
        sourceStorage = storage;
    }

    public static DataTransferFabric withStorage(
            Map<Integer, Double> vmCapacityBytesPerSecond,
            Map<Integer, Integer> actualVmHosts,
            FatTreeTopology topology,
            DataflowStorageSpec storage) {
        DataTransferFabric base = new DataTransferFabric(
                vmCapacityBytesPerSecond,
                topology == null ? null : actualVmHosts,
                topology,
                Collections.singletonList("source"));
        return new DataTransferFabric(base, actualVmHosts, storage);
    }

    public DataflowStorageSpec getSourceStorage() {
        return sourceStorage;
    }

    public Set<DataLocation> getLocations() {
        return locations;
    }

    public Map<String, Double> getResourceCapacities() {
        return capacities;
    }

    public Map<Integer, Integer> getActualVmHosts() {
        return hosts;
    }

    public FatTreeTopology getTopology() {
        return topology;
    }

    public Route route(DataLocation source, DataLocation destination) {
        requireLocation(source);
        requireLocation(destination);
        if (sourceStorage != null) {
            return storageRoute(source, destination);
        }
        if (destination.getKind() != DataLocation.Kind.VM || source.equals(destination)) {
            throw bad("NF003 input routes require a remote VM destination; local/output paths are separate cases");
        }
        List<String> resources = new ArrayList<>();
        if (source.getKind() == DataLocation.Kind.VM) {
            resources.add(source.toString());
            if (topology != null) {
                resources.addAll(topology.route(hosts.get(source.getVmId()), hosts.get(destination.getVmId())));
            }
        }
        resources.add(destination.toString());
        return new Route(source, destination, resources, capacities);
    }

    private Route storageRoute(DataLocation source, DataLocation destination) {
        if (source.equals(destination)) {
            throw bad("A local object has no positive storage route");
        }
        boolean reading = source.getKind() == DataLocation.Kind.SOURCE,
                writing = destination.getKind() == DataLocation.Kind.SOURCE;
        if (reading && writing) {
            throw bad("Store-to-store routing is unsupported");
        }
        List<String> resources = new ArrayList<>();
        if (reading) {
            resources.add(STORE_READ);
            resources.add(STORE_NIC);
        } else {
            resources.add(source.toString());
        }
        int from = reading ? sourceStorage.getAttachmentHostId() : hosts.get(source.getVmId());
        int to = writing ? sourceStorage.getAttachmentHostId() : hosts.get(destination.getVmId());
        if (topology != null) {
            resources.addAll(topology.route(from, to));
        }
        if (writing) {
            resources.add(STORE_NIC);
            resources.add(STORE_WRITE);
        } else {
            resources.add(destination.toString());
        }
        return new Route(source, destination, resources, capacities, false);
    }

    void requireLocation(DataLocation location) {
        if (location == null || !locations.contains(location)) {
            throw bad("Unknown fabric location: " + location);
        }
    }

    private static void requireCapacity(double capacity) {
        if (!Double.isFinite(capacity) || capacity < Double.MIN_NORMAL) {
            throw bad("V2 fabric capacities and standalone rates must be positive normal finite binary64 values");
        }
    }

    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }
}
