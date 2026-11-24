package org.workflowsim.scheduling;

import org.workflowsim.Job;

/**
 * Optional callback for online scheduling policies that keep state derived from completed
 * attempts.
 *
 * <p>FGCS-R3 (2026-11-24, A1): root cause — EBPSM published cached outputs and container images
 * only at dispatch, when no Task can be {@code SUCCESS} yet, so its locality preference never had
 * any cache to prefer. {@code WorkflowScheduler} now notifies the active policy when a Job attempt
 * returns, after the failure model has fixed every Task status and before the next scheduling
 * round.
 */
public interface JobReturnListener {

    /**
     * Receives a returned Job attempt whose Task statuses are final.
     *
     * @param job returned attempt, still carrying its executing VM ID
     */
    void onJobReturned(Job job);
}
