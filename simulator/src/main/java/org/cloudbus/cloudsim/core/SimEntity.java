/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.core;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.NetworkTopology;
import org.cloudbus.cloudsim.core.predicates.Predicate;

public abstract class SimEntity implements Cloneable {

    private String name;

    private int id;

    private SimEvent evbuf;

    private int state;

    public SimEntity(String name) {
        if (name.indexOf(" ") != -1) {
            throw new IllegalArgumentException("Entity names can't contain spaces.");
        }
        this.name = name;
        id = -1;
        state = RUNNABLE;
        CloudSim.addEntity(this);
    }

    public String getName() {
        return name;
    }

    public int getId() {
        return id;
    }

    public void schedule(int dest, double delay, int tag, Object data) {
        if (!CloudSim.running()) {
            return;
        }
        CloudSim.send(id, dest, delay, tag, data);
    }

    public void schedule(int dest, double delay, int tag) {
        schedule(dest, delay, tag, null);
    }

    public void schedule(String dest, double delay, int tag, Object data) {
        schedule(CloudSim.getEntityId(dest), delay, tag, data);
    }

    public void schedule(String dest, double delay, int tag) {
        schedule(dest, delay, tag, null);
    }

    public void scheduleNow(int dest, int tag, Object data) {
        schedule(dest, 0, tag, data);
    }

    public void scheduleNow(int dest, int tag) {
        schedule(dest, 0, tag, null);
    }

    public void scheduleNow(String dest, int tag, Object data) {
        schedule(CloudSim.getEntityId(dest), 0, tag, data);
    }

    public void scheduleNow(String dest, int tag) {
        schedule(dest, 0, tag, null);
    }

    public void scheduleFirst(int dest, double delay, int tag, Object data) {
        if (!CloudSim.running()) {
            return;
        }
        CloudSim.sendFirst(id, dest, delay, tag, data);
    }

    public void scheduleFirst(int dest, double delay, int tag) {
        scheduleFirst(dest, delay, tag, null);
    }

    public void scheduleFirst(String dest, double delay, int tag, Object data) {
        scheduleFirst(CloudSim.getEntityId(dest), delay, tag, data);
    }

    public void scheduleFirst(String dest, double delay, int tag) {
        scheduleFirst(dest, delay, tag, null);
    }

    public void scheduleFirstNow(int dest, int tag, Object data) {
        scheduleFirst(dest, 0, tag, data);
    }

    public void scheduleFirstNow(int dest, int tag) {
        scheduleFirst(dest, 0, tag, null);
    }

    public void scheduleFirstNow(String dest, int tag, Object data) {
        scheduleFirst(CloudSim.getEntityId(dest), 0, tag, data);
    }

    public void scheduleFirstNow(String dest, int tag) {
        scheduleFirst(dest, 0, tag, null);
    }

    public void pause(double delay) {
        if (delay < 0) {
            throw new IllegalArgumentException("Negative delay supplied.");
        }
        if (!CloudSim.running()) {
            return;
        }
        CloudSim.pause(id, delay);
    }

    public int numEventsWaiting(Predicate p) {
        return CloudSim.waiting(id, p);
    }

    public int numEventsWaiting() {
        return CloudSim.waiting(id, CloudSim.SIM_ANY);
    }

    public SimEvent selectEvent(Predicate p) {
        if (!CloudSim.running()) {
            return null;
        }

        return CloudSim.select(id, p);
    }

    public SimEvent cancelEvent(Predicate p) {
        if (!CloudSim.running()) {
            return null;
        }

        return CloudSim.cancel(id, p);
    }

    public SimEvent getNextEvent(Predicate p) {
        if (!CloudSim.running()) {
            return null;
        }
        if (numEventsWaiting(p) > 0) {
            return selectEvent(p);
        }
        return null;
    }

    public void waitForEvent(Predicate p) {
        if (!CloudSim.running()) {
            return;
        }

        CloudSim.wait(id, p);
        state = WAITING;
    }

    public SimEvent getNextEvent() {
        return getNextEvent(CloudSim.SIM_ANY);
    }

    public abstract void startEntity();

    public abstract void processEvent(SimEvent ev);

    public abstract void shutdownEntity();

    public void run() {
        SimEvent ev = evbuf != null ? evbuf : getNextEvent();

        while (ev != null) {
            processEvent(ev);
            if (state != RUNNABLE) {
                break;
            }

            ev = getNextEvent();
        }

        evbuf = null;
    }

    @Override
    protected final Object clone() throws CloneNotSupportedException {
        SimEntity copy = (SimEntity) super.clone();
        copy.setName(name);
        copy.setEventBuffer(null);
        return copy;
    }

    private void setName(String new_name) {
        name = new_name;
    }

    protected int getState() {
        return state;
    }

    protected SimEvent getEventBuffer() {
        return evbuf;
    }

    public static final int RUNNABLE = 0;

    public static final int WAITING = 1;

    public static final int HOLDING = 2;

    public static final int FINISHED = 3;

    protected void setState(int state) {
        this.state = state;
    }

    protected void setId(int id) {
        this.id = id;
    }

    protected void setEventBuffer(SimEvent e) {
        evbuf = e;
    }

    protected void send(int entityId, double delay, int cloudSimTag, Object data) {
        if (entityId < 0) {
            return;
        }

        if (delay < 0) {
            delay = 0;
        }

        if (Double.isInfinite(delay)) {
            throw new IllegalArgumentException("The specified delay is infinite value");
        }

        if (entityId < 0) {
            Log.printLine(getName() + ".send(): Error - " + "invalid entity id " + entityId);
            return;
        }

        int srcId = getId();
        if (entityId != srcId) {
            delay += getNetworkDelay(srcId, entityId);
        }

        schedule(entityId, delay, cloudSimTag, data);
    }

    protected void send(int entityId, double delay, int cloudSimTag) {
        send(entityId, delay, cloudSimTag, null);
    }

    protected void send(String entityName, double delay, int cloudSimTag, Object data) {
        send(CloudSim.getEntityId(entityName), delay, cloudSimTag, data);
    }

    protected void send(String entityName, double delay, int cloudSimTag) {
        send(entityName, delay, cloudSimTag, null);
    }

    protected void sendNow(int entityId, int cloudSimTag, Object data) {
        send(entityId, 0, cloudSimTag, data);
    }

    protected void sendNow(int entityId, int cloudSimTag) {
        send(entityId, 0, cloudSimTag, null);
    }

    protected void sendNow(String entityName, int cloudSimTag, Object data) {
        send(CloudSim.getEntityId(entityName), 0, cloudSimTag, data);
    }

    protected void sendNow(String entityName, int cloudSimTag) {
        send(entityName, 0, cloudSimTag, null);
    }

    private double getNetworkDelay(int src, int dst) {
        if (NetworkTopology.isNetworkEnabled()) {
            return NetworkTopology.getDelay(src, dst);
        }
        return 0.0;
    }
}
