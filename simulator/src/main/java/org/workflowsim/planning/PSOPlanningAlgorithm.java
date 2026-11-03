package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.cloudbus.cloudsim.Vm;
import org.workflowsim.Task;
import org.workflowsim.utils.SimulationRandom;
import org.workflowsim.utils.TaskExecutionModel;

/**
 * PSO (particle swarm optimization) workflow planning algorithm -- paper reproduction.
 *
 * <p>Reproduction target: <b>Pandey, Wu, Guru, Buyya, "A Particle Swarm Optimization-Based
 * Heuristic for Scheduling Workflow Applications in Cloud Computing Environments",
 * AINA 2010</b>; reference open-source implementation: {@code meysamhit/workflowsim-pso}
 * (that repository's {@code PSOPlanningAlgorithm/Particle/FitnessFunction}).</p>
 *
 * <h2>Algorithm core (identical to the reference implementation)</h2>
 * <ul>
 *   <li>Each particle encodes a Task-to-VM mapping; population size 30, 100 iterations;</li>
 *   <li>Fitness = 0.8 * total cost + 0.2 * makespan (cost model in
 *       {@link PsoFitnessFunction}, VM unit price = mips/1000);</li>
 *   <li>Standard PSO velocity/position update: w=0.7, c1=c2=1.5, position rounded to the
 *       nearest VM;</li>
 *   <li>After convergence the global best mapping is written through
 *       {@code task.setVmId(...)} and executed at run time by
 *       {@code SchedulingAlgorithm.STATIC} (enforced by the platform contract).</li>
 * </ul>
 *
 * <h2>Deliberate differences from the reference implementation (platform contract adaptation)</h2>
 * <ul>
 *   <li>Random numbers: the named component stream
 *       {@code SimulationRandom.newJavaRandom("planning.pso")} is used (the root seed is
 *       controlled by the campaign seed and reproducible) instead of the reference
 *       implementation's hard-coded {@code new Random(42)};</li>
 *   <li>VM candidates are sorted by ascending VM ID first, and the position range is the
 *       index of that ordered list -- a deterministic tie-break contract consistent with
 *       the supported algorithm family;</li>
 *   <li>Tasks are sorted by ascending ID and then matched to position dimensions, so the
 *       mapping definition does not depend on parse order;</li>
 *   <li>When the matrix is present the effective execution seconds are used, the same as
 *       at run time; after initialization and each position update, coordinates
 *       incompatible with the PE count are projected to the nearest feasible VM index,
 *       with ties going to the smaller VM ID, and no extra random sampling is performed.
 *       On a fully compatible platform the original coordinates, random stream, and
 *       update formulas stay unchanged;</li>
 *   <li>The reference experiment uses {@code planning=PSO + scheduling=ROUNDROBIN}; on the
 *       current platform that combination is rejected by {@code SimulationConfig} (a
 *       planning algorithm must be paired with STATIC dispatch), and ROUNDROBIN scheduling
 *       would overwrite the planning mapping; the reproduction uses {@code PSO + STATIC}.</li>
 * </ul>
 *
 * <h2>Known model limitations (faithfully retained from the reference implementation)</h2>
 * <p>Fitness uses a <b>sequential-execution VM load model</b> and ignores DAG dependency
 * edges -- both the reference implementation and the paper use this simplified cost model.
 * The run-side makespan follows the actual execution after engine dependency release.</p>
 *
 * @since WorkflowSim Toolkit 1.0 (paper reproduction round)
 */
public class PSOPlanningAlgorithm extends BasePlanningAlgorithm {

    /** Population size (reference implementation constant). */
    public static final int POPULATION_SIZE = 30;
    /** Maximum iteration count (reference implementation constant). */
    public static final int MAX_ITERATIONS = 100;
    /** Inertia weight (reference implementation constant). */
    public static final double INERTIA_WEIGHT = 0.7;
    /** Cognitive coefficient (reference implementation constant). */
    public static final double COGNITIVE_COEFFICIENT = 1.5;
    /** Social coefficient (reference implementation constant). */
    public static final double SOCIAL_COEFFICIENT = 1.5;
    /** Cost weight (reference implementation constant: cost dominates, makespan is secondary). */
    public static final double COST_WEIGHT = 0.8;

    /** Global best fitness of the latest convergence (for regression tests and reproduction checks). */
    private double lastBestFitness = Double.NaN;
    /** Actual iteration count of the latest run. */
    private int lastIterationCount = 0;
    /** Global best mapping of the latest convergence (Task ID to VM ID). */
    private java.util.Map<Integer, Integer> lastMapping = new java.util.LinkedHashMap<Integer, Integer>();

    @Override
    public void run() {
        List<Task> rawTasks = getTaskList();
        List<? extends Vm> rawVms = getVmList();
        if (rawTasks == null || rawTasks.isEmpty() || rawVms == null || rawVms.isEmpty()) {
            lastBestFitness = Double.NaN;
            lastIterationCount = 0;
            lastMapping.clear();
            return;
        }

        // Deterministic order: tasks by ascending ID, VMs by ascending ID
        // (position range = VM list index).
        List<Task> tasks = new ArrayList<Task>(rawTasks);
        Collections.sort(tasks, new Comparator<Task>() {
            @Override
            public int compare(Task a, Task b) {
                return Integer.compare(a.getCloudletId(), b.getCloudletId());
            }
        });
        List<Vm> vms = new ArrayList<Vm>(rawVms);
        Collections.sort(vms, new Comparator<Vm>() {
            @Override
            public int compare(Vm a, Vm b) {
                return Integer.compare(a.getId(), b.getId());
            }
        });
        validatePositiveMips(vms);

        int taskCount = tasks.size();
        int vmCount = vms.size();
        List<List<Integer>> compatibleIndices = compatibleVmIndices(tasks, vms);
        Random random = SimulationRandom.newJavaRandom("planning.pso");

        List<PsoParticle> swarm = initSwarm(taskCount, vmCount, random, compatibleIndices);
        PsoResult result = evolveSwarm(swarm, tasks, vms, compatibleIndices, random, vmCount);

        // R8 audit fix (algorithm channel P2-3): if every fitness is NaN (unreachable in
        // theory after the mips check, but kept defensively), globalBestPosition stays
        // null and the original code crashed here with a null pointer. Fail fast
        // explicitly at the root cause.
        if (result.position == null) {
            throw new IllegalStateException("PSO did not converge to any finite-fitness position (all particle "
                    + "fitnesses were NaN); check task lengths and VM MIPS");
        }

        // Write the global best mapping after convergence.
        writeMapping(tasks, vms, result.position);
        lastBestFitness = result.fitness;
    }

    /** MIPS entry validation stage of {@link #run()} (verbatim relocation of R8 fix P2-3). */
    private static void validatePositiveMips(List<Vm> vms) {
        // R8 audit fix (algorithm channel P2-3): fitness computes cost as length/mips, so a
        // non-positive mips yields Inf/NaN fitness and contaminates the whole swarm, ending
        // in a globalBestPosition null-pointer crash. Fail fast at the entry point.
        for (Vm vm : vms) {
            double mips = vm.getMips();
            if (Double.isNaN(mips) || Double.isInfinite(mips) || mips <= 0.0) {
                throw new IllegalStateException(
                        "PSO requires every VM to have finite positive MIPS; VM #" + vm.getId() + " has " + mips);
            }
        }
    }

    /** Swarm initialization stage of {@link #run()} (verbatim relocation). */
    private static List<PsoParticle> initSwarm(
            int taskCount, int vmCount, Random random, List<List<Integer>> compatibleIndices) {
        List<PsoParticle> swarm = new ArrayList<PsoParticle>();
        for (int i = 0; i < POPULATION_SIZE; i++) {
            PsoParticle particle = new PsoParticle(taskCount, vmCount, random);
            constrainToCompatibleVms(particle.getPosition(), compatibleIndices);
            particle.setBestPosition(particle.getPosition());
            swarm.add(particle);
        }
        return swarm;
    }

    /**
     * Iterative evolution stage of {@link #run()} (fitness -> global best -> velocity and
     * position update, verbatim relocation).
     */
    private PsoResult evolveSwarm(
            List<PsoParticle> swarm,
            List<Task> tasks,
            List<Vm> vms,
            List<List<Integer>> compatibleIndices,
            Random random,
            int vmCount) {
        PsoResult result = new PsoResult();
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            for (PsoParticle particle : swarm) {
                double fitness = PsoFitnessFunction.evaluate(particle.getPosition(), tasks, vms, COST_WEIGHT);
                if (fitness < particle.getBestFitness()) {
                    particle.setBestPosition(particle.getPosition());
                    particle.setBestFitness(fitness);
                }
                if (fitness < result.fitness) {
                    result.position = particle.getPosition().clone();
                    result.fitness = fitness;
                }
            }
            if (result.position == null) {
                throw new IllegalStateException(
                        "PSO found no finite-fitness compatible mapping; " + "check task costs and VM MIPS");
            }
            for (PsoParticle particle : swarm) {
                particle.updateVelocity(
                        result.position, INERTIA_WEIGHT, COGNITIVE_COEFFICIENT, SOCIAL_COEFFICIENT, random);
                particle.updatePosition(vmCount);
                constrainToCompatibleVms(particle.getPosition(), compatibleIndices);
            }
            lastIterationCount = iter + 1;
        }
        return result;
    }

    /** Convergence mapping stage of {@link #run()} (verbatim relocation). */
    private void writeMapping(List<Task> tasks, List<Vm> vms, int[] globalBestPosition) {
        lastMapping.clear();
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            Vm vm = vms.get(globalBestPosition[i]);
            task.setVmId(vm.getId());
            lastMapping.put(Integer.valueOf(task.getCloudletId()), Integer.valueOf(vm.getId()));
        }
    }

    /** Private relocation holder for the {PSO} iteration stage (global best position and fitness). */
    private static final class PsoResult {
        private int[] position;
        private double fitness = Double.MAX_VALUE;
    }

    private static List<List<Integer>> compatibleVmIndices(List<Task> tasks, List<Vm> vms) {
        List<List<Integer>> result = new ArrayList<List<Integer>>();
        for (Task task : tasks) {
            List<Integer> candidates = new ArrayList<Integer>();
            for (int index = 0; index < vms.size(); index++) {
                Vm vm = vms.get(index);
                if (task.getNumberOfPes() <= vm.getNumberOfPes()) {
                    // Validate every feasible coordinate before sampling; a missing matrix entry must not
                    // depend on whether a random particle visits it.
                    TaskExecutionModel.executionSeconds(task, vm.getId(), vm.getMips());
                    candidates.add(Integer.valueOf(index));
                }
            }
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("PSO cannot map task " + task.getCloudletId()
                        + "; no compatible VM has sufficient processing elements");
            }
            result.add(candidates);
        }
        return result;
    }

    /**
     * Keep the original VM index coordinate system; project infeasible positions to the
     * nearest feasible index, with ties resolved to the smaller VM ID.
     */
    private static void constrainToCompatibleVms(int[] position, List<List<Integer>> candidates) {
        for (int task = 0; task < position.length; task++) {
            int selected = candidates.get(task).get(0).intValue();
            int distance = Math.abs(position[task] - selected);
            for (Integer candidate : candidates.get(task)) {
                int candidateDistance = Math.abs(position[task] - candidate.intValue());
                if (candidateDistance < distance) {
                    selected = candidate.intValue();
                    distance = candidateDistance;
                }
            }
            position[task] = selected;
        }
    }

    /** @return global best fitness of the latest convergence; {@code NaN} when not run or without input */
    public double getLastBestFitness() {
        return lastBestFitness;
    }

    /** @return iteration count of the latest run */
    public int getLastIterationCount() {
        return lastIterationCount;
    }

    /** @return mapping of the latest convergence (Task ID to VM ID); insertion order = ascending Task ID */
    public java.util.Map<Integer, Integer> getLastMapping() {
        return Collections.unmodifiableMap(lastMapping);
    }
}
