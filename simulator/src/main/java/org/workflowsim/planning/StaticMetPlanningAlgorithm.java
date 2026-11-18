package org.workflowsim.planning;

import java.util.List;

import org.workflowsim.CondorVM;
import org.workflowsim.Task;

public final class StaticMetPlanningAlgorithm extends StaticIndependentPlanningSupport {

    @Override
    public void run() {
        final String algorithm = "STATIC_MET";
        List<CondorVM> vms = sortedVms(algorithm);
        for (Task task : independentTasks(algorithm)) {
            assign(task, fastestMachine(task, vms, algorithm));
        }
    }
}
