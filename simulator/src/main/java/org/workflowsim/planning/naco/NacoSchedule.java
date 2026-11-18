package org.workflowsim.planning.naco;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NacoSchedule {
    private final Map<Integer, Entry> entries;
    private final double makespanSeconds;

    NacoSchedule(Map<Integer, Entry> entries, double makespanSeconds) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<Integer, Entry>(entries));
        this.makespanSeconds = makespanSeconds;
    }

    public Entry getTaskPlan(int taskId) {
        Entry entry = entries.get(taskId);
        if (entry == null) {
            throw new IllegalArgumentException("No NACO task plan " + taskId);
        }
        return entry;
    }

    public List<Entry> getTaskPlans() {
        return Collections.unmodifiableList(new ArrayList<Entry>(entries.values()));
    }

    public double getMakespanSeconds() {
        return makespanSeconds;
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
