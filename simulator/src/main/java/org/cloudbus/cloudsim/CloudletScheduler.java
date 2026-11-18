/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import java.util.List;

public abstract class CloudletScheduler {

    private double previousTime;

    private List<Double> currentMipsShare;

    public CloudletScheduler() {
        setPreviousTime(0.0);
    }

    public abstract double updateVmProcessing(double currentTime, List<Double> mipsShare);

    public abstract double cloudletSubmit(Cloudlet gl, double fileTransferTime);

    public abstract double cloudletSubmit(Cloudlet gl);

    public abstract Cloudlet cloudletCancel(int clId);

    public abstract boolean cloudletPause(int clId);

    public abstract double cloudletResume(int clId);

    public abstract void cloudletFinish(ResCloudlet rcl);

    public abstract int getCloudletStatus(int clId);

    public abstract boolean isFinishedCloudlets();

    public abstract Cloudlet getNextFinishedCloudlet();

    public abstract int runningCloudlets();

    public abstract Cloudlet migrateCloudlet();

    public abstract double getTotalUtilizationOfCpu(double time);

    public abstract List<Double> getCurrentRequestedMips();

    public abstract double getTotalCurrentAvailableMipsForCloudlet(ResCloudlet rcl, List<Double> mipsShare);

    public abstract double getTotalCurrentRequestedMipsForCloudlet(ResCloudlet rcl, double time);

    public abstract double getTotalCurrentAllocatedMipsForCloudlet(ResCloudlet rcl, double time);

    public abstract double getCurrentRequestedUtilizationOfRam();

    public abstract double getCurrentRequestedUtilizationOfBw();

    public double getPreviousTime() {
        return previousTime;
    }

    protected void setPreviousTime(double previousTime) {
        this.previousTime = previousTime;
    }

    protected void setCurrentMipsShare(List<Double> currentMipsShare) {
        this.currentMipsShare = currentMipsShare;
    }

    public List<Double> getCurrentMipsShare() {
        return currentMipsShare;
    }
}
