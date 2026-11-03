package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.List;

import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * Classic Min-Min completion-time baseline for independent tasks.
 *
 * <p>Each round first finds the minimum tentative completion time of every remaining Task and
 * then selects the global minimum; equal completion times are broken by Task ID, and VM-level
 * ties are broken by VM ID in the shared logic. This policy rejects Tasks that carry DAG edges.</p>
 */
public final class StaticMinMinPlanningAlgorithm extends StaticIndependentPlanningSupport {

    @Override
    public void run() {
        final String algorithm = "STATIC_MINMIN";
        List<CondorVM> vms = sortedVms(algorithm);
        double[] availability = new double[vms.size()];
        List<Task> remaining = new ArrayList<Task>(independentTasks(algorithm));
        while (!remaining.isEmpty()) {
            Task selectedTask = null;
            Choice selectedChoice = null;
            for (Task task : remaining) {
                Choice choice = earliestCompletion(task, vms, availability, algorithm);
                if (selectedChoice == null
                        || choice.getFinishTime() < selectedChoice.getFinishTime()
                        || (Double.compare(choice.getFinishTime(), selectedChoice.getFinishTime()) == 0
                                && task.getCloudletId() < selectedTask.getCloudletId())) {
                    selectedTask = task;
                    selectedChoice = choice;
                }
            }
            assign(selectedTask, selectedChoice.getVm());
            availability[selectedChoice.getVmIndex()] = selectedChoice.getFinishTime();
            remaining.remove(selectedTask);
        }
    }
}
