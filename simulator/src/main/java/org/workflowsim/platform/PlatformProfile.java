package org.workflowsim.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Immutable abstract infrastructure model used by WorkflowSim experiments.
 *
 * <p>A platform description only declares the resources being simulated; it cannot be
 * used to claim that a WfCommons execution trace ran on the same infrastructure.</p>
 */
public final class PlatformProfile {

    /** Execution resource sharing mode of Cloudlets inside a VM. */
    public enum CloudletSchedulerMode {
        /** Each task exclusively owns the allocated processing resources until it completes. */
        SPACE_SHARED,
        /** Multiple tasks share the processing resources in time slices. */
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

    /** BAT-C1: @return green power windows declared by the budget model (immutable); empty = model not declared */
    public List<CarbonWindow> getCarbonWindows() {
        return carbonWindows;
    }

    /**
     * Green power windows of the budget model (BAT-C1, 2026-10-28): available green power
     * over the interval [startSeconds, endSeconds). Ordering and non-overlap are validated
     * at build time; coverage completeness is validated by the accounting side against the
     * simulation duration.
     */
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

    /**
     * DVFS frequency tier (BAT-D1, 2026-10-29). A P-state triple: MIPS speed plus the idle
     * and full-load power of that tier. The table order is strictly descending by MIPS
     * (first tier = default tier). Derivation of the conservation law:
     *
     * <pre>
     * E_vm = P_idle(default tier) * T_idle + SUM_tier [P_max(tier) * T_busy(tier)]
     * </pre>
     *
     * <p>A single-tier list (or an empty one) degenerates semantically to the v1 linear law
     * (equivalent to one tier). Empty list = v1 mode; non-empty list = DVFS mode.</p>
     */
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

    /**
     * Returns the explicitly pinned VM-to-Host mapping; VMs that are not pinned do not
     * appear in this mapping.
     *
     * @return immutable explicit VM-Host pinning map
     */
    public java.util.Map<Integer, Integer> getPinnedVmHostIds() {
        return pinnedVmHostIds;
    }

    /**
     * Returns the deterministic VM-to-Host placement computed by the preflight at platform
     * construction time.
     *
     * <p>The standard runner compares it with the mapping actually created by CloudSim
     * before publishing a report.</p>
     *
     * @return immutable complete VM-Host preflight placement map
     */
    public java.util.Map<Integer, Integer> getVmHostAssignments() {
        return vmHostAssignments;
    }

    public StorageSpec getStorage() {
        return storage;
    }

    /** @return explicit bounded dataflow store, or null for original models */
    public org.workflowsim.data.v2.DataflowStorageSpec getSourceStorage() {
        return sourceStorage;
    }

    public CostSpec getCosts() {
        return costs;
    }

    /**
     * Returns the network topology declared by the platform; {@code null} when not declared.
     *
     * <p>The Fat-tree link-contention data movement model requires this declaration.</p>
     *
     * @return immutable network topology declaration, or null
     */
    public org.workflowsim.network.NetworkTopologySpec getNetworkTopology() {
        return networkTopology;
    }

    /**
     * Creates an empty platform description builder.
     *
     * @param name platform name used in reports and evidence artifacts; must not be empty
     * @return builder that can further add Host, VM, storage and cost configuration
     * @throws IllegalArgumentException when the name is empty
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** Builder that validates platform identity, resource capacity and deterministic placement constraints. */
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

        /** @param value explicit bounded source/storage resources @return this builder */
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

        /**
         * Adds a Host description.
         *
         * @param host platform Host description to add; must not be null
         * @return this builder
         * @throws IllegalArgumentException when {@code host} is null
         */
        public Builder addHost(HostSpec host) {
            hosts.add(requireNonNull(host, "Host specification"));
            return this;
        }

        /**
         * Adds a VM description.
         *
         * @param vm platform VM description to add; must not be null
         * @return this builder
         * @throws IllegalArgumentException when {@code vm} is null
         */
        public Builder addVm(VmSpec vm) {
            vms.add(requireNonNull(vm, "VM specification"));
            return this;
        }

        /**
         * Pins a declared VM to a declared Host.
         *
         * <p>VMs that are not pinned keep using the deterministic compatible placement rule
         * applied by the standard runner.</p>
         *
         * @param vmId non-negative identifier of a declared VM
         * @param hostId non-negative identifier of a declared Host
         * @return this builder
         * @throws IllegalArgumentException when an identifier is negative or the same VM is
         *     pinned to different Hosts
         */
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

        /**
         * Sets the abstract capacity and maximum transfer rate of the shared storage.
         *
         * @param value non-null storage description
         * @return this builder
         * @throws IllegalArgumentException when {@code value} is null
         */
        public Builder storage(StorageSpec value) {
            this.storage = requireNonNull(value, "Storage specification");
            return this;
        }

        /**
         * Sets the datacenter-level abstract cost parameters.
         *
         * @param value non-null cost description
         * @return this builder
         * @throws IllegalArgumentException when {@code value} is null
         */
        public Builder costs(CostSpec value) {
            this.costs = requireNonNull(value, "Cost specification");
            return this;
        }

        /**
         * Declares the network topology of the platform (v1 supports only the Al-Fares k-Pod
         * Fat-tree; see {@link org.workflowsim.network.NetworkTopologySpec#fatTree}).
         *
         * <p>Used only by the Fat-tree link-contention data movement model; once declared it
         * must be combined with that model (the standard runner rejects a configuration that
         * declares a topology without using that model).</p>
         *
         * @param value non-null topology declaration
         * @return this builder
         * @throws IllegalArgumentException when {@code value} is null
         */
        /** BAT-C1: appends a green power window; must be time-ascending and non-overlapping (validated at build). */
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

        /**
         * Validates the resource descriptions and computes the deterministic VM-Host
         * preflight placement.
         *
         * @return immutable platform description
         * @throws IllegalArgumentException when a Host/VM is missing, an identifier is
         *     duplicated, resources are oversubscribed, or the pinned placement is infeasible
         */
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

        /**
         * Reproduces the candidate order of the standard {@code VmAllocationPolicySimple}:
         * most remaining PEs first, then Host declaration order to break ties. A pinned
         * binding replaces the default choice of the corresponding VM; the variable capacity
         * check covers all resources CloudSim retains when creating a VM.
         */
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

        /**
         * Rejects configurations whose aggregate resources are already oversubscribed,
         * before the deterministic preflight runs the stricter per-Host checks.
         */
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

    /** Immutable resource specification of an abstract Host. */
    public static final class HostSpec {

        private final int id;
        private final int pes;
        private final double mipsPerPe;
        private final int ramMb;
        private final long bandwidth;
        private final long storageMb;

        /**
         * Creates a Host resource specification.
         *
         * @param id non-negative Host identifier
         * @param pes number of available processing elements; must be positive
         * @param mipsPerPe compute capacity per processing element; must be positive and finite
         * @param ramMb memory capacity in MB; must be positive
         * @param bandwidth available bandwidth; must be positive
         * @param storageMb local storage capacity in MB; must be positive
         * @throws IllegalArgumentException when any resource parameter is invalid
         */
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

    /** Immutable resource, scheduler and optional pricing specification of an abstract VM. */
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

        /**
         * Creates a VM specification without explicit VM-level pricing.
         *
         * @param id non-negative VM identifier
         * @param mips compute capacity of the VM; must be positive and finite
         * @param pes number of VM processing elements; must be positive
         * @param ramMb memory capacity in MB; must be positive
         * @param bandwidth VM bandwidth; must be positive
         * @param imageSizeMb VM image size in MB; must be positive
         * @param vmm VMM name; must not be empty
         * @param schedulerMode Cloudlet scheduling mode inside the VM; must not be null
         * @throws IllegalArgumentException when any resource parameter is invalid
         */
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

        /** 12-parameter (carbon included) delegate from before BAT-D1: frequency tiers default to null. */
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

        /** Convenience constructor: costs only, no power model. Delegates to master. */
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

        /** 11-parameter (power included) delegate from before BAT-C1: carbon intensity defaults to null. */
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

        /**
         * Creates a VM specification with optional VM-level pricing.
         *
         * <p>When the standard runner uses {@code CostModel.VM}, every VM must provide
         * pricing information.</p>
         *
         * @param id non-negative VM identifier
         * @param mips compute capacity of the VM; must be positive and finite
         * @param pes number of VM processing elements; must be positive
         * @param ramMb memory capacity in MB; must be positive
         * @param bandwidth VM bandwidth; must be positive
         * @param imageSizeMb VM image size in MB; must be positive
         * @param vmm VMM name; must not be empty
         * @param schedulerMode Cloudlet scheduling mode inside the VM; must not be null
         * @param costs optional VM-level cost specification; whether null is allowed is
         *     decided by the selected cost model
         * @throws IllegalArgumentException when any resource parameter is invalid
         */
        /**
         * Creates a VmSpec with optional power fields for the energy dimension
         * (BAT-E1, 2026-10-27). This is the master constructor; the two shorter
         * forms delegate here with zero power defaults.
         *
         * @throws IllegalArgumentException when any resource or power parameter is invalid
         */
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
            // BAT-D1: non-empty frequency tier list excludes the v1 power fields (single source of truth).
            if (frequencyTiers != null && !frequencyTiers.isEmpty()) {
                if (idlePowerWatts > 0.0 || maxPowerWatts > 0.0) {
                    throw new IllegalArgumentException(
                            "VM " + id + " declares BOTH frequencyTiers and plain idlePowerWatts/"
                                    + "maxPowerWatts — one source of truth required (BAT-D1)");
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

        /** @return idle power in watts; 0 when no power model is declared */
        public double getIdlePowerWatts() {
            return idlePowerWatts;
        }

        /** @return max power in watts; 0 when no power model is declared */
        public double getMaxPowerWatts() {
            return maxPowerWatts;
        }

        /** @return true when this VM carries a declared power model (any field > 0) */
        public boolean hasPowerModel() {
            return idlePowerWatts > 0.0 || maxPowerWatts > 0.0;
        }

        /** BAT-C1: @return per-VM carbon intensity declared by the intensity model (gCO2/kWh); null = not declared */
        public Double getCarbonIntensityGramsPerKWh() {
            return carbonIntensityGramsPerKWh;
        }

        /** BAT-D1: @return frequency tier table (immutable; empty = v1 single-tier mode) */
        public List<FrequencyTier> getFrequencyTiers() {
            return frequencyTiers;
        }

        /** BAT-D1: @return whether this VM declares DVFS frequency tiers */
        public boolean hasFrequencyTiers() {
            return frequencyTiers != null && !frequencyTiers.isEmpty();
        }

        /** BAT-D1: @return default tier (= first tier of the table; callers must check hasFrequencyTiers first) */
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

    /** Immutable capacity and maximum transfer rate specification of the abstract shared storage. */
    public static final class StorageSpec {

        private final long capacityMb;
        private final int maxTransferRateMbPerSecond;

        /**
         * Creates a shared storage specification.
         *
         * @param capacityMb storage capacity in MB; must be positive
         * @param maxTransferRateMbPerSecond maximum transfer rate in MB/s; must be positive
         * @throws IllegalArgumentException when the capacity or the rate is not positive
         */
        public StorageSpec(long capacityMb, int maxTransferRateMbPerSecond) {
            if (capacityMb <= 0L || maxTransferRateMbPerSecond <= 0) {
                throw new IllegalArgumentException("Storage capacity and transfer rate must be positive");
            }
            this.capacityMb = capacityMb;
            this.maxTransferRateMbPerSecond = maxTransferRateMbPerSecond;
        }

        public long getCapacityMb() {
            return capacityMb;
        }

        public int getMaxTransferRateMbPerSecond() {
            return maxTransferRateMbPerSecond;
        }
    }

    /** Immutable abstract cost specification used by a datacenter or a VM. */
    public static final class CostSpec {

        private final double cpuPerSecond;
        private final double memory;
        private final double storage;
        private final double bandwidth;

        /**
         * Creates an abstract cost specification.
         *
         * @param cpuPerSecond cost per CPU second; must be a non-negative finite value
         * @param memory memory cost; must be a non-negative finite value
         * @param storage storage cost; must be a non-negative finite value
         * @param bandwidth bandwidth cost; must be a non-negative finite value
         * @throws IllegalArgumentException when any cost is not a non-negative finite value
         */
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
