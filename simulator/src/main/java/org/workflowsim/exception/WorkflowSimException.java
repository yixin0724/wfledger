package org.workflowsim.exception;

/**
 * Root class of the WorkflowSim simulator exceptions.
 *
 * <p>All WorkflowSim-specific exceptions should extend this class or one of its subclasses so
 * that callers can catch and handle simulator-related errors uniformly. This exception is a
 * checked exception, so callers must handle it explicitly or declare it in their throws
 * clause.</p>
 *
 * <p>Exception hierarchy:</p>
 * <ul>
 *   <li>{@code WorkflowSimException} - root class</li>
 *   <li>{@code SimulationConfigurationException} - configuration error</li>
 *   <li>{@code WorkflowParsingException} - input parsing error</li>
 *   <li>{@code SimulationExecutionException} - simulation execution error</li>
 *   <li>{@code PlatformException} - platform/resource error</li>
 * </ul>
 *
 * @see SimulationConfigurationException
 * @see WorkflowParsingException
 * @see SimulationExecutionException
 */
public class WorkflowSimException extends Exception {

    /**
     * Create an exception with an error message.
     *
     * @param message message describing the cause of the error
     */
    public WorkflowSimException(String message) {
        super(message);
    }

    /**
     * Create an exception with an error message and a root cause.
     *
     * @param message message describing the cause of the error
     * @param cause underlying exception that caused this exception
     */
    public WorkflowSimException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Create an exception with a root cause.
     *
     * @param cause underlying exception that caused this exception
     */
    public WorkflowSimException(Throwable cause) {
        super(cause);
    }
}
