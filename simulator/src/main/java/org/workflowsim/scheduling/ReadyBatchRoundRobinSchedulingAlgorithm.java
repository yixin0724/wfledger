package org.workflowsim.scheduling;

import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * Round-robin scheduler for the WorkflowSim online ready-batch lifecycle.
 *
 * <p>Maintains a VM cursor that advances to the next VM after each allocation and wraps
 * around. The policy instance is held by {@code WorkflowScheduler} for the duration of one
 * simulation, so the cursor persists across ready-batches; the scan skips busy or
 * PE-incompatible VMs until no candidate remains in the current round.</p>
 *
 * <p>The difference from the historical {@code RoundRobinSchedulingAlgorithm} is that the
 * latter declares {@code vmIndex} but never uses it and is in fact First-Fit-Idle; only
 * this implementation rotates a persistent cursor.</p>
 */
public class ReadyBatchRoundRobinSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    /** VM cursor, preserved across calls to {@link #run()}. */
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
        // Normalize the cursor defensively to prevent an out-of-range access when the VM list
        // size changes
        vmCursor = vmCursor % vmCount;

        for (int i = 0; i < size; i++) {
            Cloudlet cloudlet = (Cloudlet) getCloudletList().get(i);

            // Search for an idle VM starting at the cursor position
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
                break; // no idle VM available
            }

            selectedVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            cloudlet.setVmId(selectedVm.getId());
            getScheduledList().add(cloudlet);

            // Advance the cursor after a successful allocation so that the next round starts at the
            // successor VM.
            vmCursor = (vmCursor + 1) % vmCount;
        }
    }
}
