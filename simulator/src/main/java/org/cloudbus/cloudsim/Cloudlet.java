/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.core.CloudSim;

public class Cloudlet {

    private int userId;

    private long cloudletLength;

    private final long cloudletFileSize;

    private final long cloudletOutputSize;

    private int numberOfPes;

    private final int cloudletId;

    private int status;

    private DecimalFormat num;

    private double finishTime;

    private double execStartTime;

    private int reservationId = -1;

    private final boolean record;

    private String newline;

    private StringBuffer history;

    private final List<Resource> resList;

    private int index;

    private int classType;

    private int netToS;

    public static final int CREATED = 0;

    public static final int READY = 1;

    public static final int QUEUED = 2;

    public static final int INEXEC = 3;

    public static final int SUCCESS = 4;

    public static final int FAILED = 5;

    public static final int CANCELED = 6;

    public static final int PAUSED = 7;

    public static final int RESUMED = 8;

    public static final int FAILED_RESOURCE_UNAVAILABLE = 9;

    protected int vmId;

    protected double costPerBw;

    protected double accumulatedBwCost;

    private UtilizationModel utilizationModelCpu;

    private UtilizationModel utilizationModelRam;

    private UtilizationModel utilizationModelBw;

    private List<String> requiredFiles = null;

    public Cloudlet(
            final int cloudletId,
            final long cloudletLength,
            final int pesNumber,
            final long cloudletFileSize,
            final long cloudletOutputSize,
            final UtilizationModel utilizationModelCpu,
            final UtilizationModel utilizationModelRam,
            final UtilizationModel utilizationModelBw) {
        this(
                cloudletId,
                cloudletLength,
                pesNumber,
                cloudletFileSize,
                cloudletOutputSize,
                utilizationModelCpu,
                utilizationModelRam,
                utilizationModelBw,
                false);
        vmId = -1;
        accumulatedBwCost = 0.0;
        costPerBw = 0.0;

        requiredFiles = new LinkedList<String>();
    }

    public Cloudlet(
            final int cloudletId,
            final long cloudletLength,
            final int pesNumber,
            final long cloudletFileSize,
            final long cloudletOutputSize,
            final UtilizationModel utilizationModelCpu,
            final UtilizationModel utilizationModelRam,
            final UtilizationModel utilizationModelBw,
            final boolean record,
            final List<String> fileList) {
        this(
                cloudletId,
                cloudletLength,
                pesNumber,
                cloudletFileSize,
                cloudletOutputSize,
                utilizationModelCpu,
                utilizationModelRam,
                utilizationModelBw,
                record);
        vmId = -1;
        accumulatedBwCost = 0.0;
        costPerBw = 0.0;

        requiredFiles = fileList;
    }

    public Cloudlet(
            final int cloudletId,
            final long cloudletLength,
            final int pesNumber,
            final long cloudletFileSize,
            final long cloudletOutputSize,
            final UtilizationModel utilizationModelCpu,
            final UtilizationModel utilizationModelRam,
            final UtilizationModel utilizationModelBw,
            final List<String> fileList) {
        this(
                cloudletId,
                cloudletLength,
                pesNumber,
                cloudletFileSize,
                cloudletOutputSize,
                utilizationModelCpu,
                utilizationModelRam,
                utilizationModelBw,
                false);
        vmId = -1;
        accumulatedBwCost = 0.0;
        costPerBw = 0.0;

        requiredFiles = fileList;
    }

    public Cloudlet(
            final int cloudletId,
            final long cloudletLength,
            final int pesNumber,
            final long cloudletFileSize,
            final long cloudletOutputSize,
            final UtilizationModel utilizationModelCpu,
            final UtilizationModel utilizationModelRam,
            final UtilizationModel utilizationModelBw,
            final boolean record) {
        userId = -1;
        status = CREATED;
        this.cloudletId = cloudletId;
        numberOfPes = pesNumber;
        execStartTime = 0.0;
        finishTime = -1.0;
        classType = 0;
        netToS = 0;

        this.cloudletLength = Math.max(1, cloudletLength);
        this.cloudletFileSize = Math.max(1, cloudletFileSize);
        this.cloudletOutputSize = Math.max(1, cloudletOutputSize);

        resList = new ArrayList<Resource>(2);
        index = -1;
        this.record = record;

        vmId = -1;
        accumulatedBwCost = 0.0;
        costPerBw = 0.0;

        requiredFiles = new LinkedList<String>();

        setUtilizationModelCpu(utilizationModelCpu);
        setUtilizationModelRam(utilizationModelRam);
        setUtilizationModelBw(utilizationModelBw);
    }

    private static class Resource {

        public double submissionTime = 0.0;

        public double wallClockTime = 0.0;

        public double actualCPUTime = 0.0;

        public double costPerSec = 0.0;

        public long finishedSoFar = 0;

        public int resourceId = -1;

        public String resourceName = null;
    }

    public boolean setReservationId(final int resId) {
        if (resId <= 0) {
            return false;
        }
        reservationId = resId;
        return true;
    }

    public int getReservationId() {
        return reservationId;
    }

    public boolean hasReserved() {
        if (reservationId == -1) {
            return false;
        }
        return true;
    }

    public boolean setCloudletLength(final long cloudletLength) {
        if (cloudletLength <= 0) {
            return false;
        }

        this.cloudletLength = cloudletLength;
        return true;
    }

    public boolean setNetServiceLevel(final int netServiceLevel) {
        boolean success = false;
        if (netServiceLevel > 0) {
            netToS = netServiceLevel;
            success = true;
        }

        return success;
    }

    public int getNetServiceLevel() {
        return netToS;
    }

    public double getWaitingTime() {
        if (index == -1) {
            return 0;
        }

        final double subTime = resList.get(index).submissionTime;
        return execStartTime - subTime;
    }

    public boolean setClassType(final int classType) {
        boolean success = false;
        if (classType > 0) {
            this.classType = classType;
            success = true;
        }

        return success;
    }

    public int getClassType() {
        return classType;
    }

    public boolean setNumberOfPes(final int numberOfPes) {
        if (numberOfPes > 0) {
            this.numberOfPes = numberOfPes;
            return true;
        }
        return false;
    }

    public int getNumberOfPes() {
        return numberOfPes;
    }

    public String getCloudletHistory() {
        String msg = null;
        if (history == null) {
            msg = "No history is recorded for Cloudlet #" + cloudletId;
        } else {
            msg = history.toString();
        }

        return msg;
    }

    public long getCloudletFinishedSoFar() {
        if (index == -1) {
            return cloudletLength;
        }

        final long finish = resList.get(index).finishedSoFar;
        if (finish > cloudletLength) {
            return cloudletLength;
        }

        return finish;
    }

    public boolean isFinished() {
        if (index == -1) {
            return false;
        }

        boolean completed = false;

        final long finish = resList.get(index).finishedSoFar;
        final long result = cloudletLength - finish;
        if (result <= 0.0) {
            completed = true;
        }
        return completed;
    }

    public void setCloudletFinishedSoFar(final long length) {

        if (length < 0.0 || index < 0) {
            return;
        }

        final Resource res = resList.get(index);
        res.finishedSoFar = length;

        if (record) {
            write("Sets the length's finished so far to " + length);
        }
    }

    public void setUserId(final int id) {
        userId = id;
        if (record) {
            write("Assigns the Cloudlet to " + CloudSim.getEntityName(id) + " (ID #" + id + ")");
        }
    }

    public int getUserId() {
        return userId;
    }

    public int getResourceId() {
        if (index == -1) {
            return -1;
        }
        return resList.get(index).resourceId;
    }

    public long getCloudletFileSize() {
        return cloudletFileSize;
    }

    public long getCloudletOutputSize() {
        return cloudletOutputSize;
    }

    public void setResourceParameter(final int resourceID, final double cost) {
        final Resource res = new Resource();
        res.resourceId = resourceID;
        res.costPerSec = cost;
        res.resourceName = CloudSim.getEntityName(resourceID);

        resList.add(res);

        if (index == -1 && record) {
            write("Allocates this Cloudlet to " + res.resourceName + " (ID #" + resourceID + ") with cost = $" + cost
                    + "/sec");
        } else if (record) {
            final int id = resList.get(index).resourceId;
            final String name = resList.get(index).resourceName;
            write("Moves Cloudlet from " + name + " (ID #" + id + ") to " + res.resourceName + " (ID #" + resourceID
                    + ") with cost = $" + cost + "/sec");
        }

        index++;
    }

    public void setSubmissionTime(final double clockTime) {
        if (clockTime < 0.0 || index < 0) {
            return;
        }

        final Resource res = resList.get(index);
        res.submissionTime = clockTime;

        if (record) {
            write("Sets the submission time to " + num.format(clockTime));
        }
    }

    public double getSubmissionTime() {
        if (index == -1) {
            return 0.0;
        }
        return resList.get(index).submissionTime;
    }

    public void setExecStartTime(final double clockTime) {
        execStartTime = clockTime;
        if (record) {
            write("Sets the execution start time to " + num.format(clockTime));
        }
    }

    public double getExecStartTime() {
        return execStartTime;
    }

    public void setExecParam(final double wallTime, final double actualTime) {
        if (wallTime < 0.0 || actualTime < 0.0 || index < 0) {
            return;
        }

        final Resource res = resList.get(index);
        res.wallClockTime = wallTime;
        res.actualCPUTime = actualTime;

        if (record) {
            write("Sets the wall clock time to " + num.format(wallTime) + " and the actual CPU time to "
                    + num.format(actualTime));
        }
    }

    public void setCloudletStatus(final int newStatus) throws Exception {

        if (status == newStatus) {
            return;
        }

        if (newStatus < Cloudlet.CREATED || newStatus > Cloudlet.FAILED_RESOURCE_UNAVAILABLE) {
            throw new Exception("Cloudlet.setCloudletStatus() : Error - Invalid integer range for Cloudlet status.");
        }

        if (newStatus == Cloudlet.SUCCESS) {
            finishTime = CloudSim.clock();
        }

        if (record) {
            write("Sets Cloudlet status from " + getCloudletStatusString() + " to "
                    + Cloudlet.getStatusString(newStatus));
        }

        status = newStatus;
    }

    public int getCloudletStatus() {
        return status;
    }

    public String getCloudletStatusString() {
        return Cloudlet.getStatusString(status);
    }

    public static String getStatusString(final int status) {
        String statusString = null;
        switch (status) {
            case Cloudlet.CREATED:
                statusString = "Created";
                break;

            case Cloudlet.READY:
                statusString = "Ready";
                break;

            case Cloudlet.INEXEC:
                statusString = "InExec";
                break;

            case Cloudlet.SUCCESS:
                statusString = "Success";
                break;

            case Cloudlet.QUEUED:
                statusString = "Queued";
                break;

            case Cloudlet.FAILED:
                statusString = "Failed";
                break;

            case Cloudlet.CANCELED:
                statusString = "Canceled";
                break;

            case Cloudlet.PAUSED:
                statusString = "Paused";
                break;

            case Cloudlet.RESUMED:
                statusString = "Resumed";
                break;

            case Cloudlet.FAILED_RESOURCE_UNAVAILABLE:
                statusString = "Failed_resource_unavailable";
                break;

            default:
                break;
        }

        return statusString;
    }

    public long getCloudletLength() {
        return cloudletLength;
    }

    public long getCloudletTotalLength() {
        return getCloudletLength() * getNumberOfPes();
    }

    public double getCostPerSec() {
        if (index == -1) {
            return 0.0;
        }
        return resList.get(index).costPerSec;
    }

    public double getWallClockTime() {
        if (index == -1) {
            return 0.0;
        }
        return resList.get(index).wallClockTime;
    }

    public String[] getAllResourceName() {
        final int size = resList.size();
        String[] data = null;

        if (size > 0) {
            data = new String[size];
            for (int i = 0; i < size; i++) {
                data[i] = resList.get(i).resourceName;
            }
        }

        return data;
    }

    public int[] getAllResourceId() {
        final int size = resList.size();
        int[] data = null;

        if (size > 0) {
            data = new int[size];
            for (int i = 0; i < size; i++) {
                data[i] = resList.get(i).resourceId;
            }
        }

        return data;
    }

    public double getActualCPUTime(final int resId) {
        Resource resource = getResourceById(resId);
        if (resource != null) {
            return resource.actualCPUTime;
        }
        return 0.0;
    }

    public double getCostPerSec(final int resId) {
        Resource resource = getResourceById(resId);
        if (resource != null) {
            return resource.costPerSec;
        }
        return 0.0;
    }

    public long getCloudletFinishedSoFar(final int resId) {
        Resource resource = getResourceById(resId);
        if (resource != null) {
            return resource.finishedSoFar;
        }
        return 0;
    }

    public double getSubmissionTime(final int resId) {
        Resource resource = getResourceById(resId);
        if (resource != null) {
            return resource.submissionTime;
        }
        return 0.0;
    }

    public double getWallClockTime(final int resId) {
        Resource resource = getResourceById(resId);
        if (resource != null) {
            return resource.wallClockTime;
        }
        return 0.0;
    }

    public String getResourceName(final int resId) {
        Resource resource = getResourceById(resId);
        if (resource != null) {
            return resource.resourceName;
        }
        return null;
    }

    public Resource getResourceById(final int resourceId) {
        for (Resource resource : resList) {
            if (resource.resourceId == resourceId) {
                return resource;
            }
        }
        return null;
    }

    public double getFinishTime() {
        return finishTime;
    }

    protected void write(final String str) {
        if (!record) {
            return;
        }

        if (num == null || history == null) {

            newline = System.getProperty("line.separator");
            num = new DecimalFormat("#0.00#");
            history = new StringBuffer(1000);
            history.append("Time below denotes the simulation time.");
            history.append(System.getProperty("line.separator"));
            history.append("Time (sec)       Description Cloudlet #" + cloudletId);
            history.append(System.getProperty("line.separator"));
            history.append("------------------------------------------");
            history.append(System.getProperty("line.separator"));
            history.append(num.format(CloudSim.clock()));
            history.append("   Creates Cloudlet ID #" + cloudletId);
            history.append(System.getProperty("line.separator"));
        }

        history.append(num.format(CloudSim.clock()));
        history.append("   " + str + newline);
    }

    public int getStatus() {
        return getCloudletStatus();
    }

    public int getCloudletId() {
        return cloudletId;
    }

    public int getVmId() {
        return vmId;
    }

    public void setVmId(final int vmId) {
        this.vmId = vmId;
    }

    public double getActualCPUTime() {
        return getFinishTime() - getExecStartTime();
    }

    public void setResourceParameter(final int resourceID, final double costPerCPU, final double costPerBw) {
        setResourceParameter(resourceID, costPerCPU);
        this.costPerBw = costPerBw;
        accumulatedBwCost = costPerBw * getCloudletFileSize();
    }

    public double getProcessingCost() {

        double cost = 0;

        cost += accumulatedBwCost;

        cost += costPerBw * getCloudletOutputSize();
        return cost;
    }

    public List<String> getRequiredFiles() {
        return requiredFiles;
    }

    protected void setRequiredFiles(final List<String> requiredFiles) {
        this.requiredFiles = requiredFiles;
    }

    public boolean addRequiredFile(final String fileName) {

        if (getRequiredFiles() == null) {
            setRequiredFiles(new LinkedList<String>());
        }

        boolean result = false;
        for (int i = 0; i < getRequiredFiles().size(); i++) {
            final String temp = getRequiredFiles().get(i);
            if (temp.equals(fileName)) {
                result = true;
                break;
            }
        }

        if (!result) {
            getRequiredFiles().add(fileName);
        }

        return result;
    }

    public boolean deleteRequiredFile(final String filename) {
        boolean result = false;
        if (getRequiredFiles() == null) {
            return result;
        }

        for (int i = 0; i < getRequiredFiles().size(); i++) {
            final String temp = getRequiredFiles().get(i);

            if (temp.equals(filename)) {
                getRequiredFiles().remove(i);
                result = true;

                break;
            }
        }

        return result;
    }

    public boolean requiresFiles() {
        boolean result = false;
        if (getRequiredFiles() != null && getRequiredFiles().size() > 0) {
            result = true;
        }

        return result;
    }

    public UtilizationModel getUtilizationModelCpu() {
        return utilizationModelCpu;
    }

    public void setUtilizationModelCpu(final UtilizationModel utilizationModelCpu) {
        this.utilizationModelCpu = utilizationModelCpu;
    }

    public UtilizationModel getUtilizationModelRam() {
        return utilizationModelRam;
    }

    public void setUtilizationModelRam(final UtilizationModel utilizationModelRam) {
        this.utilizationModelRam = utilizationModelRam;
    }

    public UtilizationModel getUtilizationModelBw() {
        return utilizationModelBw;
    }

    public void setUtilizationModelBw(final UtilizationModel utilizationModelBw) {
        this.utilizationModelBw = utilizationModelBw;
    }

    public double getUtilizationOfCpu(final double time) {
        return getUtilizationModelCpu().getUtilization(time);
    }

    public double getUtilizationOfRam(final double time) {
        return getUtilizationModelRam().getUtilization(time);
    }

    public double getUtilizationOfBw(final double time) {
        return getUtilizationModelBw().getUtilization(time);
    }
}
