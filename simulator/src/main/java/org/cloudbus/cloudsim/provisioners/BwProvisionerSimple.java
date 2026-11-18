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

public class BwProvisionerSimple extends BwProvisioner {

    private Map<String, Long> bwTable;

    public BwProvisionerSimple(long bw) {
        super(bw);
        setBwTable(new HashMap<String, Long>());
    }

    @Override
    public boolean allocateBwForVm(Vm vm, long bw) {
        deallocateBwForVm(vm);

        if (getAvailableBw() >= bw) {
            setAvailableBw(getAvailableBw() - bw);
            getBwTable().put(vm.getUid(), bw);
            vm.setCurrentAllocatedBw(getAllocatedBwForVm(vm));
            return true;
        }

        vm.setCurrentAllocatedBw(getAllocatedBwForVm(vm));
        return false;
    }

    @Override
    public long getAllocatedBwForVm(Vm vm) {
        if (getBwTable().containsKey(vm.getUid())) {
            return getBwTable().get(vm.getUid());
        }
        return 0;
    }

    @Override
    public void deallocateBwForVm(Vm vm) {
        if (getBwTable().containsKey(vm.getUid())) {
            long amountFreed = getBwTable().remove(vm.getUid());
            setAvailableBw(getAvailableBw() + amountFreed);
            vm.setCurrentAllocatedBw(0);
        }
    }

    @Override
    public void deallocateBwForAllVms() {
        super.deallocateBwForAllVms();
        getBwTable().clear();
    }

    @Override
    public boolean isSuitableForVm(Vm vm, long bw) {
        long allocatedBw = getAllocatedBwForVm(vm);
        boolean result = allocateBwForVm(vm, bw);
        deallocateBwForVm(vm);
        if (allocatedBw > 0) {
            allocateBwForVm(vm, allocatedBw);
        }
        return result;
    }

    protected Map<String, Long> getBwTable() {
        return bwTable;
    }

    protected void setBwTable(Map<String, Long> bwTable) {
        this.bwTable = bwTable;
    }
}
