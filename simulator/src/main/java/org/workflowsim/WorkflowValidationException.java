package org.workflowsim;

/**
 * Thrown when a workflow input cannot be represented as a valid task directed acyclic
 * graph (DAG).
 *
 * <p>Callers must treat this exception as an input, or an input conversion result, that
 * violates the model constraints, rather than as a retryable simulation run failure.</p>
 */
public final class WorkflowValidationException extends IllegalArgumentException {

    /**
     * Creates the exception with the validation failure reason.
     *
     * @param message caller-facing description of the validation failure
     */
    public WorkflowValidationException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the validation failure reason and its root cause.
     *
     * @param message caller-facing description of the validation failure
     * @param cause underlying exception that caused the validation failure
     */
    public WorkflowValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
