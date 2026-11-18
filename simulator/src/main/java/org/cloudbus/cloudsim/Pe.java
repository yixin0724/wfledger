/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import org.cloudbus.cloudsim.provisioners.PeProvisioner;

public class Pe {

    public static final int FREE = 1;

    public static final int BUSY = 2;

    public static final int FAILED = 3;

    private int id;

    private int status;

    private PeProvisioner peProvisioner;

    public Pe(int id, PeProvisioner peProvisioner) {
        setId(id);
        setPeProvisioner(peProvisioner);

        status = FREE;
    }

    protected void setId(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public void setMips(double d) {
        getPeProvisioner().setMips(d);
    }

    public int getMips() {
        return (int) getPeProvisioner().getMips();
    }

    public int getStatus() {
        return status;
    }

    public void setStatusFree() {
        setStatus(FREE);
    }

    public void setStatusBusy() {
        setStatus(BUSY);
    }

    public void setStatusFailed() {
        setStatus(FAILED);
    }

    public void setStatus(int status) {
        this.status = status;
    }

    protected void setPeProvisioner(PeProvisioner peProvisioner) {
        this.peProvisioner = peProvisioner;
    }

    public PeProvisioner getPeProvisioner() {
        return peProvisioner;
    }
}
