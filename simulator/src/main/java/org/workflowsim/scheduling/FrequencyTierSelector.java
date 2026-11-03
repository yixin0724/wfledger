package org.workflowsim.scheduling;

import java.util.List;

import org.workflowsim.Job;
import org.workflowsim.platform.PlatformProfile;

/**
 * BAT-D6 (2026-10-29, docs/advanced/DVFS_DIMENSION_DESIGN.md §2.2 registration
 * surface): the <b>first-class scheduling-time API</b> for assigning a frequency tier
 * per Job.
 *
 * <p>Design contract: DVFS assignment happens at dispatch time (assignment at
 * admission, §1.4) -- each compute Job receives exactly one frequency tier after it
 * obtains a VM assignment and before the datacenter submission. The default authority
 * for the tier choice belongs to the session-level
 * {@code SimulationConfig.frequencyPolicy} (DEFAULT/MIN_POWER/EDP_HEURISTIC, a uniform
 * diagonal across all Jobs); when the <b>active scheduling-policy instance</b>
 * implements this interface, the per-Job tier choice moves up to the algorithm -- this
 * is the only channel through which a registered DVFS-aware algorithm
 * ({@code AlgorithmDeclaration.dvfsAware}) can express a heterogeneous tier policy, and
 * it is the first consumer surface that actually uses per-Job tier assignment.</p>
 *
 * <p>Invocation timing and obligations:</p>
 * <ul>
 *   <li>invoked by {@code WorkflowScheduler} only when dispatching a compute Job
 *   <b>that has already obtained a tiered VM assignment</b> (never for tierless VMs,
 *   non-compute Jobs, or the v1 platform -- v1 stays bit-identical);</li>
 *   <li>the returned tier <b>must be a declared tier from the candidate table</b>
 *   (exact identity match on mips; a tier is unique by mips); a {@code null} return or
 *   an out-of-table tier is explicitly rejected by the scheduler (fail-fast), and no
 *   silent fallback to the default tier is provided;
 *   <li>the decision must use only state observable at dispatch time (the declared work
 *   of this Job, the candidate table, the algorithm's own state) and must keep a
 *   deterministic tie-break (AGENTS #9) -- this is the obligation that aligns the
 *   "per-Job tierMips of the evidence chain" with the algorithm behavior; the interface
 *   does not verify it, and it is the declaring responsibility of the algorithm author
 *   (the same discipline as the declaration surface of
 *   {@link org.workflowsim.experiment.registry.AlgorithmDeclaration}).</li>
 * </ul>
 *
 * <p>Tier-table order convention: the candidate table is strictly descending by mips,
 * <b>the first tier = the platform-declared default tier</b> (never implicitly the
 * fastest); length conversion (work / tier rate) is still performed uniformly by
 * {@code WorkflowScheduler.assignFrequencyTier}, and implementations only select --
 * they do not rewrite the Job length or the ledger.</p>
 */
public interface FrequencyTierSelector {

    /**
     * Selects the single frequency tier for a compute Job that has just obtained a VM
     * assignment.
     *
     * @param job   compute Job to dispatch (provides the declared computation MI per PE)
     * @param tiers candidate frequency tiers of the target VM (strictly descending by
     *              mips; the first tier = the default tier)
     * @return a declared tier from {@code tiers}; an illegal return value is explicitly
     *         rejected as an evidence-chain break
     */
    PlatformProfile.FrequencyTier selectFrequencyTier(Job job, List<PlatformProfile.FrequencyTier> tiers);
}
