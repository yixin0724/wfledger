package org.workflowsim.planning.greenheft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GreenHeftProblem {
    private final List<GreenHeftTask> tasks;
    private final Map<Integer, GreenHeftTask> tasksById;

    public GreenHeftProblem(List<GreenHeftTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("GreenHEFT requires at least one task");
        }
        this.tasks = new ArrayList<GreenHeftTask>(tasks);
        Collections.sort(this.tasks, Comparator.comparingInt(GreenHeftTask::getTaskId));
        this.tasksById = new LinkedHashMap<Integer, GreenHeftTask>();
        for (GreenHeftTask task : this.tasks) {
            if (tasksById.put(task.getTaskId(), task) != null) {
                throw new IllegalArgumentException("Duplicate GreenHEFT task ID " + task.getTaskId());
            }
        }
    }

    public List<GreenHeftTask> getTasks() {
        return Collections.unmodifiableList(tasks);
    }

    public GreenHeftTask getTask(int taskId) {
        GreenHeftTask task = tasksById.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Unknown GreenHEFT task " + taskId);
        }
        return task;
    }
}
