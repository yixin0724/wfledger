package org.workflowsim.scheduling;

import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

public class ReadyBatchRoundRobinSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    private int vmCursor = 0;

    @Override
    public void run() {
        validateCloudletCompatibility();
        int size = getCloudletList().size();
        List<CondorVM> vmList = sortedVmsById();
        int vmCount = vmList.size();

        if (vmCount == 0) {
            return;
        }

        vmCursor = vmCursor % vmCount;

        for (int i = 0; i < size; i++) {
            Cloudlet cloudlet = (Cloudlet) getCloudletList().get(i);

            CondorVM selectedVm = null;
            int checked = 0;
            while (checked < vmCount) {
                CondorVM vm = vmList.get(vmCursor);
                if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE && isCompatible(cloudlet, vm)) {
                    selectedVm = vm;
                    break;
                }
                vmCursor = (vmCursor + 1) % vmCount;
                checked++;
            }

            if (selectedVm == null) {
                break;
            }

            selectedVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            cloudlet.setVmId(selectedVm.getId());
            getScheduledList().add(cloudlet);

            vmCursor = (vmCursor + 1) % vmCount;
        }
    }
}
