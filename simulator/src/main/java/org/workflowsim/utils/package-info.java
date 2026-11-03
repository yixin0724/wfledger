/**
 * Configuration, randomness, cost, failure-parameter, and legacy global-state support for
 * WorkflowSim.
 *
 * <p>{@link org.workflowsim.utils.SimulationConfig} is the immutable declaration of one
 * standard run; {@link org.workflowsim.utils.SimulationSession} manages the legacy global
 * state of {@code Parameters}, the replica catalog, and the failure models around each
 * serial run. Because the underlying CloudSim kernel keeps static state, this package does
 * not promise concurrent execution of multiple simulation sessions inside one JVM.</p>
 */
package org.workflowsim.utils;
