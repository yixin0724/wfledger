package org.workflowsim.exception;

/**
 * Thrown when a workflow input file cannot be parsed or converted into a valid DAG model.
 *
 * <p>This exception covers the following error scenarios:</p>
 * <ul>
 *   <li>malformed input file (invalid XML/JSON structure)</li>
 *   <li>missing required field or type mismatch</li>
 *   <li>task graph contains a cycle and cannot be represented as a DAG</li>
 *   <li>file dependency references a non-existent task or file</li>
 *   <li>the WfCommons JSON runtime field cannot be converted to MI</li>
 * </ul>
 *
 * <p>Callers should treat this exception as an input-data problem, not as a simulator-internal
 * error. Researchers must check the integrity and format correctness of the input file.</p>
 *
 * @see org.workflowsim.WorkflowParser
 * @see org.workflowsim.WfCommonsJsonParser
 * @see org.workflowsim.WorkflowValidationException
 */
public final class WorkflowParsingException extends WorkflowSimException {

    private final String inputPath;

    /**
     * Creates the exception with a parse error description.
     *
     * @param message specific description of the parse failure
     */
    public WorkflowParsingException(String message) {
        super(message);
        this.inputPath = null;
    }

    /**
     * Creates the exception with a parse error description and the input path.
     *
     * @param message specific description of the parse failure
     * @param inputPath path of the input file that caused the parse failure
     */
    public WorkflowParsingException(String message, String inputPath) {
        super(message + (inputPath != null ? " (input: " + inputPath + ")" : ""));
        this.inputPath = inputPath;
    }

    /**
     * Creates the exception with a parse error description, the input path, and the root cause.
     *
     * @param message specific description of the parse failure
     * @param inputPath path of the input file that caused the parse failure
     * @param cause underlying exception that caused the parse failure
     */
    public WorkflowParsingException(String message, String inputPath, Throwable cause) {
        super(message + (inputPath != null ? " (input: " + inputPath + ")" : ""), cause);
        this.inputPath = inputPath;
    }

    /**
     * Returns the path of the input file that caused the parse failure.
     *
     * @return input file path, or {@code null} if it was not specified
     */
    public String getInputPath() {
        return inputPath;
    }
}
