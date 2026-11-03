/**
 * Exception type hierarchy of the WorkflowSim simulator.
 *
 * <p>This package provides a precise exception taxonomy that helps researchers locate the
 * root cause of a problem quickly:</p>
 *
 * <ul>
 *   <li>{@link org.workflowsim.exception.WorkflowSimException} -
 *       root class of all WorkflowSim exceptions</li>
 *   <li>{@link org.workflowsim.exception.SimulationConfigurationException} -
 *       failed validation of configuration parameters, algorithm combinations, or model
 *       constraints</li>
 *   <li>{@link org.workflowsim.exception.WorkflowParsingException} -
 *       input file parsing, DAG conversion, or field mapping errors</li>
 *   <li>{@link org.workflowsim.exception.SimulationExecutionException} -
 *       runtime errors in CloudSim execution, event handling, or scheduling algorithms</li>
 *   <li>{@link org.workflowsim.exception.PlatformException} -
 *       platform resource configuration, capacity constraint, or VM-to-Host placement
 *       errors</li>
 * </ul>
 *
 * <h2>Usage guide</h2>
 *
 * <p><strong>Researcher's view</strong>: when an experiment fails, the exception type
 * directly indicates the part that needs inspection:</p>
 * <ul>
 *   <li>{@code SimulationConfigurationException} -> check {@code SimulationConfig} and the
 *       algorithm combination</li>
 *   <li>{@code WorkflowParsingException} -> check the input file format and DAG
 *       integrity</li>
 *   <li>{@code PlatformException} -> check the feasibility of the Host/VM resource
 *       configuration</li>
 *   <li>{@code SimulationExecutionException} -> inspect the log; the issue may need to be
 *       reported to the maintainers</li>
 * </ul>
 *
 * <p><strong>Developer's view</strong>: throw the most precise exception type:</p>
 * <pre>{@code
 * // Configuration validation
 * if (config.getVmCount() != platform.getVms().size()) {
 *     throw new SimulationConfigurationException(
 *         "VM count mismatch: config=" + config.getVmCount() +
 *         ", platform=" + platform.getVms().size());
 * }
 *
 * // Input parsing
 * if (!dagValidator.isAcyclic(tasks)) {
 *     throw new WorkflowParsingException(
 *         "Workflow contains cycles", inputPath);
 * }
 *
 * // Platform resources
 * if (totalRequiredPes > host.getNumberOfPes()) {
 *     throw new PlatformException(
 *         "Host PE capacity insufficient: required=" + totalRequiredPes +
 *         ", available=" + host.getNumberOfPes());
 * }
 * }</pre>
 *
 * <h2>Relationship to legacy exceptions</h2>
 *
 * <ul>
 *   <li>{@link org.workflowsim.WorkflowValidationException} continues to be used for DAG
 *       structure validation and should now be regarded as a specific case of
 *       {@code WorkflowParsingException}</li>
 *   <li>{@link org.workflowsim.failure.RetryLimitExceededException} is the runtime
 *       termination condition of a protected budget and keeps its
 *       {@code IllegalStateException} inheritance</li>
 * </ul>
 *
 * @since WorkflowSim 1.0
 */
package org.workflowsim.exception;
