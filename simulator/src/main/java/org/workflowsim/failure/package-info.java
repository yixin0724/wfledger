/**
 * Failure generation, monitoring, and session-level failure-model configuration.
 *
 * <p>Failure distributions and monitoring modes describe scenario assumptions within the
 * simulation, not a calibration of real platform failure rates. When random failures are
 * used, record the complete configuration through {@code SimulationConfig} and the seed
 * plan, and avoid reusing stateful generators across runs.</p>
 */
package org.workflowsim.failure;
