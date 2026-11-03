package org.workflowsim.planning;

import java.util.Random;

/**
 * PSO particle: position = Task-to-VM mapping (one dimension per Task, whose value is an
 * index into the VM list).
 *
 * <p>Faithfully ported from the {@code Particle} class of
 * {@code meysamhit/workflowsim-pso} (a WorkflowSim open-source implementation of Pandey
 * et al., AINA 2010). Velocity initialization, the update formulas, and the position
 * rounding rule match the reference implementation:</p>
 * <ul>
 *   <li>initialization: position is uniform random in [0, vmCount); velocity is
 *       initialized in the range
 *       {@code random.nextDouble()*vmCount - vmCount/2.0};</li>
 *   <li>velocity update: {@code v = w*v + c1*r1*(pBest - x) + c2*r2*(gBest - x)};</li>
 *   <li>position update: {@code x' = round(x + v)}, clamped to [0, vmCount-1].</li>
 * </ul>
 *
 * <p>Difference from the reference implementation: random numbers come from the
 * {@code SimulationRandom} named component stream (reproducible from the root seed)
 * instead of a hard-coded {@code new Random(42)}.</p>
 *
 * @since WorkflowSim Toolkit 1.0 (paper reproduction round)
 */
public class PsoParticle {

    private int[] position;
    private double[] velocity;
    private int[] bestPosition;
    private double bestFitness = Double.MAX_VALUE;

    /**
     * Creates a randomly initialized particle.
     *
     * @param taskCount number of Task dimensions
     * @param vmCount number of VM candidates (domain of the position value)
     * @param random named component random stream
     */
    public PsoParticle(int taskCount, int vmCount, Random random) {
        position = new int[taskCount];
        velocity = new double[taskCount];
        for (int i = 0; i < taskCount; i++) {
            position[i] = random.nextInt(vmCount);
            velocity[i] = random.nextDouble() * vmCount - vmCount / 2.0;
        }
        bestPosition = position.clone();
    }

    /** Copy constructor (used to snapshot the global best). */
    public PsoParticle(PsoParticle other) {
        this.position = other.position.clone();
        this.velocity = other.velocity.clone();
        this.bestPosition = other.bestPosition.clone();
        this.bestFitness = other.bestFitness;
    }

    /** @return current position (Task-to-VM index mapping) */
    public int[] getPosition() {
        return position;
    }
    /** @return particle historical best position */
    public int[] getBestPosition() {
        return bestPosition;
    }
    /** Records the particle historical best position. */
    public void setBestPosition(int[] p) {
        this.bestPosition = p.clone();
    }
    /** @return particle historical best fitness */
    public double getBestFitness() {
        return bestFitness;
    }
    /** Records the particle historical best fitness. */
    public void setBestFitness(double f) {
        this.bestFitness = f;
    }

    /**
     * Standard PSO velocity update.
     *
     * @param globalBest global best position
     * @param w inertia weight
     * @param c1 cognitive coefficient
     * @param c2 social coefficient
     * @param random named component random stream
     */
    public void updateVelocity(int[] globalBest, double w, double c1, double c2, Random random) {
        for (int i = 0; i < velocity.length; i++) {
            double r1 = random.nextDouble();
            double r2 = random.nextDouble();
            velocity[i] = w * velocity[i]
                    + c1 * r1 * (bestPosition[i] - position[i])
                    + c2 * r2 * (globalBest[i] - position[i]);
        }
    }

    /**
     * Position update: rounds the new position to the nearest VM index and clamps it to
     * the value domain.
     *
     * @param vmCount number of VM candidates
     */
    public void updatePosition(int vmCount) {
        for (int i = 0; i < position.length; i++) {
            double newPos = position[i] + velocity[i];
            int rounded = (int) Math.round(newPos);
            rounded = Math.max(0, Math.min(vmCount - 1, rounded));
            position[i] = rounded;
        }
    }
}
