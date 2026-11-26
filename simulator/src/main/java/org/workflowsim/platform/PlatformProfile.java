package org.workflowsim.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

public final class PlatformProfile {

    public enum CloudletSchedulerMode {
        SPACE_SHARED,

        TIME_SHARED
    }

    private final String name;
    private final List<HostSpec> hosts;
    private final List<VmSpec> vms;
    private final java.util.Map<Integer, Integer> pinnedVmHostIds;
    private final java.util.Map<Integer, Integer> vmHostAssignments;
    private final StorageSpec storage;
    private final CostSpec costs;
    private final org.workflowsim.network.NetworkTopologySpec networkTopology;
    private final transient org.workflowsim.data.v2.DataflowStorageSpec sourceStorage;
    private final List<CarbonWindow> carbonWindows;

    private PlatformProfile(Builder builder) {
        this.name = builder.name;
        this.hosts = Collections.unmodifiableList(new ArrayList<>(builder.hosts));
        this.vms = Collections.unmodifiableList(new ArrayList<>(builder.vms));
        this.pinnedVmHostIds =
                Collections.unmodifiableMap(new LinkedHashMap<Integer, Integer>(builder.pinnedVmHostIds));
        this.vmHostAssignments =
                Collections.unmodifiableMap(new LinkedHashMap<Integer, Integer>(builder.vmHostAssignments));
        this.storage = builder.storage;
        this.costs = builder.costs;
        this.networkTopology = builder.networkTopology;
        this.sourceStorage = builder.sourceStorage;
        this.carbonWindows = builder.carbonWindows.isEmpty()
                ? Collections.<CarbonWindow>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(builder.carbonWindows));
    }

    public List<CarbonWindow> getCarbonWindows() {
        return carbonWindows;
    }

    public static final class CarbonWindow {
        private final double startSeconds;
        private final double endSeconds;
        private final double greenWatts;

        public CarbonWindow(double startSeconds, double endSeconds, double greenWatts) {
            if (!(startSeconds >= 0.0)
                    || !Double.isFinite(startSeconds)
                    || !(endSeconds > startSeconds)
                    || !Double.isFinite(endSeconds)
                    || !(greenWatts >= 0.0)
                    || !Double.isFinite(greenWatts)) {
                throw new IllegalArgumentException("Invalid carbon window: start=" + startSeconds + " end=" + endSeconds
                        + " greenWatts=" + greenWatts);
            }
            this.startSeconds = startSeconds;
            this.endSeconds = endSeconds;
            this.greenWatts = greenWatts;
        }

        public double getStartSeconds() {
            return startSeconds;
        }

        public double getEndSeconds() {
            return endSeconds;
        }

        public double getGreenWatts() {
            return greenWatts;
        }
    }

    public static final class FrequencyTier {
        private final double mips;
        private final double idlePowerWatts;
        private final double maxPowerWatts;

        public FrequencyTier(double mips, double idlePowerWatts, double maxPowerWatts) {
            if (!(mips > 0.0)
                    || !Double.isFinite(mips)
                    || !(idlePowerWatts >= 0.0)
                    || !Double.isFinite(idlePowerWatts)
                    || maxPowerWatts < idlePowerWatts
                    || !Double.isFinite(maxPowerWatts)) {
                throw new IllegalArgumentException("Invalid frequency tier: mips=" + mips
                        + " idle=" + idlePowerWatts + " max=" + maxPowerWatts
                        + " (required: mips>0, idle≥0, max≥idle, all finite)");
            }
            this.mips = mips;
            this.idlePowerWatts = idlePowerWatts;
            this.maxPowerWatts = maxPowerWatts;
        }

        public double getMips() {
            return mips;
        }

        public double getIdlePowerWatts() {
            return idlePowerWatts;
        }

        public double getMaxPowerWatts() {
            return maxPowerWatts;
        }
    }

    public String getName() {
        return name;
    }

    public List<HostSpec> getHosts() {
        return hosts;
    }

    public List<VmSpec> getVms() {
        return vms;
    }

    public java.util.Map<Integer, Integer> getPinnedVmHostIds() {
        return pinnedVmHostIds;
    }

    public java.util.Map<Integer, Integer> getVmHostAssignments() {
        return vmHostAssignments;
    }

    public StorageSpec getStorage() {
        return storage;
    }

    public org.workflowsim.data.v2.DataflowStorageSpec getSourceStorage() {
        return sourceStorage;
    }

    public CostSpec getCosts() {
        return costs;
    }

    public org.workflowsim.network.NetworkTopologySpec getNetworkTopology() {
        return networkTopology;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {

        private final String name;
        private final List<HostSpec> hosts = new ArrayList<>();
        private final List<VmSpec> vms = new ArrayList<>();
        private final List<CarbonWindow> carbonWindows = new ArrayList<>();
        private final java.util.Map<Integer, Integer> pinnedVmHostIds = new LinkedHashMap<Integer, Integer>();
        private java.util.Map<Integer, Integer> vmHostAssignments = Collections.emptyMap();
        private StorageSpec storage = new StorageSpec(1_000_000_000_000L, 15);
        private CostSpec costs = new CostSpec(3.0, 0.05, 0.1, 0.1);
        private org.workflowsim.network.NetworkTopologySpec networkTopology;
        private org.workflowsim.data.v2.DataflowStorageSpec sourceStorage;

        public Builder sourceStorage(org.workflowsim.data.v2.DataflowStorageSpec value) {
            sourceStorage = requireNonNull(value, "Dataflow source storage");
            return this;
        }

        private Builder(String name) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Platform profile name cannot be empty");
            }
            this.name = name.trim();
        }

        public Builder addHost(HostSpec host) {
            hosts.add(requireNonNull(host, "Host specification"));
            return this;
        }

        public Builder addVm(VmSpec vm) {
            vms.add(requireNonNull(vm, "VM specification"));
            return this;
        }

        public Builder pinVmToHost(int vmId, int hostId) {
            if (vmId < 0 || hostId < 0) {
                throw new IllegalArgumentException("Pinned VM and Host IDs must be non-negative");
            }
            Integer previous = pinnedVmHostIds.put(vmId, hostId);
            if (previous != null && previous.intValue() != hostId) {
                throw new IllegalArgumentException("VM " + vmId + " is already pinned to Host " + previous);
            }
            return this;
        }

        public Builder storage(StorageSpec value) {
            this.storage = requireNonNull(value, "Storage specification");
            return this;
        }

        public Builder costs(CostSpec value) {
            this.costs = requireNonNull(value, "Cost specification");
            return this;
        }

        public Builder carbonWindow(double startSeconds, double endSeconds, double greenWatts) {
            CarbonWindow window = new CarbonWindow(startSeconds, endSeconds, greenWatts);
            if (!carbonWindows.isEmpty()) {
                CarbonWindow last = carbonWindows.get(carbonWindows.size() - 1);
                if (window.getStartSeconds() < last.getEndSeconds()) {
                    throw new IllegalArgumentException("Carbon windows must be sorted and non-overlapping: "
                            + window.getStartSeconds() + " overlaps " + last.getEndSeconds());
                }
            }
            carbonWindows.add(window);
            return this;
        }

        public Builder networkTopology(org.workflowsim.network.NetworkTopologySpec value) {
            this.networkTopology = requireNonNull(value, "Network topology specification");
            return this;
        }

        public PlatformProfile build() {
            if (hosts.isEmpty() || vms.isEmpty()) {
                throw new IllegalArgumentException("Platform profile requires at least one host and one VM");
            }
            validateUniqueHostIds(hosts);
            validateUniqueVmIds(vms);
            if (sourceStorage != null) {
                boolean found = false;
                for (HostSpec host : hosts) {
                    if (host.getId() == sourceStorage.getAttachmentHostId()) {
                        found = true;
                    }
                }
                if (!found) {
                    throw new IllegalArgumentException("Dataflow storage attachment references an undeclared host");
                }
            }
            validatePinnedVmHostIds(hosts, vms, pinnedVmHostIds);
            for (VmSpec vm : vms) {
                if (!canFitOnAnyHost(vm, hosts)) {
                    throw new IllegalArgumentException("VM " + vm.getId() + " cannot fit on any declared host");
                }
            }
            validateAggregateCapacity(hosts, vms);
            vmHostAssignments = resolveVmHostAssignments(hosts, vms, pinnedVmHostIds);
            return new PlatformProfile(this);
        }

        private static void validateUniqueHostIds(List<HostSpec> values) {
            Set<Integer> ids = new HashSet<>();
            for (HostSpec value : values) {
                if (!ids.add(value.getId())) {
                    throw new IllegalArgumentException("Duplicate host ID " + value.getId());
                }
            }
        }

        private static void validateUniqueVmIds(List<VmSpec> values) {
            Set<Integer> ids = new HashSet<>();
            for (VmSpec value : values) {
                if (!ids.add(value.getId())) {
                    throw new IllegalArgumentException("Duplicate VM ID " + value.getId());
                }
            }
        }

        private static boolean canFitOnAnyHost(VmSpec vm, List<HostSpec> hosts) {
            for (HostSpec host : hosts) {
                if (vm.getPes() <= host.getPes()
                        && vm.getMips() <= host.getMipsPerPe()
                        && vm.getRamMb() <= host.getRamMb()
                        && vm.getBandwidth() <= host.getBandwidth()
                        && vm.getImageSizeMb() <= host.getStorageMb()) {
                    return true;
                }
            }
            return false;
        }

        private static void validatePinnedVmHostIds(
                List<HostSpec> hosts, List<VmSpec> vms, java.util.Map<Integer, Integer> pinned) {
            Set<Integer> hostIds = new HashSet<Integer>();
            for (HostSpec host : hosts) {
                hostIds.add(host.getId());
            }
            Set<Integer> vmIds = new HashSet<Integer>();
            for (VmSpec vm : vms) {
                vmIds.add(vm.getId());
            }
            for (java.util.Map.Entry<Integer, Integer> entry : pinned.entrySet()) {
                if (!vmIds.contains(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "Pinned VM " + entry.getKey() + " is not declared in this profile");
                }
                if (!hostIds.contains(entry.getValue())) {
                    throw new IllegalArgumentException(
                            "Pinned Host " + entry.getValue() + " is not declared in this profile");
                }
            }
        }

        private static java.util.Map<Integer, Integer> resolveVmHostAssignments(
                List<HostSpec> hosts, List<VmSpec> vms, java.util.Map<Integer, Integer> pinned) {
            List<PlacementCapacity> capacities = new ArrayList<PlacementCapacity>();
            for (HostSpec host : hosts) {
                capacities.add(new PlacementCapacity(host));
            }
            java.util.Map<Integer, Integer> assignments = new LinkedHashMap<Integer, Integer>();
            for (VmSpec vm : vms) {
                Integer pinnedHostId = pinned.get(vm.getId());
                PlacementCapacity selected =
                        pinnedHostId == null ? selectDefaultHost(capacities, vm) : findHost(capacities, pinnedHostId);
                if (selected == null || !selected.canHost(vm)) {
                    String detail = pinnedHostId == null
                            ? "No deterministic host placement for VM " + vm.getId()
                            : "Pinned VM " + vm.getId() + " cannot be placed on Host " + pinnedHostId;
                    throw new IllegalArgumentException(detail + " under declared resource capacity");
                }
                selected.allocate(vm);
                assignments.put(vm.getId(), selected.hostId);
            }
            return assignments;
        }

        private static PlacementCapacity findHost(List<PlacementCapacity> capacities, int hostId) {
            for (PlacementCapacity capacity : capacities) {
                if (capacity.hostId == hostId) {
                    return capacity;
                }
            }
            return null;
        }

        private static PlacementCapacity selectDefaultHost(List<PlacementCapacity> capacities, VmSpec vm) {
            PlacementCapacity selected = null;
            for (PlacementCapacity candidate : capacities) {
                if (!candidate.canHost(vm)) {
                    continue;
                }
                if (selected == null || candidate.remainingPes > selected.remainingPes) {
                    selected = candidate;
                }
            }
            return selected;
        }

        private static void validateAggregateCapacity(List<HostSpec> hosts, List<VmSpec> vms) {
            long hostPes = 0L;
            long hostRam = 0L;
            long hostBandwidth = 0L;
            long hostStorage = 0L;
            long vmPes = 0L;
            long vmRam = 0L;
            long vmBandwidth = 0L;
            long vmStorage = 0L;
            for (HostSpec host : hosts) {
                hostPes += host.getPes();
                hostRam += host.getRamMb();
                hostBandwidth += host.getBandwidth();
                hostStorage += host.getStorageMb();
            }
            for (VmSpec vm : vms) {
                vmPes += vm.getPes();
                vmRam += vm.getRamMb();
                vmBandwidth += vm.getBandwidth();
                vmStorage += vm.getImageSizeMb();
            }
            if (vmPes > hostPes || vmRam > hostRam || vmBandwidth > hostBandwidth || vmStorage > hostStorage) {
                throw new IllegalArgumentException("Aggregate VM demand exceeds declared host capacity");
            }
        }

        private static <T> T requireNonNull(T value, String name) {
            if (value == null) {
                throw new IllegalArgumentException(name + " cannot be null");
            }
            return value;
        }

        private static final class PlacementCapacity {
            private final int hostId;
            private final double mipsPerPe;
            private int remainingPes;
            private double remainingMips;
            private int remainingRamMb;
            private long remainingBandwidth;
            private long remainingStorageMb;

            private PlacementCapacity(HostSpec host) {
                this.hostId = host.getId();
                this.mipsPerPe = host.getMipsPerPe();
                this.remainingPes = host.getPes();
                this.remainingMips = host.getPes() * host.getMipsPerPe();
                this.remainingRamMb = host.getRamMb();
                this.remainingBandwidth = host.getBandwidth();
                this.remainingStorageMb = host.getStorageMb();
            }

            private boolean canHost(VmSpec vm) {
                return vm.getPes() <= remainingPes
                        && vm.getMips() <= mipsPerPe
                        && vm.getMips() * vm.getPes() <= remainingMips
                        && vm.getRamMb() <= remainingRamMb
                        && vm.getBandwidth() <= remainingBandwidth
                        && vm.getImageSizeMb() <= remainingStorageMb;
            }

            private void allocate(VmSpec vm) {
                remainingPes -= vm.getPes();
                remainingMips -= vm.getMips() * vm.getPes();
                remainingRamMb -= vm.getRamMb();
                remainingBandwidth -= vm.getBandwidth();
                remainingStorageMb -= vm.getImageSizeMb();
            }
        }
    }

    public static final class HostSpec {

        private final int id;
        private final int pes;
        private final double mipsPerPe;
        private final int ramMb;
        private final long bandwidth;
        private final long storageMb;

        public HostSpec(int id, int pes, double mipsPerPe, int ramMb, long bandwidth, long storageMb) {
            if (id < 0 || pes <= 0 || !positiveFinite(mipsPerPe) || ramMb <= 0 || bandwidth <= 0L || storageMb <= 0L) {
                throw new IllegalArgumentException("Invalid host specification for ID " + id);
            }
            this.id = id;
            this.pes = pes;
            this.mipsPerPe = mipsPerPe;
            this.ramMb = ramMb;
            this.bandwidth = bandwidth;
            this.storageMb = storageMb;
        }

        public int getId() {
            return id;
        }

        public int getPes() {
            return pes;
        }

        public double getMipsPerPe() {
            return mipsPerPe;
        }

        public int getRamMb() {
            return ramMb;
        }

        public long getBandwidth() {
            return bandwidth;
        }

        public long getStorageMb() {
            return storageMb;
        }
    }

    public static final class VmSpec {

        private final int id;
        private final double mips;
        private final int pes;
        private final int ramMb;
        private final long bandwidth;
        private final long imageSizeMb;
        private final String vmm;
        private final CloudletSchedulerMode schedulerMode;
        private final CostSpec costs;
        private final double idlePowerWatts;
        private final double maxPowerWatts;
        private final Double carbonIntensityGramsPerKWh;
        private final List<FrequencyTier> frequencyTiers;

        public VmSpec(
                int id,
                double mips,
                int pes,
                int ramMb,
                long bandwidth,
                long imageSizeMb,
                String vmm,
                CloudletSchedulerMode schedulerMode) {
            this(id, mips, pes, ramMb, bandwidth, imageSizeMb, vmm, schedulerMode, null, 0.0, 0.0, null, null);
        }

        public VmSpec(
                int id,
                double mips,
                int pes,
                int ramMb,
                long bandwidth,
                long imageSizeMb,
                String vmm,
                CloudletSchedulerMode schedulerMode,
                CostSpec costs,
                double idlePowerWatts,
                double maxPowerWatts,
                Double carbonIntensityGramsPerKWh) {
            this(
                    id,
                    mips,
                    pes,
                    ramMb,
                    bandwidth,
                    imageSizeMb,
                    vmm,
                    schedulerMode,
                    costs,
                    idlePowerWatts,
                    maxPowerWatts,
                    carbonIntensityGramsPerKWh,
                    null);
        }

        public VmSpec(
                int id,
                double mips,
                int pes,
                int ramMb,
                long bandwidth,
                long imageSizeMb,
                String vmm,
                CloudletSchedulerMode schedulerMode,
                CostSpec costs) {
            this(id, mips, pes, ramMb, bandwidth, imageSizeMb, vmm, schedulerMode, costs, 0.0, 0.0, null, null);
        }

        public VmSpec(
                int id,
                double mips,
                int pes,
                int ramMb,
                long bandwidth,
                long imageSizeMb,
                String vmm,
                CloudletSchedulerMode schedulerMode,
                CostSpec costs,
                double idlePowerWatts,
                double maxPowerWatts) {
            this(
                    id,
                    mips,
                    pes,
                    ramMb,
                    bandwidth,
                    imageSizeMb,
                    vmm,
                    schedulerMode,
                    costs,
                    idlePowerWatts,
                    maxPowerWatts,
                    null,
                    null);
        }

        public VmSpec(
                int id,
                double mips,
                int pes,
                int ramMb,
                long bandwidth,
                long imageSizeMb,
                String vmm,
                CloudletSchedulerMode schedulerMode,
                CostSpec costs,
                double idlePowerWatts,
                double maxPowerWatts,
                Double carbonIntensityGramsPerKWh,
                List<FrequencyTier> frequencyTiers) {

            if (frequencyTiers != null && !frequencyTiers.isEmpty()) {
                if (idlePowerWatts > 0.0 || maxPowerWatts > 0.0) {
                    throw new IllegalArgumentException(
                            "VM " + id + " declares BOTH frequencyTiers and plain idlePowerWatts/"
                                    + "maxPowerWatts — one source of truth required");
                }
                for (int t = 1; t < frequencyTiers.size(); t++) {
                    if (frequencyTiers.get(t).getMips()
                            >= frequencyTiers.get(t - 1).getMips()) {
                        throw new IllegalArgumentException("Frequency tiers on VM " + id
                                + " must be strictly descending by mips: tier " + t + " mips="
                                + frequencyTiers.get(t).getMips());
                    }
                }
            }
            if (carbonIntensityGramsPerKWh != null
                    && (carbonIntensityGramsPerKWh.isNaN() || carbonIntensityGramsPerKWh.doubleValue() < 0.0)) {
                throw new IllegalArgumentException("Invalid carbonIntensityGramsPerKWh for VM " + id + ": "
                        + carbonIntensityGramsPerKWh + " (required when declared: finite and >= 0)");
            }
            if (id < 0
                    || !positiveFinite(mips)
                    || pes <= 0
                    || ramMb <= 0
                    || bandwidth <= 0L
                    || imageSizeMb <= 0L
                    || vmm == null
                    || vmm.trim().isEmpty()
                    || schedulerMode == null) {
                throw new IllegalArgumentException("Invalid VM specification for ID " + id);
            }
            if (idlePowerWatts < 0.0
                    || !Double.isFinite(idlePowerWatts)
                    || maxPowerWatts < idlePowerWatts
                    || !Double.isFinite(maxPowerWatts)) {
                throw new IllegalArgumentException("Invalid power fields for VM " + id + ": idle=" + idlePowerWatts
                        + " max=" + maxPowerWatts + " (required: maxPower >= idlePower >= 0, both finite)");
            }
            this.id = id;
            this.mips = mips;
            this.pes = pes;
            this.ramMb = ramMb;
            this.bandwidth = bandwidth;
            this.imageSizeMb = imageSizeMb;
            this.vmm = vmm;
            this.schedulerMode = schedulerMode;
            this.costs = costs;
            this.idlePowerWatts = idlePowerWatts;
            this.maxPowerWatts = maxPowerWatts;
            this.carbonIntensityGramsPerKWh = carbonIntensityGramsPerKWh;
            this.frequencyTiers = frequencyTiers == null || frequencyTiers.isEmpty()
                    ? Collections.<FrequencyTier>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(frequencyTiers));
        }

        public double getIdlePowerWatts() {
            return idlePowerWatts;
        }

        public double getMaxPowerWatts() {
            return maxPowerWatts;
        }

        public boolean hasPowerModel() {
            return idlePowerWatts > 0.0 || maxPowerWatts > 0.0;
        }

        public Double getCarbonIntensityGramsPerKWh() {
            return carbonIntensityGramsPerKWh;
        }

        public List<FrequencyTier> getFrequencyTiers() {
            return frequencyTiers;
        }

        public boolean hasFrequencyTiers() {
            return frequencyTiers != null && !frequencyTiers.isEmpty();
        }

        public FrequencyTier defaultFrequencyTier() {
            return frequencyTiers.get(0);
        }

        public int getId() {
            return id;
        }

        public double getMips() {
            return mips;
        }

        public int getPes() {
            return pes;
        }

        public int getRamMb() {
            return ramMb;
        }

        public long getBandwidth() {
            return bandwidth;
        }

        public long getImageSizeMb() {
            return imageSizeMb;
        }

        public String getVmm() {
            return vmm;
        }

        public CloudletSchedulerMode getSchedulerMode() {
            return schedulerMode;
        }

        public boolean hasCosts() {
            return costs != null;
        }

        public CostSpec getCosts() {
            return costs;
        }
    }

    public static final class StorageSpec {

        private final long capacityMb;
        private final double maxTransferRateMbPerSecond;

        public StorageSpec(long capacityMb, double maxTransferRateMbPerSecond) {
            if (capacityMb <= 0L || !positiveFinite(maxTransferRateMbPerSecond)) {
                throw new IllegalArgumentException("Storage capacity must be positive and the transfer rate finite and "
                        + "positive: capacityMb=" + capacityMb + " rate=" + maxTransferRateMbPerSecond);
            }
            this.capacityMb = capacityMb;
            this.maxTransferRateMbPerSecond = maxTransferRateMbPerSecond;
        }

        public long getCapacityMb() {
            return capacityMb;
        }

        public double getMaxTransferRateMbPerSecond() {
            return maxTransferRateMbPerSecond;
        }
    }

    public static final class CostSpec {

        private final double cpuPerSecond;
        private final double memory;
        private final double storage;
        private final double bandwidth;

        public CostSpec(double cpuPerSecond, double memory, double storage, double bandwidth) {
            if (!nonNegativeFinite(cpuPerSecond)
                    || !nonNegativeFinite(memory)
                    || !nonNegativeFinite(storage)
                    || !nonNegativeFinite(bandwidth)) {
                throw new IllegalArgumentException("Datacenter costs must be finite and non-negative");
            }
            this.cpuPerSecond = cpuPerSecond;
            this.memory = memory;
            this.storage = storage;
            this.bandwidth = bandwidth;
        }

        public double getCpuPerSecond() {
            return cpuPerSecond;
        }

        public double getMemory() {
            return memory;
        }

        public double getStorage() {
            return storage;
        }

        public double getBandwidth() {
            return bandwidth;
        }
    }

    private static boolean positiveFinite(double value) {
        return value > 0.0 && !Double.isInfinite(value) && !Double.isNaN(value);
    }

    private static boolean nonNegativeFinite(double value) {
        return value >= 0.0 && !Double.isInfinite(value) && !Double.isNaN(value);
    }
}
