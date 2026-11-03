package org.workflowsim.rl;

/**
 * Policy contract for the R4 RL track: select one VM for each ready Job at
 * every scheduling decision instant.
 *
 * <p>This is the minimal interface between the platform and an external
 * learning algorithm. On every ready-batch update the scheduler builds an
 * {@link RlObservation} and calls {@link #selectVms(RlObservation)}; the
 * returned values correspond one-to-one to the ready Jobs in the observation,
 * and each value is an index into the observed VM list.</p>
 *
 * <p><b>Contract</b>:</p>
 * <ul>
 *   <li>The returned array length must equal
 *       {@code observation.getReadyJobs().size()}; otherwise the scheduler
 *       aborts the simulation with {@code IllegalStateException};</li>
 *   <li>Legal values are in {@code [0, observation.getVms().size())} or
 *       {@link #NO_ASSIGNMENT}; an out-of-range value aborts the simulation (a
 *       defective policy implementation must fail explicitly);</li>
 *   <li>When the selected VM is not idle, has an incompatible PE, or has
 *       already been taken within this update, the scheduler skips that Job (it
 *       stays ready and is decided again on the next update) -- an exploratory
 *       policy does not crash for this reason, but the policy is obliged to
 *       eventually produce an executable action, and the platform watchdog
 *       explicitly intercepts a stall;</li>
 *   <li>An implementation must return the same action for the same observation
 *       (determinism), keeping every episode reproducible bit for bit; when
 *       randomness is needed, use the named seeded random stream
 *       {@code SimulationRandom.newApacheRandom(stream)}.</li>
 * </ul>
 *
 * <p><b>Demo interface scope (AUD-F11)</b>: the observation this contract receives is
 * non-Markovian (no DAG structure, no transfer state, no history) and the reward is emitted
 * only at the terminal state, so a policy implemented against this interface is a
 * demonstration-grade closed loop, not a research-grade MDP learner; see the
 * {@code org.workflowsim.rl} package description for the full boundary statement.</p>
 */
public interface RlPolicy {

    /** Action value: no assignment for this Job this round; it stays ready for the next decision. */
    int NO_ASSIGNMENT = -1;

    /**
     * Selects a VM for each ready Job in the observation.
     *
     * @param observation state snapshot at the decision instant
     * @return VM index array corresponding one-to-one to the ready Jobs (see
     *         the class contract for the legal values)
     */
    int[] selectVms(RlObservation observation);
}
