package org.workflowsim.scheduling;

import java.util.ArrayList;
import java.util.List;

import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.utils.CrchRecovery;
import org.workflowsim.utils.CrchRegistry;

/** CRCH ready-Job dispatcher with recovery-aware alternative-VM selection. */
public final class CrchSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    public void run() {
        CrchRegistry.get();
        for (CondorVM vm : sortedVmsById()) {
            CrchRegistry.registerVmSpeed(vm.getId(), vm.getMips());
        }
        List<Job> jobs = new ArrayList<Job>();
        for (Object item : getCloudletList()) {
            jobs.add((Job) item);
        }
        for (Job job : jobs) {
            CrchRecovery recovery = CrchRegistry.hasRecovery(job.getCloudletId())
                    ? CrchRegistry.getRecovery(job.getCloudletId())
                    : null;
            CondorVM selected = null;
            double best = Double.POSITIVE_INFINITY;
            for (CondorVM vm : sortedVmsById()) {
                if (vm.getState() != WorkflowSimTags.VM_STATUS_IDLE) {
                    continue;
                }
                if (recovery != null && vm.getId() == recovery.getFailedVmId()) {
                    continue;
                }
                double seconds = job.getEffectiveExecutionLengthMi() / vm.getMips();
                if (seconds < best - 1.0e-9
                        || (Math.abs(seconds - best) <= 1.0e-9
                                && (selected == null || vm.getId() < selected.getId()))) {
                    best = seconds;
                    selected = vm;
                }
            }
            if (selected == null && recovery != null) {
                for (CondorVM vm : sortedVmsById()) {
                    if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                        selected = vm;
                        break;
                    }
                }
            }
            if (selected == null) {
                continue;
            }
            selected.setState(WorkflowSimTags.VM_STATUS_BUSY);
            job.setVmId(selected.getId());
            getScheduledList().add(job);
        }
    }
}
