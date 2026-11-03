/**
 * R4 RL scheduling track: state, action, and reward contracts plus the environment loop.
 *
 * <p>This package wraps the deterministic event loop of the platform into a reinforcement
 * learning environment: state = the ready-Job queue plus the VM load view
 * ({@link org.workflowsim.rl.RlObservation}), action = the VM choice for each ready Job
 * ({@link org.workflowsim.rl.RlPolicy}), reward = -makespan
 * ({@link org.workflowsim.rl.RlEpisodeResult}). An episode is driven by
 * {@link org.workflowsim.rl.RlEnvironment}, which executes internally through the standard
 * {@code SimulationRunner}, so the decision trace is fully auditable.</p>
 *
 * <p>Wiring: configure {@code SchedulingAlgorithm.RL_POLICY} (online scheduling track; the
 * planning layer must be INVALID) and run through {@code RlEnvironment.runEpisode}.
 * Running an RL_POLICY configuration directly with {@code SimulationRunner} fails
 * explicitly because no registered policy is available.</p>
 */
package org.workflowsim.rl;
