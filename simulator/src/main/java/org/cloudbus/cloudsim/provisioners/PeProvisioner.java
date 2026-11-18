/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.provisioners;

import java.util.List;

import org.cloudbus.cloudsim.Vm;

public abstract class PeProvisioner {

    private double mips;

    private double availableMips;

    public PeProvisioner(double mips) {
        setMips(mips);
        setAvailableMips(mips);
    }

    public abstract boolean allocateMipsForVm(Vm vm, double mips);

    public abstract boolean allocateMipsForVm(String vmUid, double mips);

    public abstract boolean allocateMipsForVm(Vm vm, List<Double> mips);

    public abstract List<Double> getAllocatedMipsForVm(Vm vm);

    public abstract double getTotalAllocatedMipsForVm(Vm vm);

    public abstract double getAllocatedMipsForVmByVirtualPeId(Vm vm, int peId);

    public abstract void deallocateMipsForVm(Vm vm);

    public void deallocateMipsForAllVms() {
        setAvailableMips(getMips());
    }

    public double getMips() {
        return mips;
    }

    public void setMips(double mips) {
        this.mips = mips;
    }

    public double getAvailableMips() {
        return availableMips;
    }

    protected void setAvailableMips(double availableMips) {
        this.availableMips = availableMips;
    }

    public double getTotalAllocatedMips() {
        double totalAllocatedMips = getMips() - getAvailableMips();
        if (totalAllocatedMips > 0) {
            return totalAllocatedMips;
        }
        return 0;
    }

    public double getUtilization() {
        return getTotalAllocatedMips() / getMips();
    }
}
