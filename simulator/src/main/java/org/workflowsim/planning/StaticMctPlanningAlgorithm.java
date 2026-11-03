package org.workflowsim.planning;

import java.util.List;

import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * Minimum Completion Time (MCT) baseline for independent tasks.
 *
 * <p>Maps tasks one at a time in deterministic Task-ID order, each time selecting the compatible
 * VM with the smallest {@code availability + executionTime} and updating that VM's provisional
 * availability time. It is not a runtime ready-batch MCT scheduler.</p>
 */
public final class StaticMctPlanningAlgorithm extends StaticIndependentPlanningSupport {

    @Override
    public void run() {
        final String algorithm = "STATIC_MCT";
        List<CondorVM> vms = sortedVms(algorithm);
        double[] availability = new double[vms.size()];
        for (Task task : independentTasks(algorithm)) {
            Choice choice = earliestCompletion(task, vms, availability, algorithm);
            assign(task, choice.getVm());
            availability[choice.getVmIndex()] = choice.getFinishTime();
        }
    }
}
