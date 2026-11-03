/**
 * Copyright 2012-2013 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.workflowsim;

/**
 * WorkflowSim custom event tags.
 *
 * <p>These values are not part of CloudSim's {@code CloudSimTags}; they carry
 * internal event communication among the workflow engine, the scheduler, and the
 * datacenter. Tag values must remain stable to avoid collisions with CloudSim
 * event numbers.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class WorkflowSimTags {

    /** Starting value of the workflow-specific event numbers. */
    private static final int BASE = 1000;
    /** VM ready state; currently reserved and unused. */
    public static final int VM_STATUS_READY = BASE + 2;
    /** VM busy state; the scheduler must not submit new jobs to such a VM. */
    public static final int VM_STATUS_BUSY = BASE + 3;
    /** VM idle state; the VM can accept new jobs. */
    public static final int VM_STATUS_IDLE = BASE + 4;

    public static final int START_SIMULATION = BASE + 0;
    public static final int JOB_SUBMIT = BASE + 1;
    public static final int CLOUDLET_UPDATE = BASE + 5;
    public static final int CLOUDLET_CHECK = BASE + 6;
    /**
     * Under the pre-execution transfer delay model, the engine starts the input
     * transfer once the data is ready and, when the transfer finishes, uses this
     * tag to release the job into the dispatch flow (see
     * {@code PRE_EXECUTION_TRANSFER_DELAY_V1} of
     * {@link org.workflowsim.data.DataMovementModel}).
     */
    public static final int JOB_STAGE_IN_COMPLETE = BASE + 7;
    /**
     * Under the link-contention data movement model
     * ({@code PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1}), the progress
     * check event the engine sends to itself for fluid transfers: integrate all
     * active transfers at the current clock, settle the completed transfer
     * groups, and reschedule the next check while transfers remain active.
     */
    public static final int TRANSFER_CONTENTION_CHECK = BASE + 8;

    /**
     * R5 dynamic arrival: the arrival rescan event the engine sends to itself
     * when multiple workflows are submitted with peak shifting. It fires one
     * idempotent readiness scan at the submission instant of the earliest
     * not-yet-arrived workflow and releases the root jobs of the workflows that
     * have arrived by that instant.
     */
    public static final int WORKFLOW_ARRIVAL_SCAN = BASE + 9;

    /** Dedicated V2 per-file service/visibility observation; never the V1 group-check protocol. */
    public static final int COHERENT_DATAFLOW_CHECK = BASE + 10;

    /** Constants class; instantiation is forbidden. */
    private WorkflowSimTags() {
        throw new UnsupportedOperationException("WorkflowSim Tags cannot be instantiated");
    }
}
