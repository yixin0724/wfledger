/**
 * Offline Task-to-VM static mapping for workflows and controlled static DAG planning policies.
 *
 * <p>The {@code STATIC_*} baselines accept only dependency-free independent task sets and emit a
 * deterministic VM mapping; they do not become DAG schedulers or real queue replays. The
 * {@code SHARED_STORAGE_*} tracks compute a static DAG plan with reserved-zone insertion under a
 * controlled model with shared storage, no clustering, no failures, no overhead, and
 * {@code SPACE_SHARED} VMs, and they expose an auditable decision trace.</p>
 *
 * <p>The historical {@code HEFT} and {@code DHEFT} planning enumerations were removed in the R9
 * cleanup round; use the {@code SHARED_STORAGE_*} family for dependency-aware DAG planning and the
 * {@code STATIC_*} family for independent task mapping.</p>
 */
package org.workflowsim.planning;
