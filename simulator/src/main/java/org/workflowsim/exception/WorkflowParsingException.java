package org.workflowsim.exception;

public final class WorkflowParsingException extends WorkflowSimException {

    private final String inputPath;

    public WorkflowParsingException(String message) {
        super(message);
        this.inputPath = null;
    }

    public WorkflowParsingException(String message, String inputPath) {
        super(message + (inputPath != null ? " (input: " + inputPath + ")" : ""));
        this.inputPath = inputPath;
    }

    public WorkflowParsingException(String message, String inputPath, Throwable cause) {
        super(message + (inputPath != null ? " (input: " + inputPath + ")" : ""), cause);
        this.inputPath = inputPath;
    }

    public String getInputPath() {
        return inputPath;
    }
}
