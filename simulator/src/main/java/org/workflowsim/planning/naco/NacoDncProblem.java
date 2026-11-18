package org.workflowsim.planning.naco;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NacoDncProblem {
    private final Map<Integer, NacoTask> tasksById;
    private final List<NacoTask> tasks;
    private final Map<Integer, Double> networkSpeedByVm;
    private final Map<Integer, Double> diskSpeedByVm;

    public NacoDncProblem(
            List<NacoTask> sourceTasks, Map<Integer, Double> networkSpeedByVm, Map<Integer, Double> diskSpeedByVm) {
        if (sourceTasks == null || sourceTasks.isEmpty() || networkSpeedByVm == null || diskSpeedByVm == null) {
            throw new IllegalArgumentException("NACO DNC requires tasks and VM speed declarations");
        }
        this.tasks = new ArrayList<NacoTask>(sourceTasks);
        Collections.sort(tasks, Comparator.comparingInt(NacoTask::getTaskId));
        this.tasksById = new LinkedHashMap<Integer, NacoTask>();
        for (NacoTask task : tasks) {
            if (this.tasksById.put(task.getTaskId(), task) != null) {
                throw new IllegalArgumentException("Duplicate NACO task ID " + task.getTaskId());
            }
            for (Integer vmId : task.getComputeSecondsByVm().keySet()) {
                validateSpeed(networkSpeedByVm, vmId);
                validateSpeed(diskSpeedByVm, vmId);
            }
        }
        this.networkSpeedByVm = Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(networkSpeedByVm));
        this.diskSpeedByVm = Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(diskSpeedByVm));
    }

    private static void validateSpeed(Map<Integer, Double> speeds, int vmId) {
        Double speed = speeds.get(vmId);
        if (speed == null || !(speed > 0.0) || !Double.isFinite(speed)) {
            throw new IllegalArgumentException("Missing or invalid NACO speed for VM " + vmId);
        }
    }

    public List<NacoTask> getTasks() {
        return Collections.unmodifiableList(tasks);
    }

    public NacoTask getTask(int taskId) {
        NacoTask task = tasksById.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Unknown NACO task " + taskId);
        }
        return task;
    }

    public double getNetworkSpeed(int vmId) {
        return networkSpeedByVm.get(vmId);
    }

    public double getDiskSpeed(int vmId) {
        return diskSpeedByVm.get(vmId);
    }
}
