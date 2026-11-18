/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import java.util.List;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.lists.HostList;
import org.cloudbus.cloudsim.lists.PeList;

public class DatacenterCharacteristics {

    private int id;

    private String architecture;

    private String os;

    private List<? extends Host> hostList;

    private double timeZone;

    private double costPerSecond;

    private int allocationPolicy;

    public static final int TIME_SHARED = 0;

    public static final int SPACE_SHARED = 1;

    public static final int OTHER_POLICY_SAME_RATING = 2;

    public static final int OTHER_POLICY_DIFFERENT_RATING = 3;

    public static final int ADVANCE_RESERVATION = 4;

    private String vmm;

    private double costPerMem;

    private double costPerStorage;

    private double costPerBw;

    public DatacenterCharacteristics(
            String architecture,
            String os,
            String vmm,
            List<? extends Host> hostList,
            double timeZone,
            double costPerSec,
            double costPerMem,
            double costPerStorage,
            double costPerBw) {
        setId(-1);
        setArchitecture(architecture);
        setOs(os);
        setHostList(hostList);
        setAllocationPolicy(allocationPolicy);
        setCostPerSecond(costPerSec);

        setTimeZone(0.0);

        setVmm(vmm);
        setCostPerMem(costPerMem);
        setCostPerStorage(costPerStorage);
        setCostPerBw(costPerBw);
    }

    public String getResourceName() {
        return CloudSim.getEntityName(getId());
    }

    public Host getHostWithFreePe() {
        return HostList.getHostWithFreePe(getHostList());
    }

    public Host getHostWithFreePe(int peNumber) {
        return HostList.getHostWithFreePe(getHostList(), peNumber);
    }

    public int getMipsOfOnePe() {
        if (getHostList().size() == 0) {
            return -1;
        }

        return PeList.getMips(getHostList().get(0).getPeList(), 0);
    }

    public int getMipsOfOnePe(int id, int peId) {
        if (getHostList().size() == 0) {
            return -1;
        }

        return PeList.getMips(HostList.getById(getHostList(), id).getPeList(), peId);
    }

    public int getMips() {
        int mips = 0;
        switch (getAllocationPolicy()) {
            case DatacenterCharacteristics.TIME_SHARED:
            case DatacenterCharacteristics.OTHER_POLICY_SAME_RATING:
                mips = getMipsOfOnePe() * HostList.getNumberOfPes(getHostList());
                break;

            case DatacenterCharacteristics.SPACE_SHARED:
            case DatacenterCharacteristics.OTHER_POLICY_DIFFERENT_RATING:
                for (Host host : getHostList()) {
                    mips += host.getTotalMips();
                }
                break;

            default:
                break;
        }

        return mips;
    }

    public double getCpuTime(double cloudletLength, double load) {
        double cpuTime = 0.0;

        switch (getAllocationPolicy()) {
            case DatacenterCharacteristics.TIME_SHARED:
                cpuTime = cloudletLength / (getMipsOfOnePe() * (1.0 - load));
                break;

            default:
                break;
        }

        return cpuTime;
    }

    public int getNumberOfPes() {
        return HostList.getNumberOfPes(getHostList());
    }

    public int getNumberOfFreePes() {
        return HostList.getNumberOfFreePes(getHostList());
    }

    public int getNumberOfBusyPes() {
        return HostList.getNumberOfBusyPes(getHostList());
    }

    public boolean setPeStatus(int status, int hostId, int peId) {
        return HostList.setPeStatus(getHostList(), status, hostId, peId);
    }

    public double getCostPerMi() {
        return getCostPerSecond() / getMipsOfOnePe();
    }

    public int getNumberOfHosts() {
        return getHostList().size();
    }

    public int getNumberOfFailedHosts() {
        int numberOfFailedHosts = 0;
        for (Host host : getHostList()) {
            if (host.isFailed()) {
                numberOfFailedHosts++;
            }
        }
        return numberOfFailedHosts;
    }

    public boolean isWorking() {
        boolean result = false;
        if (getNumberOfFailedHosts() == 0) {
            result = true;
        }

        return result;
    }

    public double getCostPerMem() {
        return costPerMem;
    }

    public void setCostPerMem(double costPerMem) {
        this.costPerMem = costPerMem;
    }

    public double getCostPerStorage() {
        return costPerStorage;
    }

    public void setCostPerStorage(double costPerStorage) {
        this.costPerStorage = costPerStorage;
    }

    public double getCostPerBw() {
        return costPerBw;
    }

    public void setCostPerBw(double costPerBw) {
        this.costPerBw = costPerBw;
    }

    public String getVmm() {
        return vmm;
    }

    public int getId() {
        return id;
    }

    protected void setId(int id) {
        this.id = id;
    }

    protected String getArchitecture() {
        return architecture;
    }

    protected void setArchitecture(String architecture) {
        this.architecture = architecture;
    }

    protected String getOs() {
        return os;
    }

    protected void setOs(String os) {
        this.os = os;
    }

    @SuppressWarnings("unchecked")
    public <T extends Host> List<T> getHostList() {
        return (List<T>) hostList;
    }

    protected <T extends Host> void setHostList(List<T> hostList) {
        this.hostList = hostList;
    }

    protected double getTimeZone() {
        return timeZone;
    }

    protected void setTimeZone(double timeZone) {
        this.timeZone = timeZone;
    }

    public double getCostPerSecond() {
        return costPerSecond;
    }

    protected void setCostPerSecond(double costPerSecond) {
        this.costPerSecond = costPerSecond;
    }

    protected int getAllocationPolicy() {
        return allocationPolicy;
    }

    protected void setAllocationPolicy(int allocationPolicy) {
        this.allocationPolicy = allocationPolicy;
    }

    protected void setVmm(String vmm) {
        this.vmm = vmm;
    }
}
