/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.network;

public class TopologicalNode {

    private int nodeID = 0;

    private String nodeName = null;

    private int worldX = 0;

    private int worldY = 0;

    public TopologicalNode(int nodeID) {

        this.nodeID = nodeID;
        nodeName = String.valueOf(nodeID);
    }

    public TopologicalNode(int nodeID, int x, int y) {

        this.nodeID = nodeID;
        nodeName = String.valueOf(nodeID);
        worldX = x;
        worldY = y;
    }

    public TopologicalNode(int nodeID, String nodeName, int x, int y) {

        this.nodeID = nodeID;
        this.nodeName = nodeName;
        worldX = x;
        worldY = y;
    }

    public int getNodeID() {
        return nodeID;
    }

    public String getNodeLabel() {
        return nodeName;
    }

    public int getCoordinateX() {
        return worldX;
    }

    public int getCoordinateY() {
        return worldY;
    }
}
