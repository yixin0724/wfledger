/**
 * Core workflow execution model of WorkflowSim.
 *
 * <p>This package defines logical tasks ({@link org.workflowsim.Task}), clustered jobs
 * ({@link org.workflowsim.Job}), file dependencies, DAG validation, input parsing, and the
 * event lifecycle among planners, schedulers, datacenters, and the workflow engine. It
 * keeps the original WorkflowSim/CloudSim compatibility interfaces and is also driven by
 * the standard research entry point in {@code org.workflowsim.experiment}.</p>
 *
 * <p>This package describes an abstract discrete-event model; it does not represent a real
 * cloud platform, network topology, storage contention, or the replay of original
 * WfInstances execution traces. An auditable research runtime should establish a
 * controlled session through {@code SimulationConfig}, {@code PlatformProfile}, and
 * {@code SimulationRunner}, rather than modifying legacy static parameters directly.</p>
 */
package org.workflowsim;
