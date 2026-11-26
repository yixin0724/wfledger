package org.workflowsim.scheduling;

import java.util.ArrayList;
import java.util.List;

import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.utils.CrchCheckpointModel;
import org.workflowsim.utils.CrchConfig;
import org.workflowsim.utils.CrchRecovery;
import org.workflowsim.utils.CrchRegistry;
import org.workflowsim.utils.TaskExecutionModel;

/** CRCH placement: earliest checkpointed finish among the idle PE-compatible VMs. */
public final class CrchSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    public void run() {
        CrchConfig crch = CrchRegistry.get();
        validateCloudletCompatibility();
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
                if (vm.getState() != WorkflowSimTags.VM_STATUS_IDLE || !isCompatible(job, vm)) {
                    continue;
                }
                double seconds = executionSeconds(job, vm, recovery, crch);
                if (seconds < best - 1.0e-9) {
                    best = seconds;
                    selected = vm;
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

    private static double executionSeconds(Job job, CondorVM vm, CrchRecovery recovery, CrchConfig crch) {
        if (job.getTaskList().size() != 1) {
            return job.getEffectiveExecutionLengthMi() / vm.getMips();
        }
        Task task = job.getTaskList().get(0);
        long workMi = TaskExecutionModel.executionLengthMi(task, vm.getId(), vm.getMips());
        if (recovery != null && recovery.creditOn(vm.getId()) > 0L) {
            workMi = recovery.getRemainingWorkMi();
        }
        return CrchCheckpointModel.attemptLengthMi(workMi, vm.getMips(), crch) / vm.getMips();
    }
}
