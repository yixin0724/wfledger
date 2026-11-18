/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2011, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

public class HostStateHistoryEntry {

    private double time;

    private double allocatedMips;

    private double requestedMips;

    private boolean isActive;

    public HostStateHistoryEntry(double time, double allocatedMips, double requestedMips, boolean isActive) {
        setTime(time);
        setAllocatedMips(allocatedMips);
        setRequestedMips(requestedMips);
        setActive(isActive);
    }

    protected void setTime(double time) {
        this.time = time;
    }

    public double getTime() {
        return time;
    }

    protected void setAllocatedMips(double allocatedMips) {
        this.allocatedMips = allocatedMips;
    }

    public double getAllocatedMips() {
        return allocatedMips;
    }

    protected void setRequestedMips(double requestedMips) {
        this.requestedMips = requestedMips;
    }

    public double getRequestedMips() {
        return requestedMips;
    }

    public void setActive(boolean isActive) {
        this.isActive = isActive;
    }

    public boolean isActive() {
        return isActive;
    }
}
