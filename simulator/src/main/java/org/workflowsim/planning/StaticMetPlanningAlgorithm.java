package org.workflowsim.planning;

import java.util.List;

import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * Minimum Execution Time (MET) baseline for independent tasks.
 *
 * <p>In Task-ID order, selects the compatible VM with the smallest pure execution time
 * {@code taskLength / vmMips}; equal times select the smaller VM ID. It maintains no queued
 * availability time, so it cannot be used to explain scheduling waits or DAG dependencies.</p>
 */
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
