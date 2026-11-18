package org.workflowsim.planning;

import java.util.Random;

public class PsoParticle {

    private int[] position;
    private double[] velocity;
    private int[] bestPosition;
    private double bestFitness = Double.MAX_VALUE;

    public PsoParticle(int taskCount, int vmCount, Random random) {
        position = new int[taskCount];
        velocity = new double[taskCount];
        for (int i = 0; i < taskCount; i++) {
            position[i] = random.nextInt(vmCount);
            velocity[i] = random.nextDouble() * vmCount - vmCount / 2.0;
        }
        bestPosition = position.clone();
    }

    public PsoParticle(PsoParticle other) {
        this.position = other.position.clone();
        this.velocity = other.velocity.clone();
        this.bestPosition = other.bestPosition.clone();
        this.bestFitness = other.bestFitness;
    }

    public int[] getPosition() {
        return position;
    }

    public int[] getBestPosition() {
        return bestPosition;
    }

    public void setBestPosition(int[] p) {
        this.bestPosition = p.clone();
    }

    public double getBestFitness() {
        return bestFitness;
    }

    public void setBestFitness(double f) {
        this.bestFitness = f;
    }

    public void updateVelocity(int[] globalBest, double w, double c1, double c2, Random random) {
        for (int i = 0; i < velocity.length; i++) {
            double r1 = random.nextDouble();
            double r2 = random.nextDouble();
            velocity[i] = w * velocity[i]
                    + c1 * r1 * (bestPosition[i] - position[i])
                    + c2 * r2 * (globalBest[i] - position[i]);
        }
    }

    public void updatePosition(int vmCount) {
        for (int i = 0; i < position.length; i++) {
            double newPos = position[i] + velocity[i];
            int rounded = (int) Math.round(newPos);
            rounded = Math.max(0, Math.min(vmCount - 1, rounded));
            position[i] = rounded;
        }
    }
}
