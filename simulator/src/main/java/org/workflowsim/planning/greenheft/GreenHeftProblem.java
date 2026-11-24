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
    private final double rootReleaseSeconds;

    public GreenHeftProblem(List<GreenHeftTask> tasks) {
        this(tasks, 0.0);
    }

    /**
     * @param rootReleaseSeconds earliest start of every root task. FGCS-R3 (2026-11-24) M6: under SHARED
     *     this is the finish of the model-generated stage-in Job plus the root-release interval, exactly
     *     as in {@code SharedStorageDagPlanner}; roots no longer start at 0.
     */
    public GreenHeftProblem(List<GreenHeftTask> tasks, double rootReleaseSeconds) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("GreenHEFT requires at least one task");
        }
        if (!(rootReleaseSeconds >= 0.0) || !Double.isFinite(rootReleaseSeconds)) {
            throw new IllegalArgumentException("GreenHEFT root release must be finite and non-negative");
        }
        this.rootReleaseSeconds = rootReleaseSeconds;
        this.tasks = new ArrayList<GreenHeftTask>(tasks);
        Collections.sort(this.tasks, Comparator.comparingInt(GreenHeftTask::getTaskId));
        this.tasksById = new LinkedHashMap<Integer, GreenHeftTask>();
        for (GreenHeftTask task : this.tasks) {
            if (tasksById.put(task.getTaskId(), task) != null) {
                throw new IllegalArgumentException("Duplicate GreenHEFT task ID " + task.getTaskId());
            }
        }
    }

    public double getRootReleaseSeconds() {
        return rootReleaseSeconds;
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
