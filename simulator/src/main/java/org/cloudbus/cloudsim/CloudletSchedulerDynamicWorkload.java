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

import org.cloudbus.cloudsim.core.CloudSim;

public class CloudletSchedulerDynamicWorkload extends CloudletSchedulerTimeShared {

    private double mips;

    private int numberOfPes;

    private double totalMips;

    private Map<String, Double> underAllocatedMips;

    private double cachePreviousTime;

    private List<Double> cacheCurrentRequestedMips;

    public CloudletSchedulerDynamicWorkload(double mips, int numberOfPes) {
        super();
        setMips(mips);
        setNumberOfPes(numberOfPes);
        setTotalMips(getNumberOfPes() * getMips());
        setUnderAllocatedMips(new HashMap<String, Double>());
        setCachePreviousTime(-1);
    }

    @Override
    public double updateVmProcessing(double currentTime, List<Double> mipsShare) {
        setCurrentMipsShare(mipsShare);

        double timeSpan = currentTime - getPreviousTime();
        double nextEvent = Double.MAX_VALUE;
        List<ResCloudlet> cloudletsToFinish = new ArrayList<ResCloudlet>();

        for (ResCloudlet rcl : getCloudletExecList()) {
            rcl.updateCloudletFinishedSoFar((long)
                    (timeSpan * getTotalCurrentAllocatedMipsForCloudlet(rcl, getPreviousTime()) * Consts.MILLION));

            if (rcl.getRemainingCloudletLength() == 0) {
                cloudletsToFinish.add(rcl);
                continue;
            } else {
                double estimatedFinishTime = getEstimatedFinishTime(rcl, currentTime);
                if (estimatedFinishTime - currentTime < CloudSim.getMinTimeBetweenEvents()) {
                    estimatedFinishTime = currentTime + CloudSim.getMinTimeBetweenEvents();
                }
                if (estimatedFinishTime < nextEvent) {
                    nextEvent = estimatedFinishTime;
                }
            }
        }

        for (ResCloudlet rgl : cloudletsToFinish) {
            getCloudletExecList().remove(rgl);
            cloudletFinish(rgl);
        }

        setPreviousTime(currentTime);

        if (getCloudletExecList().isEmpty()) {
            return 0;
        }

        return nextEvent;
    }

    @Override
    public double cloudletSubmit(Cloudlet cl) {
        return cloudletSubmit(cl, 0);
    }

    @Override
    public double cloudletSubmit(Cloudlet cl, double fileTransferTime) {
        ResCloudlet rcl = new ResCloudlet(cl);
        rcl.setCloudletStatus(Cloudlet.INEXEC);

        for (int i = 0; i < cl.getNumberOfPes(); i++) {
            rcl.setMachineAndPeId(0, i);
        }

        getCloudletExecList().add(rcl);
        return getEstimatedFinishTime(rcl, getPreviousTime());
    }

    @Override
    public void cloudletFinish(ResCloudlet rcl) {
        rcl.setCloudletStatus(Cloudlet.SUCCESS);
        rcl.finalizeCloudlet();
        getCloudletFinishedList().add(rcl);
    }

    @Override
    public double getTotalUtilizationOfCpu(double time) {
        double totalUtilization = 0;
        for (ResCloudlet rcl : getCloudletExecList()) {
            totalUtilization += rcl.getCloudlet().getUtilizationOfCpu(time);
        }
        return totalUtilization;
    }

    @Override
    public List<Double> getCurrentRequestedMips() {
        if (getCachePreviousTime() == getPreviousTime()) {
            return getCacheCurrentRequestedMips();
        }
        List<Double> currentMips = new ArrayList<Double>();
        double totalMips = getTotalUtilizationOfCpu(getPreviousTime()) * getTotalMips();
        double mipsForPe = totalMips / getNumberOfPes();

        for (int i = 0; i < getNumberOfPes(); i++) {
            currentMips.add(mipsForPe);
        }

        setCachePreviousTime(getPreviousTime());
        setCacheCurrentRequestedMips(currentMips);

        return currentMips;
    }

    @Override
    public double getTotalCurrentRequestedMipsForCloudlet(ResCloudlet rcl, double time) {
        return rcl.getCloudlet().getUtilizationOfCpu(time) * getTotalMips();
    }

    @Override
    public double getTotalCurrentAvailableMipsForCloudlet(ResCloudlet rcl, List<Double> mipsShare) {
        double totalCurrentMips = 0.0;
        if (mipsShare != null) {
            int neededPEs = rcl.getNumberOfPes();
            for (double mips : mipsShare) {
                totalCurrentMips += mips;
                neededPEs--;
                if (neededPEs <= 0) {
                    break;
                }
            }
        }
        return totalCurrentMips;
    }

    @Override
    public double getTotalCurrentAllocatedMipsForCloudlet(ResCloudlet rcl, double time) {
        double totalCurrentRequestedMips = getTotalCurrentRequestedMipsForCloudlet(rcl, time);
        double totalCurrentAvailableMips = getTotalCurrentAvailableMipsForCloudlet(rcl, getCurrentMipsShare());
        if (totalCurrentRequestedMips > totalCurrentAvailableMips) {
            return totalCurrentAvailableMips;
        }
        return totalCurrentRequestedMips;
    }

    public void updateUnderAllocatedMipsForCloudlet(ResCloudlet rcl, double mips) {
        if (getUnderAllocatedMips().containsKey(rcl.getUid())) {
            mips += getUnderAllocatedMips().get(rcl.getUid());
        }
        getUnderAllocatedMips().put(rcl.getUid(), mips);
    }

    public double getEstimatedFinishTime(ResCloudlet rcl, double time) {
        return time + ((rcl.getRemainingCloudletLength()) / getTotalCurrentAllocatedMipsForCloudlet(rcl, time));
    }

    public int getTotalCurrentMips() {
        int totalCurrentMips = 0;
        for (double mips : getCurrentMipsShare()) {
            totalCurrentMips += mips;
        }
        return totalCurrentMips;
    }

    public void setTotalMips(double mips) {
        totalMips = mips;
    }

    public double getTotalMips() {
        return totalMips;
    }

    public void setNumberOfPes(int pesNumber) {
        numberOfPes = pesNumber;
    }

    public int getNumberOfPes() {
        return numberOfPes;
    }

    public void setMips(double mips) {
        this.mips = mips;
    }

    public double getMips() {
        return mips;
    }

    public void setUnderAllocatedMips(Map<String, Double> underAllocatedMips) {
        this.underAllocatedMips = underAllocatedMips;
    }

    public Map<String, Double> getUnderAllocatedMips() {
        return underAllocatedMips;
    }

    protected double getCachePreviousTime() {
        return cachePreviousTime;
    }

    protected void setCachePreviousTime(double cachePreviousTime) {
        this.cachePreviousTime = cachePreviousTime;
    }

    protected List<Double> getCacheCurrentRequestedMips() {
        return cacheCurrentRequestedMips;
    }

    protected void setCacheCurrentRequestedMips(List<Double> cacheCurrentRequestedMips) {
        this.cacheCurrentRequestedMips = cacheCurrentRequestedMips;
    }
}
