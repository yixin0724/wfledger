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
 *
 * <p><b>Demo interface scope (AUD-F11)</b>: this track is a demonstration-grade environment
 * interface, not a research-grade MDP formulation. The observation is deliberately
 * non-Markovian -- it carries only the current ready-Job queue features and the VM load
 * view, with no DAG structure, no network/transfer state, and no history of past actions or
 * observations; the reward is emitted only at the terminal state ({@code -makespan}) with no
 * per-step shaping; and the policy registry is a static single-process registry, so
 * concurrent episodes in one JVM are not supported. These boundaries are deliberate
 * simplifications of the demo closed loop; research use that needs a Markovian state or
 * step rewards must extend the observation/reward contracts first.</p>
 */
package org.workflowsim.rl;
