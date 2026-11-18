package org.workflowsim.scheduling;

import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

public class ReadyBatchMaxMinSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    public void run() {
        validateCloudletCompatibility();
        List<CondorVM> vms = sortedVmsById();
        int taskCount = getCloudletList().size();
        boolean[] scheduled = new boolean[taskCount];
        int scheduledCount = 0;

        while (scheduledCount < taskCount) {
            double globalMaxEct = -1.0;
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
