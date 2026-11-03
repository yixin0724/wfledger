package org.workflowsim.exception;

/**
 * Exception thrown when a simulation configuration violates model constraints or
 * algorithm preconditions.
 *
 * <p>This exception indicates that the parameters of
 * {@link org.workflowsim.utils.SimulationConfig},
 * {@link org.workflowsim.platform.PlatformProfile},
 * {@link org.workflowsim.failure.FailureModelConfig}, or another configuration object are
 * invalid or incompatible.</p>
 *
 * <p>Typical scenarios:</p>
 * <ul>
 *   <li>the VM count does not match the number of platform VM specs</li>
 *   <li>the algorithm combination is not supported by the standard runner</li>
 *   <li>a precondition required by the shared-storage DAG planner is not met (for
 *       example, overhead is enabled)</li>
 *   <li>VM pricing information required by the cost model is missing</li>
 *   <li>a random seed or a numeric parameter is outside its legal range</li>
 * </ul>
 *
 * <p>Callers should check and fix the configuration before the simulation starts, rather
 * than treating this exception as a retryable runtime error.</p>
 *
 * <p><strong>Note:</strong> this exception extends {@link RuntimeException} because
 * configuration errors should normally be found before startup and do not require
 * mandatory catching. This follows the fail-fast design principle.</p>
 */
public final class SimulationConfigurationException extends RuntimeException {

    /**
     * Creates the exception with a description of the configuration error.
     *
     * @param message specific description of the configuration error
     */
    public SimulationConfigurationException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a description of the configuration error and its root
     * cause.
     *
     * @param message specific description of the configuration error
     * @param cause underlying exception that made configuration validation fail
     */
    public SimulationConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
