/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.network;

import java.util.Iterator;

public class DelayMatrix_Float {

    protected float[][] mDelayMatrix = null;

    protected int mTotalNodeNum = 0;

    @SuppressWarnings("unused")
    private DelayMatrix_Float() {}
    ;

    public DelayMatrix_Float(TopologicalGraph graph, boolean directed) {

        createDelayMatrix(graph, directed);

        calculateShortestPath();
    }

    public float getDelay(int srcID, int destID) {

        if (srcID > mTotalNodeNum || destID > mTotalNodeNum) {
            throw new ArrayIndexOutOfBoundsException("srcID or destID is higher than highest stored node-ID!");
        }

        return mDelayMatrix[srcID][destID];
    }

    private void createDelayMatrix(TopologicalGraph graph, boolean directed) {

        mTotalNodeNum = graph.getNumberOfNodes();

        mDelayMatrix = new float[mTotalNodeNum][mTotalNodeNum];

        for (int row = 0; row < mTotalNodeNum; ++row) {
            for (int col = 0; col < mTotalNodeNum; ++col) {
                mDelayMatrix[row][col] = Float.MAX_VALUE;
            }
        }

        Iterator<TopologicalLink> itr = graph.getLinkIterator();

        TopologicalLink edge;
        while (itr.hasNext()) {
            edge = itr.next();

            mDelayMatrix[edge.getSrcNodeID()][edge.getDestNodeID()] = edge.getLinkDelay();

            if (!directed) {

                mDelayMatrix[edge.getDestNodeID()][edge.getSrcNodeID()] = edge.getLinkDelay();
            }
        }
    }

    private void calculateShortestPath() {
        FloydWarshall_Float floyd = new FloydWarshall_Float();

        floyd.initialize(mTotalNodeNum);
        mDelayMatrix = floyd.allPairsShortestPaths(mDelayMatrix);
    }

    @Override
    public String toString() {
        StringBuffer buffer = new StringBuffer();

        buffer.append("just a simple printout of the distance-aware-topology-class\n");
        buffer.append("delay-matrix is:\n");

        for (int column = 0; column < mTotalNodeNum; ++column) {
            buffer.append("\t" + column);
        }

        for (int row = 0; row < mTotalNodeNum; ++row) {
            buffer.append("\n" + row);

            for (int col = 0; col < mTotalNodeNum; ++col) {
                if (mDelayMatrix[row][col] == Float.MAX_VALUE) {
                    buffer.append("\t" + "-");
                } else {
                    buffer.append("\t" + mDelayMatrix[row][col]);
                }
            }
        }

        return buffer.toString();
    }
}
