/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2011, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

public class VmStateHistoryEntry {

    private double time;

    private double allocatedMips;

    private double requestedMips;

    private boolean isInMigration;

    public VmStateHistoryEntry(double time, double allocatedMips, double requestedMips, boolean isInMigration) {
        setTime(time);
        setAllocatedMips(allocatedMips);
        setRequestedMips(requestedMips);
        setInMigration(isInMigration);
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

    protected void setInMigration(boolean isInMigration) {
        this.isInMigration = isInMigration;
    }

    public boolean isInMigration() {
        return isInMigration;
    }
}
