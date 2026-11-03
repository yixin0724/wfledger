package org.workflowsim.scheduling;

import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * Min-Min scheduler for the WorkflowSim online ready-batch lifecycle.
 *
 * <p>It considers only currently ready Jobs and idle compatible VMs; in one update each VM
 * receives at most one Job. It is not equivalent to a standalone-task static Min-Min mapper
 * that maintains future machine availability times.</p>
 *
 * <p>Algorithm definition:</p>
 * <ol>
 *   <li>For each unscheduled task, compute its earliest completion time on every machine
 *       (ECT = readyTime + length/mips)</li>
 *   <li>Take the minimum ECT of each task</li>
 *   <li>Among the minimum ECTs of all tasks, select the global minimum</li>
 *   <li>Assign that task to the corresponding machine</li>
 *   <li>Repeat until all tasks are assigned or no idle VM remains in this update</li>
 * </ol>
 *
 * <p>Reference: Braun, T.D., et al. "A Comparison of Eleven Static Heuristics for
 * Mapping a Class of Independent Tasks onto Heterogeneous Distributed Computing
 * Systems." JPDC 2001.</p>
 *
 * <p>ECT is computed only on VMs that are idle at the current event instant; an equal task
 * ECT selects the smaller VM ID, and an equal global minimum ECT selects the smaller Job ID.
 * A static mapper, by contrast, must maintain each VM's tentative availability time.</p>
 */
public class ReadyBatchMinMinSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    public void run() {
        validateCloudletCompatibility();
        List<CondorVM> vms = sortedVmsById();
        int taskCount = getCloudletList().size();
        boolean[] scheduled = new boolean[taskCount];
        int scheduledCount = 0;

        while (scheduledCount < taskCount) {
            // For the current ready set, compute the ECT of each task on each idle VM
            double globalMinEct = Double.MAX_VALUE;
            int globalMinTaskIdx = -1;
            int globalMinVmIdx = -1;

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
                    // ECT = length / mips (readyTime is assumed 0 because the VM is idle)
                    double ect = (double) cloudlet.getCloudletLength() / vm.getMips();
                    if (ect < minEctForTask) {
                        minEctForTask = ect;
                        bestVmForTask = v;
                    }
                }

                // The minimum ECT of this task enters the global comparison
                if (bestVmForTask != -1
                        && (minEctForTask < globalMinEct
                                || (Double.compare(minEctForTask, globalMinEct) == 0
                                        && globalMinTaskIdx != -1
                                        && cloudlet.getCloudletId()
                                                < ((Cloudlet) getCloudletList().get(globalMinTaskIdx))
                                                        .getCloudletId()))) {
                    globalMinEct = minEctForTask;
                    globalMinTaskIdx = t;
                    globalMinVmIdx = bestVmForTask;
                }
            }

            if (globalMinTaskIdx == -1) {
                break; // No idle VM left, or no unscheduled task remains
            }

            // Dispatch the selected task to the selected VM
            Cloudlet selectedCloudlet = (Cloudlet) getCloudletList().get(globalMinTaskIdx);
            CondorVM selectedVm = vms.get(globalMinVmIdx);
            selectedVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            selectedCloudlet.setVmId(selectedVm.getId());
            getScheduledList().add(selectedCloudlet);
            scheduled[globalMinTaskIdx] = true;
            scheduledCount++;
        }
    }
}
