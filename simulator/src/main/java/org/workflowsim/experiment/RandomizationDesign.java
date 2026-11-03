package org.workflowsim.experiment;

/**
 * Declares the statistical interpretation that the seed sequence of an experiment campaign
 * may adopt.
 *
 * <p>The declaration is recorded with the results but does not change the random model of the
 * simulator.</p>
 */
public enum RandomizationDesign {

    /** A single deterministic run, so sampling inference does not apply. */
    DETERMINISTIC,

    /** Independent replications, allowing a Student-t mean interval per cell. */
    INDEPENDENT_REPLICATIONS,

    /**
     * Candidate runs reuse the root seed, but the current model is not event-keyed, so
     * common-random-numbers paired inference cannot be claimed.
     */
    COMMON_ROOT_SEEDS_NOT_EVENT_KEYED_CRN
}
