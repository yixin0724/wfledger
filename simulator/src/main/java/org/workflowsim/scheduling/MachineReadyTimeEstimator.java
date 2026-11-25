package org.workflowsim.scheduling;

import org.workflowsim.CondorVM;

/**
 * Supplies the machine ready time {@code r_v} of a created VM at the current scheduling round.
 *
 * <p>FGCS-R6 (2026-11-25): root cause — the ready-batch Min-Min/Max-Min/MCT policies considered only
 * idle VMs and no machine ready time, so with a VM-independent ETC order they reduced to SPT/LJF/FIFO
 * on the fastest idle VM. The literature heuristics (Maheswaran et al. 1999; Braun et al. 2001) rank
 * completion times {@code r_v + ETC}. {@code WorkflowScheduler} binds the runtime's own no-failure
 * completion prediction of the Job each busy VM is running.
 */
public interface MachineReadyTimeEstimator {

    /**
     * Returns the seconds until the VM can start another Job: 0 for an idle VM, otherwise the
     * no-failure remaining time of its running Job's execution envelope.
     *
     * @param vm created VM
     * @return finite non-negative seconds
     */
    double readySeconds(CondorVM vm);
}
