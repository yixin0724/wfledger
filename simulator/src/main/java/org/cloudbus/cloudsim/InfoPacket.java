/*
 * ** Network and Service Differentiation Extensions to CloudSim 3.0 **
 *
 * Gokul Poduval & Chen-Khong Tham
 * Computer Communication Networks (CCN) Lab
 * Dept of Electrical & Computer Engineering
 * National University of Singapore
 * August 2004
 *
 * Licence: GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2004, The University of Melbourne, Australia and National
 * University of Singapore
 * InfoPacket.java - Implementation of a Information Packet.
 *
 */

package org.cloudbus.cloudsim;

import java.text.DecimalFormat;
import java.util.Vector;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;

public class InfoPacket implements Packet {

    private final String name;

    private long size;

    private final int packetId;

    private final int srcId;

    private int destId;

    private int last;

    private int tag;

    private int hopsNumber;

    private long pingSize;

    private int netServiceType;

    private double bandwidth;

    private Vector<Integer> entities;

    private Vector<Double> entryTimes;

    private Vector<Double> exitTimes;

    private Vector<Double> baudRates;

    private DecimalFormat num;

    public InfoPacket(String name, int packetID, long size, int srcID, int destID, int netServiceType) {
        this.name = name;
        packetId = packetID;
        srcId = srcID;
        destId = destID;
        this.size = size;
        this.netServiceType = netServiceType;

        init();
    }

    private void init() {
        last = srcId;
        tag = CloudSimTags.INFOPKT_SUBMIT;
        bandwidth = -1;
        hopsNumber = 0;
        pingSize = size;

        if (name != null) {
            entities = new Vector<Integer>();
            entryTimes = new Vector<Double>();
            exitTimes = new Vector<Double>();
            baudRates = new Vector<Double>();
            num = new DecimalFormat("#0.000#");
        }
    }

    @Override
    public int getId() {
        return packetId;
    }

    public void setOriginalPingSize(long size) {
        pingSize = size;
    }

    public long getOriginalPingSize() {
        return pingSize;
    }

    @Override
    public String toString() {
        if (name == null) {
            return "Empty InfoPacket that contains no ping information.";
        }

        int SIZE = 1000;
        StringBuffer sb = new StringBuffer(SIZE);
        sb.append("Ping information for " + name + "\n");
        sb.append("Entity Name\tEntry Time\tExit Time\t Bandwidth\n");
        sb.append("----------------------------------------------------------\n");

        String tab = "    ";
        for (int i = 0; i < entities.size(); i++) {
            int resID = entities.get(i).intValue();
            sb.append(CloudSim.getEntityName(resID) + "\t\t");

            String entry = getData(entryTimes, i);
            String exit = getData(exitTimes, i);
            String bw = getData(baudRates, i);

            sb.append(entry + tab + tab + exit + tab + tab + bw + "\n");
        }

        sb.append("\nRound Trip Time : " + num.format(getTotalResponseTime()));
        sb.append(" seconds");
        sb.append("\nNumber of Hops  : " + getNumHop());
        sb.append("\nBottleneck Bandwidth : " + bandwidth + " bits/s");
        return sb.toString();
    }

    private String getData(Vector<Double> v, int index) {
        String result;
        try {
            Double obj = v.get(index);
            double id = obj.doubleValue();
            result = num.format(id);
        } catch (Exception e) {
            result = "    N/A";
        }

        return result;
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public boolean setSize(long size) {
        if (size < 0) {
            return false;
        }

        this.size = size;
        return true;
    }

    @Override
    public int getDestId() {
        return destId;
    }

    @Override
    public int getSrcId() {
        return srcId;
    }

    public int getNumHop() {
        int PAIR = 2;
        return ((hopsNumber - PAIR) + 1) / PAIR;
    }

    public double getTotalResponseTime() {
        if (exitTimes == null || entryTimes == null) {
            return 0;
        }

        double time = 0;
        try {
            double startTime = exitTimes.firstElement().doubleValue();
            double receiveTime = entryTimes.lastElement().doubleValue();
            time = receiveTime - startTime;
        } catch (Exception e) {
            time = 0;
        }

        return time;
    }

    public double getBaudRate() {
        return bandwidth;
    }

    public void addHop(int id) {
        if (entities == null) {
            return;
        }

        hopsNumber++;
        entities.add(Integer.valueOf(id));
    }

    public void addEntryTime(double time) {
        if (entryTimes == null) {
            return;
        }

        if (time < 0) {
            time = 0.0;
        }

        entryTimes.add(Double.valueOf(time));
    }

    public void addExitTime(double time) {
        if (exitTimes == null) {
            return;
        }

        if (time < 0) {
            time = 0.0;
        }

        exitTimes.add(Double.valueOf(time));
    }

    public void addBaudRate(double baudRate) {
        if (baudRates == null) {
            return;
        }

        baudRates.add(new Double(baudRate));
        if (bandwidth < 0 || baudRate < bandwidth) {
            bandwidth = baudRate;
        }
    }

    public Object[] getDetailBaudRate() {
        if (baudRates == null) {
            return null;
        }

        return baudRates.toArray();
    }

    public Object[] getDetailHops() {
        if (entities == null) {
            return null;
        }

        return entities.toArray();
    }

    public Object[] getDetailEntryTimes() {
        if (entryTimes == null) {
            return null;
        }

        return entryTimes.toArray();
    }

    public Object[] getDetailExitTimes() {
        if (exitTimes == null) {
            return null;
        }

        return exitTimes.toArray();
    }

    @Override
    public int getLast() {
        return last;
    }

    @Override
    public void setLast(int last) {
        this.last = last;
    }

    @Override
    public int getNetServiceType() {
        return netServiceType;
    }

    @Override
    public void setNetServiceType(int netServiceType) {
        this.netServiceType = netServiceType;
    }

    @Override
    public int getTag() {
        return tag;
    }

    public boolean setTag(int tag) {
        boolean flag = false;
        switch (tag) {
            case CloudSimTags.INFOPKT_SUBMIT:
            case CloudSimTags.INFOPKT_RETURN:
                this.tag = tag;
                flag = true;
                break;

            default:
                flag = false;
                break;
        }

        return flag;
    }

    public void setDestId(int id) {
        destId = id;
    }
}
