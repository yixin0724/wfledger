package org.workflowsim.scheduling;

import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * Max-Min scheduler for the WorkflowSim online ready-batch lifecycle.
 *
 * <p>It considers only currently ready Jobs and idle compatible VMs; it is not a static
 * Max-Min mapper that maintains future VM availability times for an independent-task
 * batch.</p>
 *
 * <p>Algorithm definition:</p>
 * <ol>
 *   <li>For each unscheduled task, compute its earliest completion time on every machine
 *       (ECT)</li>
 *   <li>Take the minimum ECT of each task</li>
 *   <li>Among the minimum ECTs of all tasks, select the global maximum (large tasks are
 *       scheduled first)</li>
 *   <li>Assign that task to the corresponding machine</li>
 *   <li>Repeat until all tasks are assigned or no idle VM remains in this update</li>
 * </ol>
 *
 * <p>The only difference from Min-Min is step 3: Max-Min takes the maximum, Min-Min the
 * minimum. An equal task minimum ECT selects the smaller VM ID; an equal global maximum ECT
 * selects the smaller Job ID. Max-Min tends to schedule large tasks first, so that small
 * tasks can fill resources that become idle later.</p>
 *
 * <p>Reference: Braun, T.D., et al. "A Comparison of Eleven Static Heuristics for
 * Mapping a Class of Independent Tasks onto Heterogeneous Distributed Computing
 * Systems." JPDC 2001.</p>
 */
public class ReadyBatchMaxMinSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    public void run() {
        validateCloudletCompatibility();
        List<CondorVM> vms = sortedVmsById();
        int taskCount = getCloudletList().size();
        boolean[] scheduled = new boolean[taskCount];
        int scheduledCount = 0;

        while (scheduledCount < taskCount) {
            double globalMaxEct = -1.0; // Max-Min takes the maximum
            int globalMaxTaskIdx = -1;
            int globalMaxVmIdx = -1;

            for (int t = 0; t < taskCount; t++) {
                if (scheduled[t]) {
                    continue;
                }
                Cloudlet cloudlet = (Cloudlet) getCloudletList().get(t);
                double minEctForTask = Double.MAX_VALUE;
                int bestVmForTask = -1;

                for (int v = 0; v < vms.size(); v++) {
                    CondorVM vm = vms.get(v);
                    if (vm.getState() != WorkflowSimTags.VM_STATUS_IDLE || !isCompatible(cloudlet, vm)) {
                        continue;
                    }
                    double ect = (double) cloudlet.getCloudletLength() / vm.getMips();
                    if (ect < minEctForTask) {
                        minEctForTask = ect;
                        bestVmForTask = v;
                    }
                }

                // Max-Min: take the maximum among the per-task minimum ECTs
                if (bestVmForTask != -1
                        && (minEctForTask > globalMaxEct
                                || (Double.compare(minEctForTask, globalMaxEct) == 0
                                        && globalMaxTaskIdx != -1
                                        && cloudlet.getCloudletId()
                                                < ((Cloudlet) getCloudletList().get(globalMaxTaskIdx))
                                                        .getCloudletId()))) {
                    globalMaxEct = minEctForTask;
                    globalMaxTaskIdx = t;
                    globalMaxVmIdx = bestVmForTask;
                }
            }

            if (globalMaxTaskIdx == -1) {
                break;
            }

            Cloudlet selectedCloudlet = (Cloudlet) getCloudletList().get(globalMaxTaskIdx);
            CondorVM selectedVm = vms.get(globalMaxVmIdx);
            selectedVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            selectedCloudlet.setVmId(selectedVm.getId());
            getScheduledList().add(selectedCloudlet);
            scheduled[globalMaxTaskIdx] = true;
            scheduledCount++;
        }
    }
}
