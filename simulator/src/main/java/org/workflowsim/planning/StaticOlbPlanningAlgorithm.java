package org.workflowsim.planning;

import java.util.List;

import org.workflowsim.CondorVM;
import org.workflowsim.Task;

public final class StaticOlbPlanningAlgorithm extends StaticIndependentPlanningSupport {

    @Override
    public void run() {
        final String algorithm = "STATIC_OLB";
        List<CondorVM> vms = sortedVms(algorithm);
        double[] availability = new double[vms.size()];
        for (Task task : independentTasks(algorithm)) {
            int selectedIndex = -1;
            for (int index = 0; index < vms.size(); index++) {
                CondorVM candidate = vms.get(index);
                if (!isCompatible(task, candidate)) {
                    continue;
                }
                if (selectedIndex < 0
                        || availability[index] < availability[selectedIndex]
                        || (Double.compare(availability[index], availability[selectedIndex]) == 0
                                && candidate.getId() < vms.get(selectedIndex).getId())) {
                    selectedIndex = index;
                }
            }
            if (selectedIndex < 0) {
                throw noCompatibleVm(task, algorithm);
            }
            CondorVM selected = vms.get(selectedIndex);
            assign(task, selected);
            availability[selectedIndex] += executionTime(task, selected);
        }
    }
}
