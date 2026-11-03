package org.workflowsim.scheduling;

/**
 * C1 (2026-11-02): scheduling surface for deferral-capable policies.
 *
 * <p>An online scheduler usually "dispatches as soon as a job is ready"; the carbon-aware
 * deferral engine instead <b>presses the job into a cleaner time window</b> before running it.
 * Deferral must be explicit: when a policy chooses not to dispatch any job in this round, it
 * must provide a <b>future wake-up instant</b>, and
 * {@link org.workflowsim.WorkflowScheduler} schedules a {@code CLOUDLET_UPDATE} event to
 * advance the clock past it. No wake-up instant and no dispatch means deadlock; the scheduler
 * fails explicitly instead of hanging silently (rule: failure must be explicit).</p>
 */
public interface DeferralAwareSchedulingAlgorithm {

    /**
     * @return absolute simulation instant at which the next scheduling round should run;
     *     {@link Double#POSITIVE_INFINITY} means nothing was deferred in this round and no
     *     wake-up is required
     */
    double getNextWakeSeconds();

    /**
     * @return number of ready jobs deferred in this round (used for scheduling-cycle records
     *     and evidence)
     */
    int getDeferredJobCount();
}
