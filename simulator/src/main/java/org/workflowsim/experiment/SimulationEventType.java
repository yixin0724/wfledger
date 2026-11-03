package org.workflowsim.experiment;

/**
 * Stable vocabulary of the events produced by one {@link SimulationRunner} run.
 *
 * <p>The event stream describes the modeled lifecycle inside the simulator; it does not
 * claim that equivalent events ever occurred on a production platform.</p>
 *
 * <p><strong>PLAT-19: division of labor between SCHEDULING_DECISION and
 * JOB_DISPATCHED.</strong> Both are recorded in pairs for the same job at the same
 * simulation time and both carry {@code queueDelaySeconds}, but they serve different duties
 * and are deliberately not merged:</p>
 * <ul>
 * <li>{@code SCHEDULING_DECISION} -- algorithm decision evidence, consumed by
 * {@link SimulationMetrics} to compute the ready-to-decision and decision-to-start delays;
 * a duplicate record breaks the event stream (fail-fast).</li>
 * <li>{@code JOB_DISPATCHED} -- dispatch audit entry proving that the scheduling result
 * entered the event queue (including the dispatch delay); currently used only by the
 * events.jsonl evidence chain and external analysis.</li>
 * </ul>
 */
public enum SimulationEventType {
    WORKFLOW_PARSED,
    /**
     * R5 dynamic arrival: a workflow input becomes schedulable at its configured submission
     * time (root jobs clear the arrival gate).
     */
    WORKFLOW_ARRIVED,
    PLANNING_COMPLETED,
    JOBS_CLUSTERED,
    STAGE_IN_JOB_CREATED,
    JOB_READY,
    SCHEDULING_CYCLE,
    /**
     * Decision point where the algorithm selects a VM for a job; consumed by
     * SimulationMetrics, and a duplicate breaks the event stream.
     */
    SCHEDULING_DECISION,
    /**
     * Audit point where a job enters the dispatch event queue; evidence-chain use only, and
     * it appears in pairs with SCHEDULING_DECISION at the same simulation time.
     */
    JOB_DISPATCHED,
    DATA_STAGE_IN_MODELED,
    TASK_EXECUTION_MODELED,
    JOB_RETURNED,
    JOB_FAILED,
    RETRY_JOB_CREATED,
    /** Explicit control-ready destination binding/reuse before input preparation; not CPU dispatch. */
    DATAFLOW_VM_ASSIGNED,
    /**
     * F-007 contract revision (2026-10-23, user-adjudicated via the adjudication package):
     * the execution start of one Job execution instance, recorded exactly once per instance
     * ahead of its TASK_EXECUTION_MODELED family. Attributes carry the job envelope start
     * ({@code jobEnvelopeStartSeconds}), the effective and requested data-stage seconds
     * ({@code effectiveDataStageSeconds}, {@code requestedDataStageSeconds}), and the modeled
     * post-stage start ({@code modeledJobStartSeconds}). This makes the decision-to-start and
     * waiting-time families recomputable from the event stream without relying on
     * {@code TASK_EXECUTION_MODELED.taskStartTime} coinciding with the job envelope.
     */
    JOB_EXECUTION_STARTED,
    /**
     * F-006 contract revision (2026-10-23, user-adjudicated via the adjudication package):
     * terminal event of a naturally completed run, recorded exactly once after the stall
     * watchdog and the quiescence checks pass, carrying the simulation termination clock as
     * the event time and as the {@code simulationEndSeconds} attribute. It closes the declared
     * recompute boundary where a positive post-delay tail recorded no events, making the
     * makespan / terminalLifecycleTail / deadline-slack families event-recomputable. Aborted
     * and failed runs deliberately omit this marker.
     */
    SIMULATION_TERMINATED
}
