package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Vm;
import org.workflowsim.Task;
import org.workflowsim.utils.TaskExecutionModel;

public final class PsoFitnessFunction {

    private PsoFitnessFunction() {}

    public static double evaluate(int[] position, List<Task> tasks, List<? extends Vm> vms, double costWeight) {
        if (tasks == null || tasks.isEmpty() || vms == null || vms.isEmpty()) {
            throw new IllegalArgumentException("PSO fitness requires non-empty task and VM lists");
        }
        if (position == null || position.length != tasks.size()) {
            throw new IllegalArgumentException("PSO position must match task count");
        }
        Map<Integer, List<Task>> vmTasks = new HashMap<Integer, List<Task>>();
        for (int i = 0; i < tasks.size(); i++) {
            int vmIndex = position[i];
            if (vmIndex < 0 || vmIndex >= vms.size()) {
                throw new IllegalArgumentException("PSO position value out of range: " + vmIndex);
            }
            if (tasks.get(i).getNumberOfPes() > vms.get(vmIndex).getNumberOfPes()) {
                return Double.POSITIVE_INFINITY;
            }
            List<Task> bucket = vmTasks.get(Integer.valueOf(vmIndex));
            if (bucket == null) {
                bucket = new ArrayList<Task>();
                vmTasks.put(Integer.valueOf(vmIndex), bucket);
            }
            bucket.add(tasks.get(i));
        }

        double totalCost = 0.0;
        double makespan = 0.0;
        for (Map.Entry<Integer, List<Task>> entry : vmTasks.entrySet()) {
            Vm vm = vms.get(entry.getKey().intValue());
            double vmLoad = 0.0;
            for (Task task : entry.getValue()) {
                double execTime = TaskExecutionModel.executionSeconds(task, vm.getId(), vm.getMips());
                vmLoad += execTime;
                totalCost += execTime * getPrice(vm);
            }
            makespan = Math.max(makespan, vmLoad);
        }
        return costWeight * totalCost + (1.0 - costWeight) * makespan;
    }

    public static double getPrice(Vm vm) {
        return vm.getMips() / 1000.0;
    }
}
