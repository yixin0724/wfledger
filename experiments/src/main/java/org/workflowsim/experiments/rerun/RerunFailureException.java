package org.workflowsim.experiments.rerun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    public RerunVerdict getVerdict() {
        return verdict;
    }

    public List<String> getDetails() {
        return details;
    }
}
