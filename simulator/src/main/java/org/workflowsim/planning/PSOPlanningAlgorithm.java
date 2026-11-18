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

public class PSOPlanningAlgorithm extends BasePlanningAlgorithm {

    public static final int POPULATION_SIZE = 30;

    public static final int MAX_ITERATIONS = 100;

    public static final double INERTIA_WEIGHT = 0.7;

    public static final double COGNITIVE_COEFFICIENT = 1.5;

    public static final double SOCIAL_COEFFICIENT = 1.5;

    public static final double COST_WEIGHT = 0.8;

    private double lastBestFitness = Double.NaN;

    private int lastIterationCount = 0;

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

        if (result.position == null) {
            throw new IllegalStateException("PSO did not converge to any finite-fitness position (all particle "
                    + "fitnesses were NaN); check task lengths and VM MIPS");
        }

        writeMapping(tasks, vms, result.position);
        lastBestFitness = result.fitness;
    }

    private static void validatePositiveMips(List<Vm> vms) {

        for (Vm vm : vms) {
            double mips = vm.getMips();
            if (Double.isNaN(mips) || Double.isInfinite(mips) || mips <= 0.0) {
                throw new IllegalStateException(
                        "PSO requires every VM to have finite positive MIPS; VM #" + vm.getId() + " has " + mips);
            }
        }
    }

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

    private void writeMapping(List<Task> tasks, List<Vm> vms, int[] globalBestPosition) {
        lastMapping.clear();
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            Vm vm = vms.get(globalBestPosition[i]);
            task.setVmId(vm.getId());
            lastMapping.put(Integer.valueOf(task.getCloudletId()), Integer.valueOf(vm.getId()));
        }
    }

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

    public double getLastBestFitness() {
        return lastBestFitness;
    }

    public int getLastIterationCount() {
        return lastIterationCount;
    }

    public java.util.Map<Integer, Integer> getLastMapping() {
        return Collections.unmodifiableMap(lastMapping);
    }
}
