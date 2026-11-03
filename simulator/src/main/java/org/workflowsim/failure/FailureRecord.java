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
package org.workflowsim.failure;

/**
 * One observed task-execution failure record, used by the monitor to estimate the failure
 * rate and by the recovery policy.
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class FailureRecord {

    /**
     * Run-time length of the tasks covered by this record.
     */
    public double length;
    /**
     * Number of failed tasks.
     */
    public int failedTasksNum;
    /**
     * DAG depth of the failed tasks (some legacy paths also use it as the type).
     */
    public int depth; // Some legacy paths use the depth as the type.
    /**
     * Total number of tasks covered by this record (both successful and failed).
     */
    public int allTaskNum;
    /**
     * Identifier of the VM on which the failure occurred.
     */
    public int vmId;
    /**
     * Identifier of the Job on which the failure occurred.
     */
    public int jobId;
    /**
     * Workflow identifier (maps to the user id in the current legacy interface).
     */
    public int workflowId;
    /**
     * Delay overhead taken into account during recovery.
     */
    public double delayLength;

    /**
     * Creates a failure observation record.
     *
     * @param length run-time length of the tasks
     * @param tasks number of failed tasks
     * @param depth DAG depth of the failed tasks
     * @param all total number of covered tasks
     * @param vm identifier of the VM on which the failure occurred
     * @param job identifier of the Job on which the failure occurred
     * @param workflow identifier of the workflow in which the failure occurred
     */
    public FailureRecord(double length, int tasks, int depth, int all, int vm, int job, int workflow) {
        this.length = length;
        this.failedTasksNum = tasks;
        this.depth = depth;
        this.allTaskNum = all;
        this.vmId = vm;
        this.jobId = job;
        this.workflowId = workflow;
    }
}
