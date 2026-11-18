/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.core;

public class CloudSimShutdown extends SimEntity {

    private int numUser;

    public CloudSimShutdown(String name, int numUser) throws Exception {

        super(name);
        this.numUser = numUser;
    }

    @Override
    public void processEvent(SimEvent ev) {
        numUser--;
        if (numUser == 0 || ev.getTag() == CloudSimTags.ABRUPT_END_OF_SIMULATION) {
            CloudSim.abruptallyTerminate();
        }
    }

    @Override
    public void startEntity() {}

    @Override
    public void shutdownEntity() {}
}
