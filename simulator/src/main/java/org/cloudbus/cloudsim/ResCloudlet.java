/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import org.cloudbus.cloudsim.core.CloudSim;

public class ResCloudlet {

    private final Cloudlet cloudlet;

    private double arrivalTime;

    private double finishedTime;

    private long cloudletFinishedSoFar;

    private double startExecTime;

    private double totalCompletionTime;

    private int machineId;

    private int peId;

    private int[] machineArrayId = null;

    private int[] peArrayId = null;

    private int index;

    private static final int NOT_FOUND = -1;

    private final long startTime;

    private final int duration;

    private final int reservId;

    private int pesNumber;

    public ResCloudlet(Cloudlet cloudlet) {

        this.cloudlet = cloudlet;
        startTime = 0;
        reservId = NOT_FOUND;
        duration = 0;

        init();
    }

    public ResCloudlet(Cloudlet cloudlet, long startTime, int duration, int reservID) {
        this.cloudlet = cloudlet;
        this.startTime = startTime;
        reservId = reservID;
        this.duration = duration;

        init();
    }

    public long getStartTime() {
        return startTime;
    }

    public int getDurationTime() {
        return duration;
    }

    public int getNumberOfPes() {
        return pesNumber;
    }

    public int getReservationID() {
        return reservId;
    }

    public boolean hasReserved() {
        if (reservId == NOT_FOUND) {
            return false;
        }

        return true;
    }

    private void init() {
        long totalInstructions = totalInstructions();

        pesNumber = cloudlet.getNumberOfPes();

        if (pesNumber > 1) {
            machineArrayId = new int[pesNumber];
            peArrayId = new int[pesNumber];
        }

        arrivalTime = CloudSim.clock();
        cloudlet.setSubmissionTime(arrivalTime);

        finishedTime = NOT_FOUND;
        machineId = NOT_FOUND;
        peId = NOT_FOUND;
        index = 0;
        totalCompletionTime = 0.0;
        startExecTime = 0.0;

        long completedMi =
                Math.min(Math.max(0L, cloudlet.getCloudletFinishedSoFar()), totalInstructions / Consts.MILLION);
        cloudletFinishedSoFar = completedMi * Consts.MILLION;
    }

    public int getCloudletId() {
        return cloudlet.getCloudletId();
    }

    public int getUserId() {
        return cloudlet.getUserId();
    }

    public long getCloudletLength() {
        return cloudlet.getCloudletLength();
    }

    public long getCloudletTotalLength() {
        return cloudlet.getCloudletTotalLength();
    }

    public int getCloudletClassType() {
        return cloudlet.getClassType();
    }

    public boolean setCloudletStatus(int status) {

        int prevStatus = cloudlet.getCloudletStatus();

        if (prevStatus == status) {
            return false;
        }

        boolean success = true;
        try {
            double clock = CloudSim.clock();

            cloudlet.setCloudletStatus(status);

            if (prevStatus == Cloudlet.INEXEC) {

                if (status == Cloudlet.CANCELED || status == Cloudlet.PAUSED || status == Cloudlet.SUCCESS) {

                    totalCompletionTime += (clock - startExecTime);
                    index = 0;
                    return true;
                }
            }

            if (prevStatus == Cloudlet.RESUMED && status == Cloudlet.SUCCESS) {

                totalCompletionTime += (clock - startExecTime);
                return true;
            }

            if (status == Cloudlet.INEXEC || (prevStatus == Cloudlet.PAUSED && status == Cloudlet.RESUMED)) {
                startExecTime = clock;
                cloudlet.setExecStartTime(startExecTime);
            }

        } catch (Exception e) {
            success = false;
        }

        return success;
    }

    public double getExecStartTime() {
        return cloudlet.getExecStartTime();
    }

    public void setExecParam(double wallClockTime, double actualCPUTime) {
        cloudlet.setExecParam(wallClockTime, actualCPUTime);
    }

    public void setMachineAndPeId(int machineId, int peId) {

        this.machineId = machineId;
        this.peId = peId;

        if (peArrayId != null && pesNumber > 1) {
            machineArrayId[index] = machineId;
            peArrayId[index] = peId;
            index++;
        }
    }

    public int getMachineId() {
        return machineId;
    }

    public int getPeId() {
        return peId;
    }

    public int[] getPeIdList() {
        return peArrayId;
    }

    public int[] getMachineIdList() {
        return machineArrayId;
    }

    public long getRemainingCloudletLength() {
        long length = Math.max(0L, totalInstructions() - cloudletFinishedSoFar);
        return length / Consts.MILLION;
    }

    private long totalInstructions() {
        if (cloudlet == null || cloudlet.getCloudletLength() <= 0L || cloudlet.getNumberOfPes() <= 0) {
            throw new IllegalArgumentException("Cloudlet work and processing elements must be positive");
        }
        try {
            long totalMi = Math.multiplyExact(cloudlet.getCloudletLength(), (long) cloudlet.getNumberOfPes());
            return Math.multiplyExact(totalMi, (long) Consts.MILLION);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "Cloudlet " + cloudlet.getCloudletId() + " work cannot be represented as signed-long instructions",
                    overflow);
        }
    }

    public void finalizeCloudlet() {

        double wallClockTime = CloudSim.clock() - arrivalTime;
        cloudlet.setExecParam(wallClockTime, totalCompletionTime);

        long finished = 0;

        if (cloudlet.getCloudletStatus() == Cloudlet.SUCCESS) {
            finished = cloudlet.getCloudletLength();
        } else {
            finished = cloudletFinishedSoFar / Consts.MILLION;
        }

        cloudlet.setCloudletFinishedSoFar(finished);
    }

    public void updateCloudletFinishedSoFar(long miLength) {
        if (miLength < 0L) {
            throw new IllegalArgumentException("Cloudlet progress cannot be negative");
        }
        long remaining = Math.max(0L, totalInstructions() - cloudletFinishedSoFar);

        cloudletFinishedSoFar += Math.min(miLength, remaining);
    }

    public double getCloudletArrivalTime() {
        return arrivalTime;
    }

    public void setFinishTime(double time) {
        if (time < 0.0) {
            return;
        }

        finishedTime = time;
    }

    public double getClouddletFinishTime() {
        return finishedTime;
    }

    public Cloudlet getCloudlet() {
        return cloudlet;
    }

    public int getCloudletStatus() {
        return cloudlet.getCloudletStatus();
    }

    public String getUid() {
        return getUserId() + "-" + getCloudletId();
    }
}
