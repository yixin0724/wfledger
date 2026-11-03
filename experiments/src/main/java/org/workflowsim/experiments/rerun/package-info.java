/**
 * R11 rerun and core-quantity difference comparison (D2): rebuild a run from historical
 * v4 evidence and compare it against the results of the current code.
 *
 * <p>This package only consumes evidence and does not modify simulator semantics. The
 * process contract is {@code docs/experiments/RERUN_DIFF_CONTRACT.md}: read the evidence
 * triple -> locate inputs (sha256 verification) -> rebuild the configuration -> rerun
 * through the standard {@code SimulationRunner} -> compare core quantities exactly ->
 * generate the verdict report. Core quantities must agree bit for bit in their serialized
 * values, with no tolerance for floating-point values; provenance, runtime, and local
 * wall-clock fields belong to the explicitly enumerated whitelist of volatile variables
 * and do not participate in the verdict.</p>
 *
 * <p>v1 scope: one run directory, manifest v4. Historical v2/v3 evidence is readable but
 * cannot be rerun and is rejected explicitly; result-reuse caching and study-level
 * batching are outside this package.</p>
 */
package org.workflowsim.experiments.rerun;
