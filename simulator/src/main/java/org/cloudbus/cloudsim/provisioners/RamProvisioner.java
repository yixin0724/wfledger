/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.provisioners;

import org.cloudbus.cloudsim.Vm;

public abstract class RamProvisioner {

    private int ram;

    private int availableRam;

    public RamProvisioner(int ram) {
        setRam(ram);
        setAvailableRam(ram);
    }

    public abstract boolean allocateRamForVm(Vm vm, int ram);

    public abstract int getAllocatedRamForVm(Vm vm);

    public abstract void deallocateRamForVm(Vm vm);

    public void deallocateRamForAllVms() {
        setAvailableRam(getRam());
    }

    public abstract boolean isSuitableForVm(Vm vm, int ram);

    public int getRam() {
        return ram;
    }

    protected void setRam(int ram) {
        this.ram = ram;
    }

    public int getUsedRam() {
        return ram - availableRam;
    }

    public int getAvailableRam() {
        return availableRam;
    }

    protected void setAvailableRam(int availableRam) {
        this.availableRam = availableRam;
    }
}
