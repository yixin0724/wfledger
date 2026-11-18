package org.workflowsim.planning;

import java.util.List;

import org.workflowsim.CondorVM;
import org.workflowsim.Task;

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
