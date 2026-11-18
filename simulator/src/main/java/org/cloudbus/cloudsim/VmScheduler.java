/*
 * Title: CloudSim Toolkit Description: CloudSim (Cloud Simulation) Toolkit for Modeling and
 * Simulation of Clouds Licence: GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.lists.PeList;

public abstract class VmScheduler {

    private List<? extends Pe> peList;

    private Map<String, List<Pe>> peMap;

    private Map<String, List<Double>> mipsMap;

    private double availableMips;

    private List<String> vmsMigratingIn;

    private List<String> vmsMigratingOut;

    public VmScheduler(List<? extends Pe> pelist) {
        setPeList(pelist);
        setPeMap(new HashMap<String, List<Pe>>());
        setMipsMap(new HashMap<String, List<Double>>());
        setAvailableMips(PeList.getTotalMips(getPeList()));
        setVmsMigratingIn(new ArrayList<String>());
        setVmsMigratingOut(new ArrayList<String>());
    }

    public abstract boolean allocatePesForVm(Vm vm, List<Double> mipsShare);

    public abstract void deallocatePesForVm(Vm vm);

    public void deallocatePesForAllVms() {
        getMipsMap().clear();
        setAvailableMips(PeList.getTotalMips(getPeList()));
        for (Pe pe : getPeList()) {
            pe.getPeProvisioner().deallocateMipsForAllVms();
        }
    }

    public List<Pe> getPesAllocatedForVM(Vm vm) {
        return getPeMap().get(vm.getUid());
    }

    public List<Double> getAllocatedMipsForVm(Vm vm) {
        return getMipsMap().get(vm.getUid());
    }

    public double getTotalAllocatedMipsForVm(Vm vm) {
        double allocated = 0;
        List<Double> mipsMap = getAllocatedMipsForVm(vm);
        if (mipsMap != null) {
            for (double mips : mipsMap) {
                allocated += mips;
            }
        }
        return allocated;
    }

    public double getMaxAvailableMips() {
        if (getPeList() == null) {
            Log.printLine("Pe list is empty");
            return 0;
        }

        double max = 0.0;
        for (Pe pe : getPeList()) {
            double tmp = pe.getPeProvisioner().getAvailableMips();
            if (tmp > max) {
                max = tmp;
            }
        }

        return max;
    }

    public double getPeCapacity() {
        if (getPeList() == null) {
            Log.printLine("Pe list is empty");
            return 0;
        }
        return getPeList().get(0).getMips();
    }

    @SuppressWarnings("unchecked")
    public <T extends Pe> List<T> getPeList() {
        return (List<T>) peList;
    }

    protected <T extends Pe> void setPeList(List<T> peList) {
        this.peList = peList;
    }

    protected Map<String, List<Double>> getMipsMap() {
        return mipsMap;
    }

    protected void setMipsMap(Map<String, List<Double>> mipsMap) {
        this.mipsMap = mipsMap;
    }

    public double getAvailableMips() {
        return availableMips;
    }

    protected void setAvailableMips(double availableMips) {
        this.availableMips = availableMips;
    }

    public List<String> getVmsMigratingOut() {
        return vmsMigratingOut;
    }

    protected void setVmsMigratingOut(List<String> vmsInMigration) {
        vmsMigratingOut = vmsInMigration;
    }

    public List<String> getVmsMigratingIn() {
        return vmsMigratingIn;
    }

    protected void setVmsMigratingIn(List<String> vmsMigratingIn) {
        this.vmsMigratingIn = vmsMigratingIn;
    }

    public Map<String, List<Pe>> getPeMap() {
        return peMap;
    }

    protected void setPeMap(Map<String, List<Pe>> peMap) {
        this.peMap = peMap;
    }
}
