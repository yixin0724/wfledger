package org.workflowsim.planning.dheft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** DHEFT ready-wave scheduler with pairwise minimum VM bandwidth in bytes per second. */
public final class DheftPlanner {

    public DheftSchedule schedule(DheftProblem problem) {
        Set<Integer> scheduled = new HashSet<Integer>();
        Map<Integer, DheftSchedule.Entry> entries = new LinkedHashMap<Integer, DheftSchedule.Entry>();
        Map<Integer, Integer> mapping = new LinkedHashMap<Integer, Integer>();
        Map<Integer, Double> available = new HashMap<Integer, Double>();
        double makespan = 0.0;
        while (scheduled.size() < problem.getTasks().size()) {
            // Official DHEFT schedules the complete current ready list before releasing any
            // children unlocked by that list. A newly-ready task must not jump this wave.
            List<DheftTask> readyWave = new ArrayList<DheftTask>();
            for (DheftTask task : problem.getTasks()) {
                if (!scheduled.contains(task.getTaskId()) && ready(task, scheduled)) {
                    readyWave.add(task);
                }
            }
            if (readyWave.isEmpty()) {
                throw new IllegalArgumentException("DHEFT graph contains a cycle or missing parent");
            }
            for (DheftTask next : readyWave) {
                int bestVm = -1;
                double bestStart = Double.POSITIVE_INFINITY;
                double bestFinish = Double.POSITIVE_INFINITY;
                for (Integer vmId : sortedVms(next)) {
                    double start = available.getOrDefault(vmId, 0.0);
                    for (Integer parentId : next.getParentTaskIds()) {
                        DheftSchedule.Entry parent = entries.get(parentId);
                        DheftEdge edge =
                                problem.getTask(parentId).getEdgesByChild().get(next.getTaskId());
                        double communication = parent.getVmId() == vmId
                                ? 0.0
                                : edge.getDataBytes() / problem.getBandwidth(parent.getVmId(), vmId);
                        start = Math.max(start, parent.getFinishSeconds() + communication);
                    }
                    double finish = start + next.getComputeSecondsByVm().get(vmId);
                    if (finish < bestFinish - 1.0e-9 || (Math.abs(finish - bestFinish) <= 1.0e-9 && vmId < bestVm)) {
                        bestVm = vmId;
                        bestStart = start;
                        bestFinish = finish;
                    }
                }
                available.put(bestVm, bestFinish);
                entries.put(next.getTaskId(), new DheftSchedule.Entry(next.getTaskId(), bestVm, bestStart, bestFinish));
                mapping.put(next.getTaskId(), bestVm);
                scheduled.add(next.getTaskId());
                makespan = Math.max(makespan, bestFinish);
            }
        }
        return new DheftSchedule(entries, mapping, makespan);
    }

    private static boolean ready(DheftTask task, Set<Integer> scheduled) {
        for (Integer parent : task.getParentTaskIds()) {
            if (!scheduled.contains(parent)) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> sortedVms(DheftTask task) {
        List<Integer> result =
                new ArrayList<Integer>(task.getComputeSecondsByVm().keySet());
        Collections.sort(result);
        return result;
    }
}
