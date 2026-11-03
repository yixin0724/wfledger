package org.workflowsim.scheduling;

import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * MCT scheduler for the WorkflowSim online ready-batch lifecycle.
 *
 * <p>For the current ready Job, compute {@code ECT = cloudletLength / vmMips} for every idle
 * compatible VM and select the smallest value; VM candidates are ordered by ascending ID, so an
 * ECT tie selects the smaller VM ID. Each VM receives at most one Job per update. Offline
 * static MCT additionally maintains a tentative availability time per machine; this
 * implementation does not claim that semantics.</p>
 *
 * <p>Reference: Maheswaran, M., et al. "Dynamic Mapping of a Class of Independent
 * Tasks onto Heterogeneous Computing Systems." JPDC 1999.</p>
 */
public class ReadyBatchMCTSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    public void run() {
        validateCloudletCompatibility();
        List<CondorVM> vms = sortedVmsById();
        int size = getCloudletList().size();

        for (int i = 0; i < size; i++) {
            Cloudlet cloudlet = (Cloudlet) getCloudletList().get(i);
            CondorVM bestVm = null;
            double minCompletionTime = Double.MAX_VALUE;

            for (CondorVM vm : vms) {
                if (vm.getState() != WorkflowSimTags.VM_STATUS_IDLE || !isCompatible(cloudlet, vm)) {
                    continue;
                }
                // At the current event time the VM is idle, so ECT reduces to execution length over VM MIPS.
                double completionTime = (double) cloudlet.getCloudletLength() / vm.getMips();
                if (completionTime < minCompletionTime) {
                    minCompletionTime = completionTime;
                    bestVm = vm;
                }
            }

            if (bestVm == null) {
                break; // no idle VM
            }

            bestVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            cloudlet.setVmId(bestVm.getId());
            getScheduledList().add(cloudlet);
        }
    }
}
