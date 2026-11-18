package org.workflowsim.planning.dvfs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DvfsSlackSchedule {
    private final Map<Integer, Entry> entries;
    private final double makespanSeconds;
    private final double totalEnergyJoules;

    DvfsSlackSchedule(Map<Integer, Entry> entries, double makespanSeconds, double totalEnergyJoules) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<Integer, Entry>(entries));
        this.makespanSeconds = makespanSeconds;
        this.totalEnergyJoules = totalEnergyJoules;
    }

    public Entry getTaskPlan(int taskId) {
        Entry entry = entries.get(taskId);
        if (entry == null) {
            throw new IllegalArgumentException("No DVFS task plan " + taskId);
        }
        return entry;
    }

    public List<Entry> getTaskPlans() {
        return Collections.unmodifiableList(new ArrayList<Entry>(entries.values()));
    }

    public double getMakespanSeconds() {
        return makespanSeconds;
    }

    public double getTotalEnergyJoules() {
        return totalEnergyJoules;
    }

    public static final class Entry {
        private final int taskId;
        private final int vmId;
        private final double tierMips;
        private final double startSeconds;
        private final double finishSeconds;

        Entry(int taskId, int vmId, double tierMips, double startSeconds, double finishSeconds) {
            this.taskId = taskId;
            this.vmId = vmId;
            this.tierMips = tierMips;
            this.startSeconds = startSeconds;
            this.finishSeconds = finishSeconds;
        }

        public int getTaskId() {
            return taskId;
        }

        public int getVmId() {
            return vmId;
        }

        public double getTierMips() {
            return tierMips;
        }

        public double getStartSeconds() {
            return startSeconds;
        }

        public double getFinishSeconds() {
            return finishSeconds;
        }
    }
}
