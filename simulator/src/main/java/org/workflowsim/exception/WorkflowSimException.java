package org.workflowsim.exception;

public class WorkflowSimException extends Exception {

    public WorkflowSimException(String message) {
        super(message);
    }

    public WorkflowSimException(String message, Throwable cause) {
        super(message, cause);
    }

    public WorkflowSimException(Throwable cause) {
        super(cause);
    }
}
