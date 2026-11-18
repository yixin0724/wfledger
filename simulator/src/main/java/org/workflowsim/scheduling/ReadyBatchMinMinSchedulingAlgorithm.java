package org.workflowsim.scheduling;

import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

public class ReadyBatchMinMinSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    public void run() {
        validateCloudletCompatibility();
        List<CondorVM> vms = sortedVmsById();
        int taskCount = getCloudletList().size();
        boolean[] scheduled = new boolean[taskCount];
        int scheduledCount = 0;

        while (scheduledCount < taskCount) {

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

                    double ect = (double) cloudlet.getCloudletLength() / vm.getMips();
                    if (ect < minEctForTask) {
                        minEctForTask = ect;
                        bestVmForTask = v;
                    }
                }

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
                break;
            }

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
