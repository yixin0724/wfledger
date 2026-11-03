/**
 * Abstract Host, VM, storage, and cost models plus a deterministic VM placement model used by
 * reproducible experiments.
 *
 * <p>This package only describes and validates simulation resource configuration. The VM-to-Host
 * preflight and the actual creation mapping can be used for replay checks, but they do not
 * constitute a model of Host CPU contention, utilization, or real infrastructure performance.</p>
 */
package org.workflowsim.platform;
