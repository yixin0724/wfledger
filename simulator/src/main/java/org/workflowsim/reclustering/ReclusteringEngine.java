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
import org.cloudbus.cloudsim.core.CloudSim;
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
                jobList.add(createJob(id, job, job.getEffectiveExecutionLengthMi(), job.getTaskList(), true));

                break;

            case FTCLUSTERING_CRCH:
                CrchRecovery recovery = CrchRegistry.recordFailure(
                        job.getTaskList().get(0).getCloudletId(),
                        job.getVmId(),
                        job.getExecStartTime(),
                        CloudSim.clock(),
                        job.getEffectiveExecutionLengthMi(),
                        CrchRegistry.getVmMips(job.getVmId()));
                long remaining = recovery.getRemainingWorkMi()
                        + (long) Math.ceil(CrchRegistry.get().getCheckpointOverheadSeconds()
                                * CrchRegistry.getVmMips(job.getVmId()));
                if (remaining <= 0L) {
                    remaining = 1L;
                }
                CrchRegistry.registerRetry(id, recovery);
                jobList.add(createJob(id, job, remaining, job.getTaskList(), true));
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
