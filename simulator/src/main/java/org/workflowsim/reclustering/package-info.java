/**
 * Reclustering logic applied after a task or job failure.
 *
 * <p>This package derives follow-up jobs from the failure record or estimates a suitable
 * clustering size. Its behavior depends on the selected failure, clustering, and
 * scheduling models, so it must be interpreted as a controlled experimental condition
 * rather than as a performance prediction for a real recovery system.</p>
 */
package org.workflowsim.reclustering;
