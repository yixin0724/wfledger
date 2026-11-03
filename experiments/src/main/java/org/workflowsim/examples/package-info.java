/**
 * Teaching and compatibility example entry points.
 *
 * <p>Except for the examples in the {@code wfcommons} subpackage that explicitly use
 * {@code SimulationConfig}, {@code PlatformProfile}, and {@code SimulationRunner}, most
 * classes in this package retain the historical static API of WorkflowSim, so that legacy
 * CloudSim initialization, workflow assembly, and compatibility behavior can be studied.
 * They are not the standard entry point for research experiments; formal experiments
 * should use the immutable configuration, experiment plans, and auditable artifact chain
 * in {@code org.workflowsim.experiment}.</p>
 */
package org.workflowsim.examples;
