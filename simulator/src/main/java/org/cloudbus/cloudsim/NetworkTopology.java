/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.cloudbus.cloudsim.network.DelayMatrix_Float;
import org.cloudbus.cloudsim.network.GraphReaderBrite;
import org.cloudbus.cloudsim.network.TopologicalGraph;
import org.cloudbus.cloudsim.network.TopologicalLink;
import org.cloudbus.cloudsim.network.TopologicalNode;

public class NetworkTopology {

    protected static int nextIdx = 0;

    private static boolean networkEnabled = false;

    protected static DelayMatrix_Float delayMatrix = null;

    protected static double[][] bwMatrix = null;

    protected static TopologicalGraph graph = null;

    protected static Map<Integer, Integer> map = null;

    public static void buildNetworkTopology(String fileName) {
        Log.printLine("Topology file: " + fileName);

        GraphReaderBrite reader = new GraphReaderBrite();

        try {
            graph = reader.readGraphFile(fileName);
            map = new HashMap<Integer, Integer>();
            generateMatrices();
        } catch (IOException e) {

            Log.printLine("Problem in processing BRITE file. Network simulation is disabled. Error: " + e.getMessage());
        }
    }

    private static void generateMatrices() {

        delayMatrix = new DelayMatrix_Float(graph, false);

        bwMatrix = createBwMatrix(graph, false);

        networkEnabled = true;
    }

    public static void addLink(int srcId, int destId, double bw, double lat) {

        if (graph == null) {
            graph = new TopologicalGraph();
        }

        if (map == null) {
            map = new HashMap<Integer, Integer>();
        }

        if (!map.containsKey(srcId)) {
            graph.addNode(new TopologicalNode(nextIdx));
            map.put(srcId, nextIdx);
            nextIdx++;
        }

        if (!map.containsKey(destId)) {
            graph.addNode(new TopologicalNode(nextIdx));
            map.put(destId, nextIdx);
            nextIdx++;
        }

        graph.addLink(new TopologicalLink(map.get(srcId), map.get(destId), (float) lat, (float) bw));

        generateMatrices();
    }

    private static double[][] createBwMatrix(TopologicalGraph graph, boolean directed) {
        int nodes = graph.getNumberOfNodes();

        double[][] mtx = new double[nodes][nodes];

        for (int i = 0; i < nodes; i++) {
            for (int j = 0; j < nodes; j++) {
                mtx[i][j] = 0.0;
            }
        }

        Iterator<TopologicalLink> iter = graph.getLinkIterator();
        while (iter.hasNext()) {
            TopologicalLink edge = iter.next();

            mtx[edge.getSrcNodeID()][edge.getDestNodeID()] = edge.getLinkBw();

            if (!directed) {
                mtx[edge.getDestNodeID()][edge.getSrcNodeID()] = edge.getLinkBw();
            }
        }

        return mtx;
    }

    public static void mapNode(int cloudSimEntityID, int briteID) {
        if (networkEnabled) {
            try {

                if (!map.containsKey(cloudSimEntityID)) {
                    if (!map.containsValue(briteID)) {
                        map.put(cloudSimEntityID, briteID);
                    } else {
                        Log.printLine("Error in network mapping. BRITE node " + briteID + " already in use.");
                    }
                } else {
                    Log.printLine("Error in network mapping. CloudSim entity " + cloudSimEntityID + " already mapped.");
                }
            } catch (Exception e) {
                Log.printLine("Error in network mapping. CloudSim node " + cloudSimEntityID
                        + " not mapped to BRITE node " + briteID + ".");
            }
        }
    }

    public static void unmapNode(int cloudSimEntityID) {
        if (networkEnabled) {
            try {
                map.remove(cloudSimEntityID);
            } catch (Exception e) {
                Log.printLine("Error in network unmapping. CloudSim node: " + cloudSimEntityID);
            }
        }
    }

    public static double getDelay(int srcID, int destID) {
        if (networkEnabled) {
            try {

                double delay = delayMatrix.getDelay(map.get(srcID), map.get(destID));

                return delay;
            } catch (Exception e) {
            }
        }
        return 0.0;
    }

    public static boolean isNetworkEnabled() {
        return networkEnabled;
    }
}
