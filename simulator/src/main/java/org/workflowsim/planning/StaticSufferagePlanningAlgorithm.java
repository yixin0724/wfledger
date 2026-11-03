package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.List;

import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * Classic Sufferage baseline for independent tasks.
 *
 * <p>Each round compares, for every Task, the gap between its second-best and best tentative
 * completion time, and maps the Task with the largest sufferage first; ties are broken by Task
 * ID. With only one compatible VM the sufferage is defined as zero so that the ordering stays
 * finite and deterministic.</p>
 */
public final class StaticSufferagePlanningAlgorithm extends StaticIndependentPlanningSupport {

    @Override
    public void run() {
        final String algorithm = "STATIC_SUFFERAGE";
        List<CondorVM> vms = sortedVms(algorithm);
        double[] availability = new double[vms.size()];
        List<Task> remaining = new ArrayList<Task>(independentTasks(algorithm));
        while (!remaining.isEmpty()) {
            Task selectedTask = null;
            Choice selectedChoice = null;
            double selectedSufferage = Double.NEGATIVE_INFINITY;
            for (Task task : remaining) {
                Choice best = earliestCompletion(task, vms, availability, algorithm);
                double secondBest = Double.POSITIVE_INFINITY;
                for (int index = 0; index < vms.size(); index++) {
                    if (index == best.getVmIndex()) {
                        continue;
                    }
                    CondorVM candidate = vms.get(index);
                    double duration = executionTime(task, candidate);
                    if (!Double.isInfinite(duration)) {
                        secondBest = Math.min(secondBest, availability[index] + duration);
                    }
                }
                // With a single compatible VM there is no alternative to lose, so the sufferage is
                // set to zero to keep the ordering finite and deterministic.
                double sufferage = Double.isInfinite(secondBest) ? 0.0 : secondBest - best.getFinishTime();
                if (selectedChoice == null
                        || sufferage > selectedSufferage
                        || (Double.compare(sufferage, selectedSufferage) == 0
                                && task.getCloudletId() < selectedTask.getCloudletId())) {
                    selectedTask = task;
                    selectedChoice = best;
                    selectedSufferage = sufferage;
                }
            }
            assign(selectedTask, selectedChoice.getVm());
            availability[selectedChoice.getVmIndex()] = selectedChoice.getFinishTime();
            remaining.remove(selectedTask);
        }
    }
}
