package org.workflowsim.exception;

public final class SimulationExecutionException extends WorkflowSimException {

    public SimulationExecutionException(String message) {
        super(message);
    }

    public SimulationExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public SimulationExecutionException(Throwable cause) {
        super(cause);
    }
}
