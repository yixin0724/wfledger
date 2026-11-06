package org.workflowsim.planning.moheft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One immutable MOHEFT candidate schedule with makespan and total energy. */
public final class MoHeftSchedule {
    private final Map<Integer, Entry> entries;
    private final double makespanSeconds;
    private final double totalEnergyJoules;

    MoHeftSchedule(Map<Integer, Entry> entries, double makespanSeconds, double totalEnergyJoules) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<Integer, Entry>(entries));
        this.makespanSeconds = makespanSeconds;
        this.totalEnergyJoules = totalEnergyJoules;
    }

    public Entry getTaskPlan(int taskId) {
        Entry entry = entries.get(taskId);
        if (entry == null) {
            throw new IllegalArgumentException("No MOHEFT task plan " + taskId);
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
        private final double startSeconds;
        private final double finishSeconds;
        private final double energyJoules;

        Entry(int taskId, int vmId, double startSeconds, double finishSeconds, double energyJoules) {
            this.taskId = taskId;
            this.vmId = vmId;
            this.startSeconds = startSeconds;
            this.finishSeconds = finishSeconds;
            this.energyJoules = energyJoules;
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

        public double getEnergyJoules() {
            return energyJoules;
        }
    }
}
