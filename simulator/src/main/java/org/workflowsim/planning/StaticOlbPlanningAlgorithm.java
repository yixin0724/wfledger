package org.workflowsim.planning;

import java.util.List;

import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * Opportunistic Load Balancing (OLB) baseline for independent tasks.
 *
 * <p>Selects, in Task-ID order, the compatible VM with the earliest tentative availability time;
 * ties on availability go to the smaller VM ID, and the Task execution time is then added to that
 * VM. It applies only to dependency-free task sets.</p>
 */
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
