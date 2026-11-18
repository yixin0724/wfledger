/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.network;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.StringTokenizer;

public class GraphReaderBrite implements GraphReaderIF {

    private static final int PARSE_NOTHING = 0;

    private static final int PARSE_NODES = 1;

    private static final int PARSE_EDGES = 2;

    private int state = PARSE_NOTHING;

    private TopologicalGraph graph = null;

    @Override
    public TopologicalGraph readGraphFile(String filename) throws IOException {

        graph = new TopologicalGraph();

        FileReader fr = new FileReader(filename);
        BufferedReader br = new BufferedReader(fr);

        String lineSep = System.getProperty("line.separator");
        String nextLine = null;
        StringBuffer sb = new StringBuffer();

        while ((nextLine = br.readLine()) != null) {
            sb.append(nextLine);

            sb.append(lineSep);

            if (state == PARSE_NOTHING) {
                if (nextLine.contains("Nodes:")) {

                    state = PARSE_NODES;
                }
            } else if (state == PARSE_NODES) {

                parseNodeString(nextLine);
            } else if (state == PARSE_EDGES) {
                parseEdgesString(nextLine);
            }
        }

        br.close();

        return graph;
    }

    private void parseNodeString(String nodeLine) {

        StringTokenizer tokenizer = new StringTokenizer(nodeLine);

        int parameters = 3;

        if (nodeLine.contains("Edges:")) {

            state = PARSE_EDGES;

            return;
        }

        if (!tokenizer.hasMoreElements()) {

            return;
        }

        int nodeID = 0;
        String nodeLabel = "";
        int xPos = 0;
        int yPos = 0;

        for (int actualParam = 0; tokenizer.hasMoreElements() && actualParam < parameters; actualParam++) {
            String token = tokenizer.nextToken();
            switch (actualParam) {
                case 0:
                    nodeID = Integer.valueOf(token);
                    nodeLabel = Integer.toString(nodeID);
                    break;

                case 1:
                    xPos = Integer.valueOf(token);
                    break;

                case 2:
                    yPos = Integer.valueOf(token);
                    break;
            }
        }

        TopologicalNode topoNode = new TopologicalNode(nodeID, nodeLabel, xPos, yPos);
        graph.addNode(topoNode);
    }

    private void parseEdgesString(String nodeLine) {
        StringTokenizer tokenizer = new StringTokenizer(nodeLine);

        int parameters = 6;

        if (!tokenizer.hasMoreElements()) {

            return;
        }

        int fromNode = 0;
        int toNode = 0;

        float linkDelay = 0;
        int linkBandwith = 0;

        for (int actualParam = 0; tokenizer.hasMoreElements() && actualParam < parameters; actualParam++) {
            String token = tokenizer.nextToken();
            switch (actualParam) {
                case 0:
                    break;

                case 1:
                    fromNode = Integer.valueOf(token);
                    break;

                case 2:
                    toNode = Integer.valueOf(token);
                    break;

                case 3:
                    break;

                case 4:
                    linkDelay = Float.valueOf(token);
                    break;

                case 5:
                    linkBandwith = Float.valueOf(token).intValue();
                    break;
            }
        }

        graph.addLink(new TopologicalLink(fromNode, toNode, linkDelay, linkBandwith));
    }
}
