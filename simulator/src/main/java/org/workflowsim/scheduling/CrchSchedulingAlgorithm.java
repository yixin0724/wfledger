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

/**
 * CRCH placement: earliest checkpointed finish among the idle PE-compatible VMs.
 *
 * <p>FGCS-R4 (2026-11-24): root cause - this was the only online policy without the PE filter, so a
 * multi-PE Job could be placed on a narrower VM.
 *
 * <p>FGCS-R5 (2026-11-24): root cause - a retry always avoided the failed VM and still skipped the
 * saved work, although the paper's checkpoints are VM-local: a resubmission on the same VM resumes
 * from the latest checkpoint, one on a different VM re-executes the completed checkpoints
 * (arXiv:1810.06361v2 Sec. 3.1.3, Eq. 20 versus Eq. 21). Each candidate is now scored by its
 * execution seconds {@code (W_v + ceil(n_v·γ·mips_v)) / mips_v}, where {@code W_v} is the work after
 * the latest checkpoint on the checkpointing VM and the full work elsewhere, and {@code n_v} its
 * checkpoints; the minimum wins and ties go to the lower VM ID. All candidates are idle now, so the
 * common start term {@code now} does not change the order. A busy failed VM is not a candidate in
 * that round; the platform has no repair interval, so the paper's wait-for-{@code Y} branch has no
 * counterpart.
 */
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
