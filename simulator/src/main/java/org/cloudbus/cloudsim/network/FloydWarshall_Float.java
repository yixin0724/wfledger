package org.cloudbus.cloudsim.network;

public class FloydWarshall_Float {

    private int numVertices;

    private float[][] Dk, Dk_minus_one;
    private int[][] Pk, Pk_minus_one;

    public void initialize(int numVertices) {
        this.numVertices = numVertices;

        Dk = new float[numVertices][];
        Dk_minus_one = new float[numVertices][];
        for (int i = 0; i < numVertices; i++) {
            Dk[i] = new float[numVertices];
            Dk_minus_one[i] = new float[numVertices];
        }

        Pk = new int[numVertices][];
        Pk_minus_one = new int[numVertices][];
        for (int i = 0; i < numVertices; i++) {
            Pk[i] = new int[numVertices];
            Pk_minus_one[i] = new int[numVertices];
        }
    }

    public float[][] allPairsShortestPaths(float[][] adjMatrix) {

        for (int i = 0; i < numVertices; i++) {
            for (int j = 0; j < numVertices; j++) {
                if (adjMatrix[i][j] != 0) {
                    Dk_minus_one[i][j] = adjMatrix[i][j];
                    Pk_minus_one[i][j] = i;
                } else {
                    Dk_minus_one[i][j] = Float.MAX_VALUE;
                    Pk_minus_one[i][j] = -1;
                }
            }
        }

        for (int k = 0; k < numVertices; k++) {

            for (int i = 0; i < numVertices; i++) {
                for (int j = 0; j < numVertices; j++) {
                    if (i != j) {

                        if (Dk_minus_one[i][j] <= Dk_minus_one[i][k] + Dk_minus_one[k][j]) {
                            Dk[i][j] = Dk_minus_one[i][j];
                            Pk[i][j] = Pk_minus_one[i][j];
                        } else {
                            Dk[i][j] = Dk_minus_one[i][k] + Dk_minus_one[k][j];
                            Pk[i][j] = Pk_minus_one[k][j];
                        }
                    } else {
                        Pk[i][j] = -1;
                    }
                }
            }

            for (int i = 0; i < numVertices; i++) {
                for (int j = 0; j < numVertices; j++) {
                    Dk_minus_one[i][j] = Dk[i][j];
                    Pk_minus_one[i][j] = Pk[i][j];
                }
            }
        }

        return Dk;
    }

    public int[][] getPK() {
        return Pk;
    }
}
