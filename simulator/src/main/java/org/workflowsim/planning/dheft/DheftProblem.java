package org.workflowsim.planning.dheft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DheftProblem {
    private final List<DheftTask> tasks;
    private final Map<Integer, DheftTask> tasksById;
    private final Map<Integer, Double> bandwidthByVm;

    public DheftProblem(List<DheftTask> tasks, Map<Integer, Double> bandwidthByVm) {
        if (tasks == null || tasks.isEmpty() || bandwidthByVm == null || bandwidthByVm.isEmpty()) {
            throw new IllegalArgumentException("DHEFT requires tasks and VM bandwidth declarations");
        }
        this.tasks = new ArrayList<DheftTask>(tasks);
        Collections.sort(this.tasks, Comparator.comparingInt(DheftTask::getTaskId));
        this.tasksById = new LinkedHashMap<Integer, DheftTask>();
        for (DheftTask task : this.tasks) {
            if (tasksById.put(task.getTaskId(), task) != null) {
                throw new IllegalArgumentException("Duplicate DHEFT task ID " + task.getTaskId());
            }
            for (Integer vmId : task.getComputeSecondsByVm().keySet()) {
                Double bandwidth = bandwidthByVm.get(vmId);
                if (bandwidth == null || !(bandwidth > 0.0) || !Double.isFinite(bandwidth)) {
                    throw new IllegalArgumentException("Missing DHEFT bandwidth for VM " + vmId);
                }
            }
        }
        this.bandwidthByVm = Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(bandwidthByVm));
    }

    public List<DheftTask> getTasks() {
        return Collections.unmodifiableList(tasks);
    }

    public DheftTask getTask(int taskId) {
        DheftTask task = tasksById.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Unknown DHEFT task " + taskId);
        }
        return task;
    }

    public double getBandwidth(int firstVmId, int secondVmId) {
        return Math.min(bandwidthByVm.get(firstVmId), bandwidthByVm.get(secondVmId));
    }
}
