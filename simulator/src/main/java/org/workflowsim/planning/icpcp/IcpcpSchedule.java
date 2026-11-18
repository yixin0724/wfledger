package org.workflowsim.planning.icpcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IcpcpSchedule {
    private final Map<Integer, Entry> entries;
    private final List<List<Integer>> assignedPaths;
    private final double makespanSeconds;
    private final double totalCost;
    private final double minimumSlackSeconds;

    IcpcpSchedule(
            Map<Integer, Entry> entries,
            List<List<Integer>> assignedPaths,
            double makespanSeconds,
            double totalCost,
            double minimumSlackSeconds) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<Integer, Entry>(entries));
        List<List<Integer>> paths = new ArrayList<List<Integer>>();
        for (List<Integer> path : assignedPaths) {
            paths.add(Collections.unmodifiableList(new ArrayList<Integer>(path)));
        }
        this.assignedPaths = Collections.unmodifiableList(paths);
        this.makespanSeconds = makespanSeconds;
        this.totalCost = totalCost;
        this.minimumSlackSeconds = minimumSlackSeconds;
    }

    public Entry getTaskPlan(int taskId) {
        Entry entry = entries.get(taskId);
        if (entry == null) {
            throw new IllegalArgumentException("No IC-PCP task plan " + taskId);
        }
        return entry;
    }

    public List<Entry> getTaskPlans() {
        return Collections.unmodifiableList(new ArrayList<Entry>(entries.values()));
    }

    public List<List<Integer>> getAssignedPaths() {
        return assignedPaths;
    }

    public double getMakespanSeconds() {
        return makespanSeconds;
    }

    public double getTotalCost() {
        return totalCost;
    }

    public double getMinimumSlackSeconds() {
        return minimumSlackSeconds;
    }

    public static final class Entry {
        private final int taskId;
        private final int vmId;
        private final double startSeconds;
        private final double finishSeconds;

        Entry(int taskId, int vmId, double startSeconds, double finishSeconds) {
            this.taskId = taskId;
            this.vmId = vmId;
            this.startSeconds = startSeconds;
            this.finishSeconds = finishSeconds;
        }

        public int getTaskId() {
            return taskId;
        }

        public int getVmId() {
            return vmId;
        }

        public double getStartSeconds() {
            return startSeconds;
        }

        public double getFinishSeconds() {
            return finishSeconds;
        }
    }
}
