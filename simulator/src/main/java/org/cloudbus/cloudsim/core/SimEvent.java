/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.core;

public class SimEvent implements Cloneable, Comparable<SimEvent> {

    private final int etype;

    private final double time;

    private double endWaitingTime;

    private int entSrc;

    private int entDst;

    private final int tag;

    private final Object data;

    private long serial = -1;

    public static final int ENULL = 0;

    public static final int SEND = 1;

    public static final int HOLD_DONE = 2;

    public static final int CREATE = 3;

    public SimEvent() {
        etype = ENULL;
        time = -1L;
        endWaitingTime = -1.0;
        entSrc = -1;
        entDst = -1;
        tag = -1;
        data = null;
    }

    SimEvent(int evtype, double time, int src, int dest, int tag, Object edata) {
        etype = evtype;
        this.time = time;
        entSrc = src;
        entDst = dest;
        this.tag = tag;
        data = edata;
    }

    SimEvent(int evtype, double time, int src) {
        etype = evtype;
        this.time = time;
        entSrc = src;
        entDst = -1;
        tag = -1;
        data = null;
    }

    protected void setSerial(long serial) {
        this.serial = serial;
    }

    protected void setEndWaitingTime(double end_waiting_time) {
        endWaitingTime = end_waiting_time;
    }

    @Override
    public String toString() {
        return "Event tag = " + tag + " source = " + CloudSim.getEntity(entSrc).getName() + " destination = "
                + CloudSim.getEntity(entDst).getName();
    }

    public int getType() {
        return etype;
    }

    @Override
    public int compareTo(SimEvent event) {
        if (event == null) {
            return 1;
        } else if (time < event.time) {
            return -1;
        } else if (time > event.time) {
            return 1;
        } else if (serial < event.serial) {
            return -1;
        } else if (this == event) {
            return 0;
        } else {
            return 1;
        }
    }

    public int getDestination() {
        return entDst;
    }

    public int getSource() {
        return entSrc;
    }

    public double eventTime() {
        return time;
    }

    public double endWaitingTime() {
        return endWaitingTime;
    }

    public int type() {
        return tag;
    }

    public int scheduledBy() {
        return entSrc;
    }

    public int getTag() {
        return tag;
    }

    public Object getData() {
        return data;
    }

    @Override
    public Object clone() {
        return new SimEvent(etype, time, entSrc, entDst, tag, data);
    }

    public void setSource(int s) {
        entSrc = s;
    }

    public void setDestination(int d) {
        entDst = d;
    }
}
