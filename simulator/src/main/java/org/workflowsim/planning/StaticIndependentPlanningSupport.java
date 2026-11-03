package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.utils.TaskExecutionModel;

/**
 * Deterministic mechanical logic shared by the offline independent-task baselines.
 *
 * <p>These planners only produce VM mappings. WorkflowSim's {@code STATIC} dispatcher carries the
 * mappings into the simulation lifecycle, but does not turn them into DAG algorithms or replay a
 * real queue. Tasks and VMs are ordered by ascending ID, and equivalent candidates are broken by
 * the smaller ID.</p>
 */
abstract class StaticIndependentPlanningSupport extends BasePlanningAlgorithm {

    protected final List<Task> independentTasks(String algorithmName) {
        if (getTaskList() == null) {
            throw new IllegalStateException(algorithmName + " requires a task list");
        }

        List<Task> tasks = new ArrayList<Task>(getTaskList());
        for (Task task : tasks) {
            if (!task.getParentList().isEmpty() || !task.getChildList().isEmpty()) {
                throw new IllegalArgumentException(algorithmName + " supports independent tasks only; "
                        + "use a DAG planner for workflows with dependencies");
            }
        }
        Collections.sort(tasks, new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                return Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        return tasks;
    }

    protected final List<CondorVM> sortedVms(String algorithmName) {
        if (getVmList() == null || getVmList().isEmpty()) {
            throw new IllegalStateException(algorithmName + " requires at least one VM");
        }

        List<CondorVM> vms = new ArrayList<CondorVM>();
        for (Object object : getVmList()) {
            if (!(object instanceof CondorVM)) {
                throw new IllegalArgumentException(algorithmName + " requires CondorVM instances");
            }
            CondorVM vm = (CondorVM) object;
            if (!isPositiveFinite(vm.getMips())) {
                throw new IllegalArgumentException(algorithmName + " requires VMs with finite positive MIPS");
            }
            vms.add(vm);
        }
        Collections.sort(vms, new Comparator<CondorVM>() {
            @Override
            public int compare(CondorVM first, CondorVM second) {
                return Integer.compare(first.getId(), second.getId());
            }
        });
        return vms;
    }

    protected final boolean isCompatible(Task task, CondorVM vm) {
        return task.getNumberOfPes() <= vm.getNumberOfPes();
    }

    protected final double executionTime(Task task, CondorVM vm) {
        // Shares the matrix-based seconds-to-integer-MI conversion with the runtime; without a matrix,
        // the original single-PE length is kept.
        return isCompatible(task, vm)
                ? TaskExecutionModel.executionSeconds(task, vm.getId(), vm.getMips())
                : Double.POSITIVE_INFINITY;
    }

    protected final void assign(Task task, CondorVM vm) {
        task.setVmId(vm.getId());
    }

    protected final CondorVM fastestMachine(Task task, List<CondorVM> vms, String algorithmName) {
        CondorVM selected = null;
        double selectedTime = Double.POSITIVE_INFINITY;
        for (CondorVM vm : vms) {
            double time = executionTime(task, vm);
            if (time < selectedTime
                    || (Double.compare(time, selectedTime) == 0 && selected != null && vm.getId() < selected.getId())) {
                selected = vm;
                selectedTime = time;
            }
        }
        if (selected == null || Double.isInfinite(selectedTime)) {
            throw noCompatibleVm(task, algorithmName);
        }
        return selected;
    }

    /**
     * Selects the compatible VM minimizing {@code availability[vm] + effectiveComputeSeconds(task, vm)}.
     * Equal finish times go to the smaller VM ID.
     */
    protected final Choice earliestCompletion(
            Task task, List<CondorVM> vms, double[] availability, String algorithmName) {
        CondorVM selected = null;
        int selectedIndex = -1;
        double selectedFinish = Double.POSITIVE_INFINITY;
        for (int index = 0; index < vms.size(); index++) {
            CondorVM vm = vms.get(index);
            double duration = executionTime(task, vm);
            double finish = Double.isInfinite(duration) ? Double.POSITIVE_INFINITY : availability[index] + duration;
            if (finish < selectedFinish
                    || (Double.compare(finish, selectedFinish) == 0
                            && selected != null
                            && vm.getId() < selected.getId())) {
                selected = vm;
                selectedIndex = index;
                selectedFinish = finish;
            }
        }
        if (selected == null || Double.isInfinite(selectedFinish)) {
            throw noCompatibleVm(task, algorithmName);
        }
        return new Choice(selected, selectedIndex, selectedFinish);
    }

    protected final IllegalArgumentException noCompatibleVm(Task task, String algorithmName) {
        return new IllegalArgumentException(algorithmName + " cannot map task " + task.getCloudletId()
                + "; no VM has sufficient processing elements");
    }

    protected final boolean isPositiveFinite(double value) {
        return value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    protected static final class Choice {

        private final CondorVM vm;
        private final int vmIndex;
        private final double finishTime;

        private Choice(CondorVM vm, int vmIndex, double finishTime) {
            this.vm = vm;
            this.vmIndex = vmIndex;
            this.finishTime = finishTime;
        }

        protected CondorVM getVm() {
            return vm;
        }

        protected int getVmIndex() {
            return vmIndex;
        }

        protected double getFinishTime() {
            return finishTime;
        }
    }
}
