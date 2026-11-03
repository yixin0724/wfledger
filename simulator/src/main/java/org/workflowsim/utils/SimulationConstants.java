package org.workflowsim.utils;

/**
 * Model constant definitions for the WorkflowSim simulator.
 *
 * <p>This class centralizes the magic numbers used by the simulator core logic; every constant
 * states its physical meaning, unit, and usage. Centralizing the definitions helps to:</p>
 * <ul>
 *   <li>Improve readability - a constant name states its physical meaning</li>
 *   <li>Simplify uniform adjustment - a model parameter changes in one place</li>
 *   <li>Strengthen traceability - researchers can understand the model assumptions quickly</li>
 * </ul>
 *
 * <h2>Usage example</h2>
 * <pre>{@code
 * // Create a stage-in Job
 * Job stageInJob = new Job(jobId, SimulationConstants.STAGE_IN_JOB_LENGTH_MI);
 *
 * // Check whether the simulation is still in its initialization phase
 * if (CloudSim.clock() < SimulationConstants.INITIAL_PROCESSING_THRESHOLD_SECONDS) {
 *     // Initialization logic
 * }
 * }</pre>
 *
 * @since WorkflowSim 1.0
 */
public final class SimulationConstants {

    /**
     * Fixed length of a stage-in Job in MI (million instructions).
     *
     * <p><strong>Physical meaning:</strong> this value is the instruction count that represents a
     * virtual "data transfer task" in the CloudSim compute model. The actual transfer time is
     * computed by {@link org.workflowsim.data.DataMovementModel}, but CloudSim requires every
     * Cloudlet to have a non-zero length.</p>
     *
     * <p><strong>Historical constraint:</strong> CloudSim requires a Cloudlet length of at least
     * 110 MI, an internal numerical-precision and scheduler-compatibility requirement. A smaller
     * value can make the scheduler behave abnormally.</p>
     *
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>{@link org.workflowsim.ClusteringEngine} - create stage-in Jobs</li>
     *   <li>{@link org.workflowsim.WorkflowDatacenter} - validate Job types</li>
     * </ul>
     *
     * <p><strong>Note:</strong> this constant must not be used to compute the actual transfer
     * time; the transfer time is computed by {@code DataMovementModel} from the file size and the
     * bandwidth.</p>
     */
    public static final long STAGE_IN_JOB_LENGTH_MI = 110L;

    /**
     * Time threshold of the initial processing loop, in simulation seconds.
     *
     * <p><strong>Physical meaning:</strong> during the first 0.111 seconds after the simulation
     * starts the datacenter is in an "initialization phase". In this phase some processing logic
     * (such as resource updates) is skipped to avoid conflicting with the initial event scheduling
     * of the CloudSim kernel.</p>
     *
     * <p><strong>Historical background:</strong> CloudSim sends the initial VM creation and
     * resource-characteristic request events at time 0.1. The 0.111 threshold ensures that those
     * initial events have been processed before the datacenter starts its regular periodic
     * updates.</p>
     *
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>{@link org.workflowsim.WorkflowDatacenter#processOtherEvent} -
     *       decide whether to skip the processing loop of the initial phase</li>
     * </ul>
     *
     * <p><strong>Model limitation:</strong> this is a CloudSim kernel compatibility requirement
     * and does not represent the initialization time of a real system. Changing this value
     * requires careful testing to ensure that the CloudSim event order is not broken.</p>
     */
    public static final double INITIAL_PROCESSING_THRESHOLD_SECONDS = 0.111;

    /**
     * Minimum meaningful task runtime, in MI.
     *
     * <p><strong>Physical meaning:</strong> when a task runtime (in seconds) multiplied by the
     * MIPS and the scaling factor falls below this value, it is rounded up to this minimum. This
     * avoids numerical-precision problems and indeterminate scheduler behavior on zero-length
     * tasks.</p>
     *
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>{@link org.workflowsim.WfCommonsJsonParser} - lower bound when converting the
     *       {@code runtimeInSeconds} field of WfCommons JSON</li>
     *   <li>Task length formula: {@code max(MIN_TASK_LENGTH_MI,
     *       floor(runtimeInSeconds * runtimeReferenceMips * runtimeScale))}</li>
     * </ul>
     *
     * <p><strong>Note:</strong> this minimum is a model assumption and does not represent the
     * shortest execution time of a real task. Its main purpose is to keep the values numerically
     * stable.</p>
     */
    public static final long MIN_TASK_LENGTH_MI = 100L;

    /**
     * Private constructor; prevents instantiation.
     */
    private SimulationConstants() {
        throw new AssertionError("SimulationConstants is a constants class and must not be instantiated");
    }
}
