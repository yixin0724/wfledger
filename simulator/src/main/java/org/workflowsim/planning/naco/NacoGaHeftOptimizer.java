package org.workflowsim.planning.naco;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.workflowsim.utils.SimulationRandom;

public final class NacoGaHeftOptimizer {

    private final NacoDncProblem problem;
    private final NacoDncEvaluator evaluator = new NacoDncEvaluator();
    private final int populationSize;
    private final int generations;
    private NacoSchedule bestSchedule;

    public NacoGaHeftOptimizer(NacoDncProblem problem, int populationSize, int generations) {
        if (problem == null || populationSize < 2 || generations < 0) {
            throw new IllegalArgumentException("NACO requires a problem and population >= 2");
        }
        this.problem = problem;
        this.populationSize = populationSize;
        this.generations = generations;
    }

    public NacoSchedule optimize() {
        List<Chromosome> population = new ArrayList<Chromosome>();
        population.add(new Chromosome(evaluator.heft(problem)));
        Random random = SimulationRandom.newJavaRandom("planning.naco.ga");
        while (population.size() < populationSize) {
            population.add(new Chromosome(NacoGaOperators.randomTopological(problem, random)));
        }
        for (int generation = 0; generation < generations; generation++) {
            List<Chromosome> offspring = new ArrayList<Chromosome>();
            for (int index = 0; index < populationSize; index++) {
                List<NacoGene> first = tournament(population, random).genes;
                List<NacoGene> second = tournament(population, random).genes;
                List<NacoGene> genes = NacoGaOperators.crossover(first, second, problem, random);
                if (random.nextDouble() < 0.5) {
                    genes = NacoGaOperators.mutate(genes, problem, random);
                }
                offspring.add(new Chromosome(genes));
            }
            population.addAll(offspring);
            Collections.sort(population, Comparator.comparingDouble(chromosome -> chromosome.fitness));
            population = new ArrayList<Chromosome>(population.subList(0, populationSize));
        }
        bestSchedule = population.get(0).schedule;
        return bestSchedule;
    }

    public NacoSchedule getBestSchedule() {
        if (bestSchedule == null) {
            throw new IllegalStateException("NACO has not been optimized");
        }
        return bestSchedule;
    }

    private Chromosome tournament(List<Chromosome> population, Random random) {
        Chromosome first = population.get(random.nextInt(population.size()));
        Chromosome second = population.get(random.nextInt(population.size()));
        return first.fitness <= second.fitness ? first : second;
    }

    private final class Chromosome {
        private final List<NacoGene> genes;
        private final NacoSchedule schedule;
        private final double fitness;

        private Chromosome(List<NacoGene> genes) {
            this.genes = genes;
            this.schedule = evaluator.evaluate(genes, problem);
            this.fitness = schedule.getMakespanSeconds();
        }

        private Chromosome(NacoSchedule schedule) {
            this.genes = new ArrayList<NacoGene>();
            for (NacoSchedule.Entry entry : schedule.getTaskPlans()) {
                genes.add(new NacoGene(entry.getTaskId(), entry.getVmId()));
            }
            this.schedule = schedule;
            this.fitness = schedule.getMakespanSeconds();
        }
    }
}
