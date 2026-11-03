package org.workflowsim.platform;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.CloudletScheduler;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.HarddriveStorage;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowDatacenter;

/** Build CloudSim/WorkflowSim entities from a validated {@link PlatformProfile}. */
public final class PlatformFactory {

    private PlatformFactory() {}

    /**
     * Convert an abstract platform description into one WorkflowSim datacenter.
     *
     * @param entityName CloudSim entity name, must not be empty
     * @param profile platform description that already passed the capacity and VM-host
     *        placement preflight
     * @return datacenter using the deterministic VM placement policy
     * @throws Exception when a CloudSim storage entity cannot be created
     * @throws IllegalArgumentException when the name is empty or the platform description
     *         is null
     */
    public static WorkflowDatacenter createDatacenter(String entityName, PlatformProfile profile) throws Exception {
        requireEntityName(entityName);
        if (profile == null) {
            throw new IllegalArgumentException("Platform profile cannot be null");
        }
        List<Host> hosts = new ArrayList<>();
        for (PlatformProfile.HostSpec spec : profile.getHosts()) {
            List<Pe> pes = new ArrayList<>();
            for (int peId = 0; peId < spec.getPes(); peId++) {
                pes.add(new Pe(peId, new PeProvisionerSimple(spec.getMipsPerPe())));
            }
            hosts.add(new Host(
                    spec.getId(),
                    new RamProvisionerSimple(spec.getRamMb()),
                    new BwProvisionerSimple(spec.getBandwidth()),
                    spec.getStorageMb(),
                    pes,
                    new VmSchedulerTimeShared(pes)));
        }

        PlatformProfile.CostSpec costs = profile.getCosts();
        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                "x86",
                "Linux",
                "Xen",
                hosts,
                10.0,
                costs.getCpuPerSecond(),
                costs.getMemory(),
                costs.getStorage(),
                costs.getBandwidth());
        PlatformProfile.StorageSpec storageSpec = profile.getStorage();
        List<Storage> storage = new LinkedList<>();
        HarddriveStorage disk = new HarddriveStorage(entityName, storageSpec.getCapacityMb());
        disk.setMaxTransferRate(storageSpec.getMaxTransferRateMbPerSecond());
        storage.add(disk);
        return new WorkflowDatacenter(
                entityName,
                characteristics,
                new DeterministicVmAllocationPolicy(hosts, profile.getVmHostAssignments()),
                storage,
                0.0);
    }

    /**
     * Create the list of VMs owned by the specified WorkflowEngine user according to the
     * platform description.
     *
     * @param profile validated platform description
     * @param userId identifier of the user/scheduler in CloudSim that receives these VMs
     * @return newly created VM list that corresponds one-to-one to the platform VM descriptions
     * @throws IllegalArgumentException when the platform description is null
     */
    public static List<CondorVM> createVms(PlatformProfile profile, int userId) {
        if (profile == null) {
            throw new IllegalArgumentException("Platform profile cannot be null");
        }
        List<CondorVM> vms = new ArrayList<>();
        for (PlatformProfile.VmSpec spec : profile.getVms()) {
            CondorVM vm;
            if (spec.hasCosts()) {
                PlatformProfile.CostSpec costs = spec.getCosts();
                vm = new CondorVM(
                        spec.getId(),
                        userId,
                        spec.getMips(),
                        spec.getPes(),
                        spec.getRamMb(),
                        spec.getBandwidth(),
                        spec.getImageSizeMb(),
                        spec.getVmm(),
                        costs.getCpuPerSecond(),
                        costs.getMemory(),
                        costs.getStorage(),
                        costs.getBandwidth(),
                        createScheduler(spec));
            } else {
                vm = new CondorVM(
                        spec.getId(),
                        userId,
                        spec.getMips(),
                        spec.getPes(),
                        spec.getRamMb(),
                        spec.getBandwidth(),
                        spec.getImageSizeMb(),
                        spec.getVmm(),
                        createScheduler(spec));
            }
            // BAT-D2 (2026-10-29): inject the frequency-tier table into the VM at assembly time
            // (an empty table keeps the v1 mode).
            if (spec.hasFrequencyTiers()) {
                vm.setFrequencyTiers(spec.getFrequencyTiers());
            }
            vms.add(vm);
        }
        return vms;
    }

    private static CloudletScheduler createScheduler(PlatformProfile.VmSpec spec) {
        if (spec.getSchedulerMode() == PlatformProfile.CloudletSchedulerMode.TIME_SHARED) {
            return new CloudletSchedulerTimeShared();
        }
        return new CloudletSchedulerSpaceShared();
    }

    private static void requireEntityName(String entityName) {
        if (entityName == null || entityName.trim().isEmpty()) {
            throw new IllegalArgumentException("Datacenter entity name cannot be empty");
        }
    }
}
