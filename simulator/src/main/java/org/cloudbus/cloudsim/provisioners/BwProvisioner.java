/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.provisioners;

import org.cloudbus.cloudsim.Vm;

public abstract class BwProvisioner {

    private long bw;

    private long availableBw;

    public BwProvisioner(long bw) {
        setBw(bw);
        setAvailableBw(bw);
    }

    public abstract boolean allocateBwForVm(Vm vm, long bw);

    public abstract long getAllocatedBwForVm(Vm vm);

    public abstract void deallocateBwForVm(Vm vm);

    public void deallocateBwForAllVms() {
        setAvailableBw(getBw());
    }

    public abstract boolean isSuitableForVm(Vm vm, long bw);

    public long getBw() {
        return bw;
    }

    protected void setBw(long bw) {
        this.bw = bw;
    }

    public long getAvailableBw() {
        return availableBw;
    }

    public long getUsedBw() {
        return bw - availableBw;
    }

    protected void setAvailableBw(long availableBw) {
        this.availableBw = availableBw;
    }
}
