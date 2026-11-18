/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.provisioners;

import java.util.HashMap;
import java.util.Map;

import org.cloudbus.cloudsim.Vm;

public class RamProvisionerSimple extends RamProvisioner {

    private Map<String, Integer> ramTable;

    public RamProvisionerSimple(int availableRam) {
        super(availableRam);
        setRamTable(new HashMap<String, Integer>());
    }

    @Override
    public boolean allocateRamForVm(Vm vm, int ram) {
        int maxRam = vm.getRam();

        if (ram >= maxRam) {
            ram = maxRam;
        }

        deallocateRamForVm(vm);

        if (getAvailableRam() >= ram) {
            setAvailableRam(getAvailableRam() - ram);
            getRamTable().put(vm.getUid(), ram);
            vm.setCurrentAllocatedRam(getAllocatedRamForVm(vm));
            return true;
        }

        vm.setCurrentAllocatedRam(getAllocatedRamForVm(vm));

        return false;
    }

    @Override
    public int getAllocatedRamForVm(Vm vm) {
        if (getRamTable().containsKey(vm.getUid())) {
            return getRamTable().get(vm.getUid());
        }
        return 0;
    }

    @Override
    public void deallocateRamForVm(Vm vm) {
        if (getRamTable().containsKey(vm.getUid())) {
            int amountFreed = getRamTable().remove(vm.getUid());
            setAvailableRam(getAvailableRam() + amountFreed);
            vm.setCurrentAllocatedRam(0);
        }
    }

    @Override
    public void deallocateRamForAllVms() {
        super.deallocateRamForAllVms();
        getRamTable().clear();
    }

    @Override
    public boolean isSuitableForVm(Vm vm, int ram) {
        int allocatedRam = getAllocatedRamForVm(vm);
        boolean result = allocateRamForVm(vm, ram);
        deallocateRamForVm(vm);
        if (allocatedRam > 0) {
            allocateRamForVm(vm, allocatedRam);
        }
        return result;
    }

    protected Map<String, Integer> getRamTable() {
        return ramTable;
    }

    protected void setRamTable(Map<String, Integer> ramTable) {
        this.ramTable = ramTable;
    }
}
