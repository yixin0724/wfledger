package org.workflowsim.experiments.rerun;

/**
 * Exhaustive set of rerun verdicts (see the report format section of
 * {@code RERUN_DIFF_CONTRACT.md}).
 *
 * <p>{@code IDENTICAL_CORE} is the only success verdict and exits with code 0; every other
 * verdict has its own non-zero exit code, so rerun can serve directly as a CI or gate step.
 * Adding a verdict requires revising the contract document in the same change;
 * implementations must not bypass the enum.</p>
 */
public enum RerunVerdict {

    /** All core quantities are identical bit for bit. */
    IDENTICAL_CORE(0),

    /** At least one core quantity differs; the divergence list is non-empty. */
    DIVERGED(1),

    /** Inputs cannot be located (all candidate paths came up empty). */
    INPUT_UNRESOLVED(2),

    /** Inputs were located but their sha256 or size does not match the manifest record. */
    INPUT_HASH_MISMATCH(3),

    /**
     * Configuration reconstruction was rejected by current-code validation (a legitimate
     * exposure of historical evidence that is not compatible with current semantics).
     */
    RECONSTRUCTION_REJECTED(4),

    /**
     * The original evidence triple itself failed structural validation, or is not manifest
     * v4.
     */
    EVIDENCE_INVALID(5);

    private final int exitCode;

    RerunVerdict(int exitCode) {
        this.exitCode = exitCode;
    }

    /** Returns the value as the process exit code: 0 on success, distinct for every other verdict. */
    public int getExitCode() {
        return exitCode;
    }
}
