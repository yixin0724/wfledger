/**
 * Online scheduling policies for ready jobs at workflow runtime.
 *
 * <p>The schedulers in this package observe only the jobs that are ready at the current event
 * time and the idle VMs; each scheduling update assigns at most one job to each VM. The
 * maintained-track {@code FCFS}, {@code READY_BATCH_*} and {@code DATA} implementations all
 * break ties deterministically by VM ID or job ID, and fail fast when no VM with a compatible
 * PE count can be found.</p>
 *
 * <p>They are not offline static mappers: they do not maintain future VM availability, generate
 * a complete execution plan, or replay a real queue. Historical labels remain in this package
 * solely for compatibility with existing callers; research entry points should use
 * maintained-track labels that carry an explicit semantic contract.</p>
 */
package org.workflowsim.scheduling;
