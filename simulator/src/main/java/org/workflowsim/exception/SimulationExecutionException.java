package org.workflowsim.exception;

/**
 * Thrown when an unrecoverable error occurs during CloudSim simulation execution.
 *
 * <p>This exception represents a failure in the simulation execution layer, including but not
 * limited to:</p>
 * <ul>
 *   <li>CloudSim entity creation failure</li>
 *   <li>exception in the event processing flow</li>
 *   <li>VM creation failure or VM-to-Host mapping inconsistency</li>
 *   <li>internal error while executing a scheduling or planning algorithm</li>
 *   <li>exception in a data movement model computation</li>
 * </ul>
 *
 * <p>This exception differs from a configuration error
 * ({@link SimulationConfigurationException}): a configuration error is a static constraint
 * violation that is detectable before startup, whereas an execution exception is a failure
 * that occurs dynamically at runtime.</p>
 *
 * <p>When researchers encounter this exception they should check:</p>
 * <ol>
 *   <li>the detailed stack trace in the simulator log</li>
 *   <li>whether the combination of configuration and input triggered an unexpected edge case</li>
 *   <li>whether a potential implementation defect should be reported to the simulator
 *       maintainers</li>
 * </ol>
 */
public final class SimulationExecutionException extends WorkflowSimException {

    /**
     * Creates the exception with an execution error description.
     *
     * @param message specific description of the execution failure
     */
    public SimulationExecutionException(String message) {
        super(message);
    }

    /**
     * Creates the exception with an execution error description and the root cause.
     *
     * @param message specific description of the execution failure
     * @param cause underlying exception that caused the execution failure
     */
    public SimulationExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates the exception with the root cause.
     *
     * @param cause underlying exception that caused the execution failure
     */
    public SimulationExecutionException(Throwable cause) {
        super(cause);
    }
}
