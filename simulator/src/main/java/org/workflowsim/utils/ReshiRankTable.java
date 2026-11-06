package org.workflowsim.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Immutable Reshi task-node ranking supplied by the experiment, not by production output. */
public final class ReshiRankTable {

    private final List<Entry> entries;

    public ReshiRankTable(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("Reshi requires a non-empty rank table");
        }
        List<Entry> copy = new ArrayList<Entry>(entries);
        Collections.sort(
                copy,
                Comparator.comparing(Entry::getTaskType)
                        .thenComparing(Entry::getNode)
                        .thenComparing(Entry::getRank));
        this.entries = Collections.unmodifiableList(copy);
    }

    public Entry bestFor(String taskType, List<Integer> freeVmIds) {
        Entry best = null;
        for (Entry entry : entries) {
            if (!entry.getTaskType().equals(taskType) || !freeVmIds.contains(entry.getVmId())) {
                continue;
            }
            if (best == null
                    || entry.getRank() < best.getRank()
                    || (Double.compare(entry.getRank(), best.getRank()) == 0 && entry.getVmId() < best.getVmId())) {
                best = entry;
            }
        }
        return best;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public static final class Entry {
        private final String taskType;
        private final String node;
        private final double rank;
        private final int vmId;

        public Entry(String taskType, String node, double rank, int vmId) {
            if (taskType == null
                    || taskType.isEmpty()
                    || node == null
                    || node.isEmpty()
                    || !Double.isFinite(rank)
                    || vmId < 0) {
                throw new IllegalArgumentException("Invalid Reshi rank entry");
            }
            this.taskType = taskType;
            this.node = node;
            this.rank = rank;
            this.vmId = vmId;
        }

        public String getTaskType() {
            return taskType;
        }

        public String getNode() {
            return node;
        }

        public double getRank() {
            return rank;
        }

        public int getVmId() {
            return vmId;
        }
    }
}
