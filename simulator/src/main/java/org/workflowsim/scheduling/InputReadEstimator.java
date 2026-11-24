package org.workflowsim.scheduling;

import org.workflowsim.Job;

/**
 * Supplies the input-read seconds that the runtime will add to a ready Job's execution envelope.
 *
 * <p>FGCS-R3 (2026-11-24, B1): root cause — online ready-batch policies compared bare
 * {@code length/MIPS} although SHARED execution also charges a storage read that the SHARED planners
 * already included. {@code WorkflowScheduler} binds the datacenter's own estimate, so both layers use
 * one cost function.
 */
public interface InputReadEstimator {

    /**
     * Returns the VM-independent input-read seconds charged inside the Job's envelope.
     *
     * @param job ready Job whose inputs are registered
     * @return finite non-negative seconds
     */
    double inputReadSeconds(Job job);
}
