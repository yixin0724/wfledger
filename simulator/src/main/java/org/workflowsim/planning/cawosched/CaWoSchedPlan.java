package org.workflowsim.planning.cawosched;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable timed plan and independently integrated brown-power cost for one CaWoSched run. */
public final class CaWoSchedPlan {

    private final Map<Integer, TaskPlan> taskPlans;
    private final List<Integer> decisionOrderTaskIds;
    private final double deadlineSeconds;
    private final double makespanSeconds;
    private final double carbonExcessWattSeconds;

    CaWoSchedPlan(
            Map<Integer, TaskPlan> taskPlans,
            List<Integer> decisionOrderTaskIds,
            double deadlineSeconds,
            double makespanSeconds,
            double carbonExcessWattSeconds) {
        this.taskPlans = Collections.unmodifiableMap(new LinkedHashMap<Integer, TaskPlan>(taskPlans));
        this.decisionOrderTaskIds = Collections.unmodifiableList(new ArrayList<Integer>(decisionOrderTaskIds));
        this.deadlineSeconds = deadlineSeconds;
        this.makespanSeconds = makespanSeconds;
        this.carbonExcessWattSeconds = carbonExcessWattSeconds;
    }

    public TaskPlan getTaskPlan(int taskId) {
        TaskPlan plan = taskPlans.get(Integer.valueOf(taskId));
        if (plan == null) {
            throw new IllegalArgumentException("No CaWoSched task " + taskId);
        }
        return plan;
    }

    public List<TaskPlan> getTaskPlans() {
        return Collections.unmodifiableList(new ArrayList<TaskPlan>(taskPlans.values()));
    }

    public List<Integer> getDecisionOrderTaskIds() {
        return decisionOrderTaskIds;
    }

    public double getDeadlineSeconds() {
        return deadlineSeconds;
    }

    public double getMakespanSeconds() {
        return makespanSeconds;
    }

    public double getCarbonExcessWattSeconds() {
        return carbonExcessWattSeconds;
    }

    /** One task's fixed VM, power values, and timed execution window. */
    public static final class TaskPlan {
        private final int taskId;
        private final int vmId;
        private final double startSeconds;
        private final double durationSeconds;
        private final double idleWatts;
        private final double activeWatts;

        TaskPlan(
                int taskId,
                int vmId,
                double startSeconds,
                double durationSeconds,
                double idleWatts,
                double activeWatts) {
            this.taskId = taskId;
            this.vmId = vmId;
            this.startSeconds = startSeconds;
            this.durationSeconds = durationSeconds;
            this.idleWatts = idleWatts;
            this.activeWatts = activeWatts;
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
            return startSeconds + durationSeconds;
        }

        public double getDurationSeconds() {
            return durationSeconds;
        }

        public double getIdleWatts() {
            return idleWatts;
        }

        public double getActiveWatts() {
            return activeWatts;
        }

        public double getDynamicWatts() {
            return activeWatts - idleWatts;
        }
    }
}
