package org.workflowsim.planning.naco;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

final class NacoGaOperators {

    private NacoGaOperators() {}

    static List<NacoGene> randomTopological(NacoDncProblem problem, Random random) {
        Map<Integer, Integer> remaining = new LinkedHashMap<Integer, Integer>();
        for (NacoTask task : problem.getTasks()) {
            remaining.put(task.getTaskId(), task.getParentTaskIds().size());
        }
        List<NacoGene> result = new ArrayList<NacoGene>();
        Set<Integer> done = new HashSet<Integer>();
        while (done.size() < problem.getTasks().size()) {
            List<Integer> ready = new ArrayList<Integer>();
            for (NacoTask task : problem.getTasks()) {
                if (!done.contains(task.getTaskId()) && remaining.get(task.getTaskId()) == 0) {
                    ready.add(task.getTaskId());
                }
            }
            if (ready.isEmpty()) {
                throw new IllegalArgumentException(
                        "NACO task graph contains a cycle or missing parent links: remaining=" + remaining
                                + ", completed=" + done);
            }
            int taskId = ready.get(random.nextInt(ready.size()));
            NacoTask task = problem.getTask(taskId);
            List<Integer> vms =
                    new ArrayList<Integer>(task.getComputeSecondsByVm().keySet());
            Collections.sort(vms);
            result.add(new NacoGene(taskId, vms.get(random.nextInt(vms.size()))));
            done.add(taskId);
            for (Integer child : task.getEdgeBitsByChild().keySet()) {
                remaining.put(child, remaining.get(child) - 1);
            }
        }
        return result;
    }

    static List<NacoGene> crossover(
            List<NacoGene> first, List<NacoGene> second, NacoDncProblem problem, Random random) {
        int cut = random.nextInt(first.size());
        List<NacoGene> result = new ArrayList<NacoGene>(first.subList(0, cut));
        Set<Integer> used = new HashSet<Integer>();
        Map<Integer, Integer> firstVm = new LinkedHashMap<Integer, Integer>();
        for (int index = 0; index < first.size(); index++) {
            if (index < cut) {
                used.add(first.get(index).getTaskId());
            }
            firstVm.put(first.get(index).getTaskId(), first.get(index).getVmId());
        }
        for (NacoGene gene : second) {
            if (used.contains(gene.getTaskId())) {
                continue;
            }
            int vm = gene.getVmId();
            if (firstVm.containsKey(gene.getTaskId())) {
                if (firstVm.get(gene.getTaskId()).intValue() == vm && random.nextDouble() <= 0.1) {
                    vm = randomVm(problem.getTask(gene.getTaskId()), random);
                } else if (random.nextInt(2) == 0) {
                    vm = firstVm.get(gene.getTaskId());
                }
            }
            result.add(new NacoGene(gene.getTaskId(), vm));
            used.add(gene.getTaskId());
        }
        return repairTopological(result, problem);
    }

    static List<NacoGene> mutate(List<NacoGene> source, NacoDncProblem problem, Random random) {
        List<NacoGene> result = new ArrayList<NacoGene>(source);
        int position = random.nextInt(result.size());
        NacoGene gene = result.get(position);
        int left = 0;
        int right = result.size() - 1;
        for (Integer parent : problem.getTask(gene.getTaskId()).getParentTaskIds()) {
            for (int index = 0; index < result.size(); index++) {
                if (result.get(index).getTaskId() == parent) {
                    left = Math.max(left, index + 1);
                }
            }
        }
        for (Integer child :
                problem.getTask(gene.getTaskId()).getEdgeBitsByChild().keySet()) {
            for (int index = 0; index < result.size(); index++) {
                if (result.get(index).getTaskId() == child) {
                    right = Math.min(right, index - 1);
                }
            }
        }
        int newPosition = left >= right ? position : left + random.nextInt(right - left + 1);
        result.remove(position);
        result.add(newPosition, new NacoGene(gene.getTaskId(), randomVm(problem.getTask(gene.getTaskId()), random)));
        return repairTopological(result, problem);
    }

    private static int randomVm(NacoTask task, Random random) {
        List<Integer> vms = new ArrayList<Integer>(task.getComputeSecondsByVm().keySet());
        Collections.sort(vms);
        return vms.get(random.nextInt(vms.size()));
    }

    private static List<NacoGene> repairTopological(List<NacoGene> source, NacoDncProblem problem) {
        Map<Integer, Integer> vmByTask = new LinkedHashMap<Integer, Integer>();
        for (NacoGene gene : source) {
            vmByTask.put(gene.getTaskId(), gene.getVmId());
        }
        List<NacoGene> result = new ArrayList<NacoGene>();
        Set<Integer> done = new HashSet<Integer>();
        while (result.size() < source.size()) {
            boolean progressed = false;
            for (NacoTask task : problem.getTasks()) {
                if (done.contains(task.getTaskId()) || !vmByTask.containsKey(task.getTaskId())) {
                    continue;
                }
                boolean ready = true;
                for (Integer parent : task.getParentTaskIds()) {
                    ready &= done.contains(parent);
                }
                if (ready) {
                    result.add(new NacoGene(task.getTaskId(), vmByTask.get(task.getTaskId())));
                    done.add(task.getTaskId());
                    progressed = true;
                }
            }
            if (!progressed) {
                throw new IllegalArgumentException("NACO chromosome cannot be topologically repaired");
            }
        }
        return result;
    }
}
