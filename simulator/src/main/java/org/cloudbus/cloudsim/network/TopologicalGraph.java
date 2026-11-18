/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.network;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

public class TopologicalGraph {

    private List<TopologicalLink> linkList = null;

    private List<TopologicalNode> nodeList = null;

    public TopologicalGraph() {
        linkList = new LinkedList<TopologicalLink>();
        nodeList = new LinkedList<TopologicalNode>();
    }

    public void addLink(TopologicalLink edge) {
        linkList.add(edge);
    }

    public void addNode(TopologicalNode node) {
        nodeList.add(node);
    }

    public int getNumberOfNodes() {
        return nodeList.size();
    }

    public int getNumberOfLinks() {
        return linkList.size();
    }

    public Iterator<TopologicalLink> getLinkIterator() {
        return linkList.iterator();
    }

    public Iterator<TopologicalNode> getNodeIterator() {
        return nodeList.iterator();
    }

    @Override
    public String toString() {
        StringBuffer buffer = new StringBuffer();
        buffer.append("topological-node-information: \n");

        for (TopologicalNode node : nodeList) {
            buffer.append(
                    node.getNodeID() + " | x is: " + node.getCoordinateX() + " y is: " + node.getCoordinateY() + "\n");
        }

        buffer.append("\n\n node-link-information:\n");

        for (TopologicalLink link : linkList) {
            buffer.append("from: " + link.getSrcNodeID() + " to: " + link.getDestNodeID() + " delay: "
                    + link.getLinkDelay() + "\n");
        }
        return buffer.toString();
    }
}
