package org.workflowsim.experiment;

/** Whether a study writes a per-run evidence bundle in addition to its own summary. */
public enum EvidenceOutput {

    /** Write only the study's summary files; no per-run manifest, metrics, event stream or sidecar. */
    SUMMARY,

    /** Write the summary and one evidence bundle per run. */
    FULL;

    public boolean requiresPerRunArtifacts() {
        return this == FULL;
    }
}
