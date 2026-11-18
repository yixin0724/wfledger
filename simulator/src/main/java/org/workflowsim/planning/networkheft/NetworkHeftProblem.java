package org.workflowsim.planning.networkheft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NetworkHeftProblem {
    private final List<NetworkHeftTask> tasks;
    private final Map<Integer, NetworkHeftTask> tasksById;
    private final double bottleneckCapacityBytesPerSecond;

    public NetworkHeftProblem(List<NetworkHeftTask> tasks, double bottleneckCapacityBytesPerSecond) {
        if (tasks == null
                || tasks.isEmpty()
                || !(bottleneckCapacityBytesPerSecond > 0.0)
                || !Double.isFinite(bottleneckCapacityBytesPerSecond)) {
            throw new IllegalArgumentException("Invalid Network-Aware HEFT problem");
        }
        this.tasks = new ArrayList<NetworkHeftTask>(tasks);
        Collections.sort(this.tasks, Comparator.comparingInt(NetworkHeftTask::getTaskId));
        this.tasksById = new LinkedHashMap<Integer, NetworkHeftTask>();
        for (NetworkHeftTask task : this.tasks) {
            if (tasksById.put(task.getTaskId(), task) != null) {
                throw new IllegalArgumentException("Duplicate task ID " + task.getTaskId());
            }
        }
        this.bottleneckCapacityBytesPerSecond = bottleneckCapacityBytesPerSecond;
    }

    public List<NetworkHeftTask> getTasks() {
        return Collections.unmodifiableList(tasks);
    }

    public NetworkHeftTask getTask(int taskId) {
        NetworkHeftTask task = tasksById.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Unknown task " + taskId);
        }
        return task;
    }

    public double getBottleneckCapacityBytesPerSecond() {
        return bottleneckCapacityBytesPerSecond;
    }
}
