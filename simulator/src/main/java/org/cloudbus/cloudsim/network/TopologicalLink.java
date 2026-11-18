/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.network;

public class TopologicalLink {

    private int srcNodeID = 0;

    private int destNodeID = 0;

    private float linkDelay = 0;

    private float linkBw = 0;

    public TopologicalLink(int srcNode, int destNode, float delay, float bw) {

        linkDelay = delay;
        srcNodeID = srcNode;
        destNodeID = destNode;
        linkBw = bw;
    }

    public int getSrcNodeID() {
        return srcNodeID;
    }

    public int getDestNodeID() {
        return destNodeID;
    }

    public float getLinkDelay() {
        return linkDelay;
    }

    public float getLinkBw() {
        return linkBw;
    }
}
