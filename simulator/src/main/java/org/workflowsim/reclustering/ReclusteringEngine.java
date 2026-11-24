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
package org.workflowsim.reclustering;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.CrchRecovery;
import org.workflowsim.utils.CrchRegistry;

public class ReclusteringEngine {

    private static Job createJob(int id, Job job, long length, List taskList, boolean updateDep) {
        if (job == null) {
            throw new IllegalArgumentException("Cannot recreate a null failed job");
        }
        Job newJob = new Job(id, length);
        newJob.setUserId(job.getUserId());
        if (!newJob.setClassType(job.getClassType())) {
            throw new IllegalStateException(
                    "Failed Job " + job.getCloudletId() + " has no executable class type to preserve for retry");
        }

        newJob.setVmId(job.getVmId());
        newJob.setNumberOfPes(job.getNumberOfPes());
        try {
            newJob.setCloudletStatus(Cloudlet.CREATED);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not initialize retry Job " + id, exception);
        }

        newJob.setTaskList(taskList == null ? new ArrayList<Task>() : copyTasks(taskList));
        populateRetryFileMetadata(newJob);
        newJob.setDepth(job.getDepth());
        if (updateDep) {
            newJob.setChildList(job.getChildList());
            newJob.setParentList(job.getParentList());
            for (Iterator it = job.getChildList().iterator(); it.hasNext(); ) {
                Job cJob = (Job) it.next();
                cJob.getParentList().remove(job);
                cJob.addParent(newJob);
            }
        }
        return newJob;
    }

    public static List<Job> process(Job job, int id) {
        if (job == null) {
            throw new IllegalArgumentException("Cannot process a null failed job");
        }
        if (id < 0) {
            throw new IllegalArgumentException("Retry job ID must be non-negative");
        }
        if (job.getTaskList() == null || job.getTaskList().isEmpty()) {
            throw new IllegalArgumentException("Failed Job " + job.getCloudletId() + " has no tasks to recreate");
        }
        List jobList = new ArrayList();

        switch (FailureParameters.getFTCluteringAlgorithm()) {
            case FTCLUSTERING_NOOP:
                jobList.add(createJob(id, job, job.getRestartWorkMi(), job.getTaskList(), true));

                break;

            case FTCLUSTERING_CRCH:
                // FGCS-R4 (2026-11-24): root cause - the window ran from the envelope start to the engine
                // clock, so the input read and post-processing delay were credited as checkpointed compute.
                // FGCS-R5 (2026-11-24): root cause - the attempt always ran to its modeled finish and the
                // retry added an ad-hoc ceil(gamma*mips) MI. The attempt now ends at its failure instant;
                // the paper charges no restore term beyond the lost work (arXiv:1810.06361v2 Eq. 20), and
                // the retry's executed length is projected per VM at submission.
                Task attempt = job.getTaskList().get(0);
                if (!job.hasFailureInstant()) {
                    throw new IllegalStateException("Failed CRCH Job " + job.getCloudletId()
                            + " has no failure instant inside its compute window");
                }
                CrchRecovery recovery = CrchRegistry.recordFailure(
                        attempt.getCloudletId(),
                        job.getVmId(),
                        job.getCrchCreditedWorkMi(),
                        job.getCrchAttemptWorkMi(),
                        job.getCrchPlannedCheckpoints(),
                        job.getFailureInstantSeconds() - attempt.getExecStartTime(),
                        CrchRegistry.getVmMips(job.getVmId()));
                CrchRegistry.registerRetry(id, recovery);
                jobList.add(createJob(id, job, recovery.getWorkMi(), job.getTaskList(), true));
                break;
            default:
                throw new IllegalStateException("Unsupported fault-tolerant clustering algorithm: "
                        + FailureParameters.getFTCluteringAlgorithm());
        }
        return jobList;
    }

    private static List<Task> copyTasks(List taskList) {
        if (taskList == null) {
            throw new IllegalArgumentException("Failed job has no task list to recreate");
        }
        List<Task> copies = new ArrayList<>();
        for (Object rawTask : taskList) {
            if (!(rawTask instanceof Task)) {
                throw new IllegalArgumentException("Failed job task list contains a non-Task value");
            }
            Task source = (Task) rawTask;
            Task copy = new Task(source.getCloudletId(), source.getCloudletLength());
            copy.setUserId(source.getUserId());
            copy.setVmId(source.getVmId());
            copy.setNumberOfPes(source.getNumberOfPes());
            if (source.hasVmExecutionCostSeconds()) {
                copy.setVmExecutionCostSeconds(source.getVmExecutionCostSeconds());
            }

            copy.setDepth(source.getDepth());
            copy.setPriority(source.getPriority());
            copy.setImpact(source.getImpact());
            copy.setType(source.getType());
            copy.setFileList(new ArrayList<>(source.getFileList()));
            for (String requiredFile : source.getRequiredFiles()) {
                copy.addRequiredFile(requiredFile);
            }
            copy.setParentList(new ArrayList<>(source.getParentList()));
            copy.setChildList(new ArrayList<>(source.getChildList()));
            if (!Double.isNaN(source.getStaticScheduleStartTime())) {
                copy.setStaticScheduleStartTime(source.getStaticScheduleStartTime());
            }
            copies.add(copy);
        }
        return copies;
    }

    private static void populateRetryFileMetadata(Job retry) {
        for (Task task : retry.getTaskList()) {
            for (FileItem file : task.getFileList()) {
                if (!retry.getFileList().contains(file)) {
                    retry.getFileList().add(file);
                }
            }
            for (String requiredFile : task.getRequiredFiles()) {
                retry.addRequiredFile(requiredFile);
            }
        }
    }
}
