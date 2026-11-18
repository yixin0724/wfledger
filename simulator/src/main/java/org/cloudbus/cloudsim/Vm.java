/*
 * Title: CloudSim Toolkit Description: CloudSim (Cloud Simulation) Toolkit for Modeling and
 * Simulation of Clouds Licence: GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class Vm {

    private int id;

    private int userId;

    private String uid;

    private long size;

    private double mips;

    private int numberOfPes;

    private int ram;

    private long bw;

    private String vmm;

    private CloudletScheduler cloudletScheduler;

    private Host host;

    private boolean inMigration;

    private long currentAllocatedSize;

    private int currentAllocatedRam;

    private long currentAllocatedBw;

    private List<Double> currentAllocatedMips;

    private boolean beingInstantiated;

    private final List<VmStateHistoryEntry> stateHistory = new LinkedList<VmStateHistoryEntry>();

    public Vm(
            int id,
            int userId,
            double mips,
            int numberOfPes,
            int ram,
            long bw,
            long size,
            String vmm,
            CloudletScheduler cloudletScheduler) {
        setId(id);
        setUserId(userId);
        setUid(getUid(userId, id));
        setMips(mips);
        setNumberOfPes(numberOfPes);
        setRam(ram);
        setBw(bw);
        setSize(size);
        setVmm(vmm);
        setCloudletScheduler(cloudletScheduler);

        setInMigration(false);
        setBeingInstantiated(true);

        setCurrentAllocatedBw(0);
        setCurrentAllocatedMips(null);
        setCurrentAllocatedRam(0);
        setCurrentAllocatedSize(0);
    }

    public double updateVmProcessing(double currentTime, List<Double> mipsShare) {
        if (mipsShare != null) {
            return getCloudletScheduler().updateVmProcessing(currentTime, mipsShare);
        }
        return 0.0;
    }

    public List<Double> getCurrentRequestedMips() {
        List<Double> currentRequestedMips = getCloudletScheduler().getCurrentRequestedMips();
        if (isBeingInstantiated()) {
            currentRequestedMips = new ArrayList<Double>();
            for (int i = 0; i < getNumberOfPes(); i++) {
                currentRequestedMips.add(getMips());
            }
        }
        return currentRequestedMips;
    }

    public double getCurrentRequestedTotalMips() {
        double totalRequestedMips = 0;
        for (double mips : getCurrentRequestedMips()) {
            totalRequestedMips += mips;
        }
        return totalRequestedMips;
    }

    public double getCurrentRequestedMaxMips() {
        double maxMips = 0;
        for (double mips : getCurrentRequestedMips()) {
            if (mips > maxMips) {
                maxMips = mips;
            }
        }
        return maxMips;
    }

    public long getCurrentRequestedBw() {
        if (isBeingInstantiated()) {
            return getBw();
        }
        return (long) (getCloudletScheduler().getCurrentRequestedUtilizationOfBw() * getBw());
    }

    public int getCurrentRequestedRam() {
        if (isBeingInstantiated()) {
            return getRam();
        }
        return (int) (getCloudletScheduler().getCurrentRequestedUtilizationOfRam() * getRam());
    }

    public double getTotalUtilizationOfCpu(double time) {
        return getCloudletScheduler().getTotalUtilizationOfCpu(time);
    }

    public double getTotalUtilizationOfCpuMips(double time) {
        return getTotalUtilizationOfCpu(time) * getMips();
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getUid() {
        return uid;
    }

    public static String getUid(int userId, int vmId) {
        return userId + "-" + vmId;
    }

    public int getId() {
        return id;
    }

    protected void setId(int id) {
        this.id = id;
    }

    protected void setUserId(int userId) {
        this.userId = userId;
    }

    public int getUserId() {
        return userId;
    }

    public double getMips() {
        return mips;
    }

    protected void setMips(double mips) {
        this.mips = mips;
    }

    public int getNumberOfPes() {
        return numberOfPes;
    }

    protected void setNumberOfPes(int numberOfPes) {
        this.numberOfPes = numberOfPes;
    }

    public int getRam() {
        return ram;
    }

    public void setRam(int ram) {
        this.ram = ram;
    }

    public long getBw() {
        return bw;
    }

    public void setBw(long bw) {
        this.bw = bw;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getVmm() {
        return vmm;
    }

    protected void setVmm(String vmm) {
        this.vmm = vmm;
    }

    public void setHost(Host host) {
        this.host = host;
    }

    public Host getHost() {
        return host;
    }

    public CloudletScheduler getCloudletScheduler() {
        return cloudletScheduler;
    }

    protected void setCloudletScheduler(CloudletScheduler cloudletScheduler) {
        this.cloudletScheduler = cloudletScheduler;
    }

    public boolean isInMigration() {
        return inMigration;
    }

    public void setInMigration(boolean inMigration) {
        this.inMigration = inMigration;
    }

    public long getCurrentAllocatedSize() {
        return currentAllocatedSize;
    }

    protected void setCurrentAllocatedSize(long currentAllocatedSize) {
        this.currentAllocatedSize = currentAllocatedSize;
    }

    public int getCurrentAllocatedRam() {
        return currentAllocatedRam;
    }

    public void setCurrentAllocatedRam(int currentAllocatedRam) {
        this.currentAllocatedRam = currentAllocatedRam;
    }

    public long getCurrentAllocatedBw() {
        return currentAllocatedBw;
    }

    public void setCurrentAllocatedBw(long currentAllocatedBw) {
        this.currentAllocatedBw = currentAllocatedBw;
    }

    public List<Double> getCurrentAllocatedMips() {
        return currentAllocatedMips;
    }

    public void setCurrentAllocatedMips(List<Double> currentAllocatedMips) {
        this.currentAllocatedMips = currentAllocatedMips;
    }

    public boolean isBeingInstantiated() {
        return beingInstantiated;
    }

    public void setBeingInstantiated(boolean beingInstantiated) {
        this.beingInstantiated = beingInstantiated;
    }

    public List<VmStateHistoryEntry> getStateHistory() {
        return stateHistory;
    }

    public void addStateHistoryEntry(double time, double allocatedMips, double requestedMips, boolean isInMigration) {
        VmStateHistoryEntry newState = new VmStateHistoryEntry(time, allocatedMips, requestedMips, isInMigration);
        if (!getStateHistory().isEmpty()) {
            VmStateHistoryEntry previousState =
                    getStateHistory().get(getStateHistory().size() - 1);
            if (previousState.getTime() == time) {
                getStateHistory().set(getStateHistory().size() - 1, newState);
                return;
            }
        }
        getStateHistory().add(newState);
    }
}
