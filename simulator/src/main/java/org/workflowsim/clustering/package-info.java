/**
 * Workflow task clustering implementations.
 *
 * <p>This package organizes logical tasks into submittable jobs and provides basic,
 * horizontal, vertical, and block clustering strategies. Clustering changes the mapping
 * from tasks to jobs and the execution events observable afterwards; a study that uses it
 * must record the clustering strategy as an explicit experimental factor. The current
 * standard {@code SimulationRunner} supports only the controlled research track without
 * task clustering.</p>
 */
package org.workflowsim.clustering;
