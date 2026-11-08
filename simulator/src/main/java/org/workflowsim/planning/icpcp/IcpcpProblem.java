package org.workflowsim.planning.icpcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable IC-PCP deadline and VM cost problem. */
public final class IcpcpProblem {
    private final List<IcpcpTask> tasks;
    private final Map<Integer, IcpcpTask> tasksById;
    private final double deadlineSeconds;
    private final double billingPeriodSeconds;
    private final Map<Integer, Double> vmCostPerSecondByVm;
    private final double workflowStartSeconds;

    public IcpcpProblem(
            List<IcpcpTask> tasks,
            double deadlineSeconds,
            double billingPeriodSeconds,
            Map<Integer, Double> vmCostPerSecondByVm) {
        this(tasks, deadlineSeconds, billingPeriodSeconds, vmCostPerSecondByVm, 0.0);
    }

    public IcpcpProblem(
            List<IcpcpTask> tasks,
            double deadlineSeconds,
            double billingPeriodSeconds,
            Map<Integer, Double> vmCostPerSecondByVm,
            double workflowStartSeconds) {
        if (tasks == null
                || tasks.isEmpty()
                || !(deadlineSeconds > 0.0)
                || !Double.isFinite(deadlineSeconds)
                || !(billingPeriodSeconds > 0.0)
                || !Double.isFinite(billingPeriodSeconds)
                || vmCostPerSecondByVm == null
                || vmCostPerSecondByVm.isEmpty()
                || workflowStartSeconds < 0.0
                || !Double.isFinite(workflowStartSeconds)) {
            throw new IllegalArgumentException("Invalid IC-PCP deadline, billing, or task declarations");
        }
        this.tasks = new ArrayList<IcpcpTask>(tasks);
        Collections.sort(this.tasks, Comparator.comparingInt(IcpcpTask::getTaskId));
        this.tasksById = new LinkedHashMap<Integer, IcpcpTask>();
        for (IcpcpTask task : this.tasks) {
            if (tasksById.put(task.getTaskId(), task) != null) {
                throw new IllegalArgumentException("Duplicate IC-PCP task ID " + task.getTaskId());
            }
            for (Integer vmId : task.getComputeSecondsByVm().keySet()) {
                Double cost = vmCostPerSecondByVm.get(vmId);
                if (cost == null || cost < 0.0 || !Double.isFinite(cost)) {
                    throw new IllegalArgumentException("Missing or invalid IC-PCP VM cost for VM " + vmId);
                }
            }
        }
        this.deadlineSeconds = deadlineSeconds;
        this.billingPeriodSeconds = billingPeriodSeconds;
        this.vmCostPerSecondByVm = Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(vmCostPerSecondByVm));
        this.workflowStartSeconds = workflowStartSeconds;
    }

    public List<IcpcpTask> getTasks() {
        return Collections.unmodifiableList(tasks);
    }

    public IcpcpTask getTask(int taskId) {
        IcpcpTask task = tasksById.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Unknown IC-PCP task " + taskId);
        }
        return task;
    }

    public double getDeadlineSeconds() {
        return deadlineSeconds;
    }

    public double getBillingPeriodSeconds() {
        return billingPeriodSeconds;
    }

    /** Absolute model instant at which root Jobs may start, including SHARED bootstrap. */
    public double getWorkflowStartSeconds() {
        return workflowStartSeconds;
    }

    public double getVmCostPerSecond(int vmId) {
        return vmCostPerSecondByVm.get(vmId);
    }

    public List<Integer> getVmIds() {
        List<Integer> ids = new ArrayList<Integer>(vmCostPerSecondByVm.keySet());
        Collections.sort(ids);
        return ids;
    }
}
