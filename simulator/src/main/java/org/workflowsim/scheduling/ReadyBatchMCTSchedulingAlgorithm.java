package org.workflowsim.scheduling;

import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

public class ReadyBatchMCTSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    public void run() {
        validateCloudletCompatibility();
        List<CondorVM> vms = sortedVmsById();
        int size = getCloudletList().size();

        for (int i = 0; i < size; i++) {
            Cloudlet cloudlet = (Cloudlet) getCloudletList().get(i);
            double readSeconds = inputReadSeconds(cloudlet);
            CondorVM bestVm = null;
            double minCompletionTime = Double.MAX_VALUE;

            for (CondorVM vm : vms) {
                if (vm.getState() != WorkflowSimTags.VM_STATUS_IDLE || !isCompatible(cloudlet, vm)) {
                    continue;
                }

                double completionTime = envelopeSeconds(cloudlet, vm, readSeconds);
                if (completionTime < minCompletionTime) {
                    minCompletionTime = completionTime;
                    bestVm = vm;
                }
            }

            if (bestVm == null) {
                break;
            }

            bestVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            cloudlet.setVmId(bestVm.getId());
            getScheduledList().add(cloudlet);
        }
    }
}
