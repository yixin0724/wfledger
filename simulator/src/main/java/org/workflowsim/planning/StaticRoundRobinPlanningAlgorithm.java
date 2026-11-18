package org.workflowsim.planning;

import java.util.List;

import org.workflowsim.CondorVM;
import org.workflowsim.Task;

public final class StaticRoundRobinPlanningAlgorithm extends StaticIndependentPlanningSupport {

    @Override
    public void run() {
        final String algorithm = "STATIC_ROUND_ROBIN";
        List<CondorVM> vms = sortedVms(algorithm);
        int nextIndex = 0;
        for (Task task : independentTasks(algorithm)) {
            int selectedIndex = -1;
            for (int offset = 0; offset < vms.size(); offset++) {
                int candidateIndex = (nextIndex + offset) % vms.size();
                if (isCompatible(task, vms.get(candidateIndex))) {
                    selectedIndex = candidateIndex;
                    break;
                }
            }
            if (selectedIndex < 0) {
                throw noCompatibleVm(task, algorithm);
            }
            assign(task, vms.get(selectedIndex));
            nextIndex = (selectedIndex + 1) % vms.size();
        }
    }
}
