package org.workflowsim.experiments.rerun;

public enum RerunVerdict {
    IDENTICAL_CORE(0),

    DIVERGED(1),

    INPUT_UNRESOLVED(2),

    INPUT_HASH_MISMATCH(3),

    RECONSTRUCTION_REJECTED(4),

    EVIDENCE_INVALID(5);

    private final int exitCode;

    RerunVerdict(int exitCode) {
        this.exitCode = exitCode;
    }

    public int getExitCode() {
        return exitCode;
    }
}
