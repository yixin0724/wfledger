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

import java.util.ArrayList;
import java.util.List;

/**
 * Clustered job (Job).
 *
 * <p>{@code Job} extends {@link Task} and keeps the original tasks that compose it. After
 * {@link ClusteringEngine} merges tasks into a job, the job length is usually the sum of the
 * lengths of its tasks; the concrete clustering policy and the file transfer estimate remain the
 * responsibility of the clustering engine.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class Job extends Task {

    /** Original task list that composes this clustered job. */
    private List<Task> taskList;

    /**
     * BAT-D2 (2026-10-29): DVFS tier MIPS assigned by the scheduler when this Job is dispatched.
     *
     * <p>Immutable "assignment at dispatch time": null = v1 no-tier mode (the platform declares no
     * tier, or this Job is not a compute Job); non-null = the [startTime, finishTime) interval of
     * this Job executes at the speed of this tier. Written by {@code WorkflowScheduler} after the
     * VM assignment, it enters the evidence chain through {@code JobOutcome.fromJob} and is frozen
     * by the report capture before the run.</p>
     */
    private Double tierMips;

    /**
     * Create a clustered job.
     *
     * @param jobId unique job identifier
     * @param jobLength job length to execute, in million instructions (MI)
     */
    public Job(final int jobId, final long jobLength) {

        super(jobId, jobLength);
        this.taskList = new ArrayList<>();
    }

    /** @return original task list that composes this job */
    public List<Task> getTaskList() {
        return this.taskList;
    }

    /**
     * BAT-D2: return the tier MIPS assigned when this Job was dispatched.
     *
     * @return MIPS of the assigned tier; null = v1 no-tier mode (v1 compatibility and stage-in Job)
     */
    public Double getTierMips() {
        return this.tierMips;
    }

    /**
     * BAT-D2: record the tier assignment at dispatch time (exactly once per Job; reserved for the
     * scheduler dispatch path).
     *
     * @param tierMips MIPS of the assigned tier; must be a positive finite value
     * @throws IllegalArgumentException if the value is not positive or not finite
     */
    public void setTierMips(final Double tierMips) {
        if (tierMips == null || !(tierMips.doubleValue() > 0.0) || tierMips.isNaN() || tierMips.isInfinite()) {
            throw new IllegalArgumentException("Frequency tier mips must be positive and finite: " + tierMips);
        }
        this.tierMips = tierMips;
    }

    /**
     * Replace the original task list that composes this job.
     *
     * @param list new original task list
     */
    public void setTaskList(List list) {
        this.taskList = list;
    }

    /**
     * Append a group of original tasks to the job.
     *
     * @param list task list to append
     */
    public void addTaskList(List list) {
        this.taskList.addAll(list);
    }

    /**
     * Return the parent task list of this job.
     *
     * @return parent task list
     */
    @Override
    public List getParentList() {
        return super.getParentList();
    }
}
