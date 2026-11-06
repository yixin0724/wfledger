package org.workflowsim.planning.naco;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DNC schedule evaluation and HEFT initialization for the NACO hybrid planner. */
public final class NacoDncEvaluator {

    public NacoSchedule evaluate(List<NacoGene> chromosome, NacoDncProblem problem) {
        if (chromosome == null || chromosome.size() > problem.getTasks().size()) {
            throw new IllegalArgumentException("NACO chromosome has an invalid length");
        }
        Map<Integer, NacoSchedule.Entry> scheduled = new LinkedHashMap<Integer, NacoSchedule.Entry>();
        Map<Integer, Double> available = new LinkedHashMap<Integer, Double>();
        double makespan = 0.0;
        for (NacoGene gene : chromosome) {
            NacoTask task = problem.getTask(gene.getTaskId());
            double network = problem.getNetworkSpeed(gene.getVmId());
            double disk = problem.getDiskSpeed(gene.getVmId());
            double maxParentFinish = 0.0;
            double communication = 0.0;
            for (Integer parentId : task.getParentTaskIds()) {
                NacoSchedule.Entry parent = scheduled.get(parentId);
                if (parent == null) {
                    throw new IllegalArgumentException(
                            "NACO chromosome is not topological at task " + task.getTaskId());
                }
                NacoTask parentTask = problem.getTask(parentId);
                double edgeBits = parentTask.getEdgeBitsByChild().containsKey(task.getTaskId())
                        ? parentTask.getEdgeBitsByChild().get(task.getTaskId())
                        : 0.0;
                if (parent.getVmId() == gene.getVmId()) {
                    communication += edgeBits / disk;
                } else {
                    double bandwidth = Math.min(problem.getNetworkSpeed(parent.getVmId()), network);
                    double slowest = Math.min(bandwidth, problem.getDiskSpeed(parent.getVmId()));
                    communication += edgeBits / slowest;
                }
                maxParentFinish = Math.max(maxParentFinish, parent.getFinishSeconds());
            }
            double diskRead = task.getInputBits() / disk;
            double diskWrite = task.getOutputBits() / disk;
            double compute = task.getComputeSecondsByVm().get(gene.getVmId());
            double start = Math.max(available.getOrDefault(gene.getVmId(), 0.0), maxParentFinish);
            double finish = start + diskRead + communication + compute + diskWrite;
            available.put(gene.getVmId(), finish);
            scheduled.put(task.getTaskId(), new NacoSchedule.Entry(task.getTaskId(), gene.getVmId(), start, finish));
            makespan = Math.max(makespan, finish);
        }
        if (scheduled.size() != chromosome.size()) {
            throw new IllegalArgumentException("NACO chromosome repeats a task");
        }
        return new NacoSchedule(scheduled, makespan);
    }

    /** Builds the HEFT seed schedule used as the elite member of the initial population. */
    public NacoSchedule heft(NacoDncProblem problem) {
        Map<Integer, Double> ranks = new LinkedHashMap<Integer, Double>();
        List<NacoTask> ordered = new ArrayList<NacoTask>(problem.getTasks());
        ordered.sort(Comparator.comparingInt(NacoTask::getTaskId).reversed());
        for (NacoTask task : ordered) {
            double bestChild = 0.0;
            for (Map.Entry<Integer, Double> edge : task.getEdgeBitsByChild().entrySet()) {
                double childRank = ranks.getOrDefault(edge.getKey(), 0.0);
                bestChild = Math.max(bestChild, childRank + edge.getValue() / averageNetwork(problem));
            }
            ranks.put(task.getTaskId(), averageCompute(task) + bestChild);
        }
        ordered.sort((first, second) -> {
            int byRank = Double.compare(ranks.get(second.getTaskId()), ranks.get(first.getTaskId()));
            return byRank != 0 ? byRank : Integer.compare(first.getTaskId(), second.getTaskId());
        });
        List<NacoGene> chromosome = new ArrayList<NacoGene>();
        for (NacoTask task : ordered) {
            int bestVm = -1;
            double bestFinish = Double.POSITIVE_INFINITY;
            for (Integer vmId : task.getComputeSecondsByVm().keySet()) {
                List<NacoGene> trial = new ArrayList<NacoGene>(chromosome);
                trial.add(new NacoGene(task.getTaskId(), vmId));
                double finish =
                        evaluate(trial, problem).getTaskPlan(task.getTaskId()).getFinishSeconds();
                if (finish < bestFinish - 1.0e-9 || (Math.abs(finish - bestFinish) <= 1.0e-9 && vmId < bestVm)) {
                    bestFinish = finish;
                    bestVm = vmId;
                }
            }
            chromosome.add(new NacoGene(task.getTaskId(), bestVm));
        }
        return evaluate(chromosome, problem);
    }

    private static double averageCompute(NacoTask task) {
        double total = 0.0;
        for (double seconds : task.getComputeSecondsByVm().values()) {
            total += seconds;
        }
        return total / task.getComputeSecondsByVm().size();
    }

    private static double averageNetwork(NacoDncProblem problem) {
        double total = 0.0;
        int count = 0;
        for (NacoTask task : problem.getTasks()) {
            for (Integer vmId : task.getComputeSecondsByVm().keySet()) {
                total += problem.getNetworkSpeed(vmId);
                count++;
            }
        }
        return total / Math.max(1, count);
    }
}
