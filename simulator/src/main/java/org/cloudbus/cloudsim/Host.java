/*
 * Title: CloudSim Toolkit Description: CloudSim (Cloud Simulation) Toolkit for Modeling and
 * Simulation of Clouds Licence: GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import java.util.ArrayList;
import java.util.List;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.lists.PeList;
import org.cloudbus.cloudsim.provisioners.BwProvisioner;
import org.cloudbus.cloudsim.provisioners.RamProvisioner;

public class Host {

    private int id;

    private long storage;

    private RamProvisioner ramProvisioner;

    private BwProvisioner bwProvisioner;

    private VmScheduler vmScheduler;

    private final List<? extends Vm> vmList = new ArrayList<Vm>();

    private List<? extends Pe> peList;

    private boolean failed;

    private final List<Vm> vmsMigratingIn = new ArrayList<Vm>();

    private Datacenter datacenter;

    public Host(
            int id,
            RamProvisioner ramProvisioner,
            BwProvisioner bwProvisioner,
            long storage,
            List<? extends Pe> peList,
            VmScheduler vmScheduler) {
        setId(id);
        setRamProvisioner(ramProvisioner);
        setBwProvisioner(bwProvisioner);
        setStorage(storage);
        setVmScheduler(vmScheduler);

        setPeList(peList);
        setFailed(false);
    }

    public double updateVmsProcessing(double currentTime) {
        double smallerTime = Double.MAX_VALUE;

        for (Vm vm : getVmList()) {
            double time = vm.updateVmProcessing(currentTime, getVmScheduler().getAllocatedMipsForVm(vm));
            if (time > 0.0 && time < smallerTime) {
                smallerTime = time;
            }
        }

        return smallerTime;
    }

    public void addMigratingInVm(Vm vm) {
        vm.setInMigration(true);

        if (!getVmsMigratingIn().contains(vm)) {
            if (getStorage() < vm.getSize()) {
                Log.printLine("[VmScheduler.addMigratingInVm] Allocation of VM #" + vm.getId() + " to Host #" + getId()
                        + " failed by storage");
                System.exit(0);
            }

            if (!getRamProvisioner().allocateRamForVm(vm, vm.getCurrentRequestedRam())) {
                Log.printLine("[VmScheduler.addMigratingInVm] Allocation of VM #" + vm.getId() + " to Host #" + getId()
                        + " failed by RAM");
                System.exit(0);
            }

            if (!getBwProvisioner().allocateBwForVm(vm, vm.getCurrentRequestedBw())) {
                Log.printLine("[VmScheduler.addMigratingInVm] Allocation of VM #" + vm.getId() + " to Host #" + getId()
                        + " failed by BW");
                System.exit(0);
            }

            getVmScheduler().getVmsMigratingIn().add(vm.getUid());
            if (!getVmScheduler().allocatePesForVm(vm, vm.getCurrentRequestedMips())) {
                Log.printLine("[VmScheduler.addMigratingInVm] Allocation of VM #" + vm.getId() + " to Host #" + getId()
                        + " failed by MIPS");
                System.exit(0);
            }

            setStorage(getStorage() - vm.getSize());

            getVmsMigratingIn().add(vm);
            getVmList().add(vm);
            updateVmsProcessing(CloudSim.clock());
            vm.getHost().updateVmsProcessing(CloudSim.clock());
        }
    }

    public void removeMigratingInVm(Vm vm) {
        vmDeallocate(vm);
        getVmsMigratingIn().remove(vm);
        getVmList().remove(vm);
        getVmScheduler().getVmsMigratingIn().remove(vm.getUid());
        vm.setInMigration(false);
    }

    public void reallocateMigratingInVms() {
        for (Vm vm : getVmsMigratingIn()) {
            if (!getVmList().contains(vm)) {
                getVmList().add(vm);
            }
            if (!getVmScheduler().getVmsMigratingIn().contains(vm.getUid())) {
                getVmScheduler().getVmsMigratingIn().add(vm.getUid());
            }
            getRamProvisioner().allocateRamForVm(vm, vm.getCurrentRequestedRam());
            getBwProvisioner().allocateBwForVm(vm, vm.getCurrentRequestedBw());
            getVmScheduler().allocatePesForVm(vm, vm.getCurrentRequestedMips());
            setStorage(getStorage() - vm.getSize());
        }
    }

    public boolean isSuitableForVm(Vm vm) {
        return (getVmScheduler().getPeCapacity() >= vm.getCurrentRequestedMaxMips()
                && getVmScheduler().getAvailableMips() >= vm.getCurrentRequestedTotalMips()
                && getRamProvisioner().isSuitableForVm(vm, vm.getCurrentRequestedRam())
                && getBwProvisioner().isSuitableForVm(vm, vm.getCurrentRequestedBw()));
    }

    public boolean vmCreate(Vm vm) {
        if (getStorage() < vm.getSize()) {
            Log.printLine("[VmScheduler.vmCreate] Allocation of VM #" + vm.getId() + " to Host #" + getId()
                    + " failed by storage");
            return false;
        }

        if (!getRamProvisioner().allocateRamForVm(vm, vm.getCurrentRequestedRam())) {
            Log.printLine("[VmScheduler.vmCreate] Allocation of VM #" + vm.getId() + " to Host #" + getId()
                    + " failed by RAM");
            return false;
        }

        if (!getBwProvisioner().allocateBwForVm(vm, vm.getCurrentRequestedBw())) {
            Log.printLine("[VmScheduler.vmCreate] Allocation of VM #" + vm.getId() + " to Host #" + getId()
                    + " failed by BW");
            getRamProvisioner().deallocateRamForVm(vm);
            return false;
        }

        if (!getVmScheduler().allocatePesForVm(vm, vm.getCurrentRequestedMips())) {
            Log.printLine("[VmScheduler.vmCreate] Allocation of VM #" + vm.getId() + " to Host #" + getId()
                    + " failed by MIPS");
            getRamProvisioner().deallocateRamForVm(vm);
            getBwProvisioner().deallocateBwForVm(vm);
            return false;
        }

        setStorage(getStorage() - vm.getSize());
        getVmList().add(vm);
        vm.setHost(this);
        return true;
    }

    public void vmDestroy(Vm vm) {
        if (vm != null) {
            vmDeallocate(vm);
            getVmList().remove(vm);
            vm.setHost(null);
        }
    }

    public void vmDestroyAll() {
        vmDeallocateAll();
        for (Vm vm : getVmList()) {
            vm.setHost(null);
            setStorage(getStorage() + vm.getSize());
        }
        getVmList().clear();
    }

    protected void vmDeallocate(Vm vm) {
        getRamProvisioner().deallocateRamForVm(vm);
        getBwProvisioner().deallocateBwForVm(vm);
        getVmScheduler().deallocatePesForVm(vm);
        setStorage(getStorage() + vm.getSize());
    }

    protected void vmDeallocateAll() {
        getRamProvisioner().deallocateRamForAllVms();
        getBwProvisioner().deallocateBwForAllVms();
        getVmScheduler().deallocatePesForAllVms();
    }

    public Vm getVm(int vmId, int userId) {
        for (Vm vm : getVmList()) {
            if (vm.getId() == vmId && vm.getUserId() == userId) {
                return vm;
            }
        }
        return null;
    }

    public int getNumberOfPes() {
        return getPeList().size();
    }

    public int getNumberOfFreePes() {
        return PeList.getNumberOfFreePes(getPeList());
    }

    public int getTotalMips() {
        return PeList.getTotalMips(getPeList());
    }

    public boolean allocatePesForVm(Vm vm, List<Double> mipsShare) {
        return getVmScheduler().allocatePesForVm(vm, mipsShare);
    }

    public void deallocatePesForVm(Vm vm) {
        getVmScheduler().deallocatePesForVm(vm);
    }

    public List<Double> getAllocatedMipsForVm(Vm vm) {
        return getVmScheduler().getAllocatedMipsForVm(vm);
    }

    public double getTotalAllocatedMipsForVm(Vm vm) {
        return getVmScheduler().getTotalAllocatedMipsForVm(vm);
    }

    public double getMaxAvailableMips() {
        return getVmScheduler().getMaxAvailableMips();
    }

    public double getAvailableMips() {
        return getVmScheduler().getAvailableMips();
    }

    public long getBw() {
        return getBwProvisioner().getBw();
    }

    public int getRam() {
        return getRamProvisioner().getRam();
    }

    public long getStorage() {
        return storage;
    }

    public int getId() {
        return id;
    }

    protected void setId(int id) {
        this.id = id;
    }

    public RamProvisioner getRamProvisioner() {
        return ramProvisioner;
    }

    protected void setRamProvisioner(RamProvisioner ramProvisioner) {
        this.ramProvisioner = ramProvisioner;
    }

    public BwProvisioner getBwProvisioner() {
        return bwProvisioner;
    }

    protected void setBwProvisioner(BwProvisioner bwProvisioner) {
        this.bwProvisioner = bwProvisioner;
    }

    public VmScheduler getVmScheduler() {
        return vmScheduler;
    }

    protected void setVmScheduler(VmScheduler vmScheduler) {
        this.vmScheduler = vmScheduler;
    }

    @SuppressWarnings("unchecked")
    public <T extends Pe> List<T> getPeList() {
        return (List<T>) peList;
    }

    protected <T extends Pe> void setPeList(List<T> peList) {
        this.peList = peList;
    }

    @SuppressWarnings("unchecked")
    public <T extends Vm> List<T> getVmList() {
        return (List<T>) vmList;
    }

    protected void setStorage(long storage) {
        this.storage = storage;
    }

    public boolean isFailed() {
        return failed;
    }

    public boolean setFailed(String resName, boolean failed) {

        this.failed = failed;
        PeList.setStatusFailed(getPeList(), resName, getId(), failed);
        return true;
    }

    public boolean setFailed(boolean failed) {

        this.failed = failed;
        PeList.setStatusFailed(getPeList(), failed);
        return true;
    }

    public boolean setPeStatus(int peId, int status) {
        return PeList.setPeStatus(getPeList(), peId, status);
    }

    public List<Vm> getVmsMigratingIn() {
        return vmsMigratingIn;
    }

    public Datacenter getDatacenter() {
        return datacenter;
    }

    public void setDatacenter(Datacenter datacenter) {
        this.datacenter = datacenter;
    }
}
