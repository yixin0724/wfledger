package org.workflowsim.experiments.rerun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Machine-readable failure of the rerun flow: carries a {@link RerunVerdict} and an optional detail
 * list.
 *
 * <p>The contract requires every failure step to fail fast and give a reason; callers map the verdict
 * to an exit code and write the message and details into the report. This exception does not
 * indicate a program bug, but is one of the legitimate failure paths defined by the contract
 * (including informational conclusions such as {@code RECONSTRUCTION_REJECTED}).</p>
 */
public final class RerunFailureException extends Exception {

    private static final long serialVersionUID = 1L;

    private final RerunVerdict verdict;
    private final List<String> details;

    public RerunFailureException(RerunVerdict verdict, String message) {
        this(verdict, message, Collections.<String>emptyList(), null);
    }

    public RerunFailureException(RerunVerdict verdict, String message, List<String> details) {
        this(verdict, message, details, null);
    }

    public RerunFailureException(RerunVerdict verdict, String message, Throwable cause) {
        this(verdict, message, Collections.<String>emptyList(), cause);
    }

    public RerunFailureException(RerunVerdict verdict, String message, List<String> details, Throwable cause) {
        super(message, cause);
        if (verdict == null || message == null || details == null) {
            throw new IllegalArgumentException("Verdict, message and details are required");
        }
        this.verdict = verdict;
        this.details = Collections.unmodifiableList(new ArrayList<String>(details));
    }

    /** Contract verdict corresponding to this failure. */
    public RerunVerdict getVerdict() {
        return verdict;
    }

    /**
     * Additional details, such as the candidate paths that were attempted or the concrete reason for
     * rejection; may be an empty list, never null.
     */
    public List<String> getDetails() {
        return details;
    }
}
