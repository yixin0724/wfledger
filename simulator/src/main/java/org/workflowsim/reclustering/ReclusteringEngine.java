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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.failure.FailureRecord;
import org.workflowsim.utils.CrchRecovery;
import org.workflowsim.utils.CrchRegistry;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;

/**
 * Legacy reclustering engine that creates one or more retry Jobs after a Job fails.
 *
 * <p>These retry policies continue the WorkflowSim compatibility behavior and depend on
 * {@link FailureParameters}, {@link FailureMonitor}, and the abstract models in the
 * overhead parameters. This is not the new standard experiment entry point, and it cannot
 * by itself prove the recovery effect in a real system.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 *
 */
public class ReclusteringEngine {

    /**
     * Create a new retry Job from a failed Job.
     *
     * @param id new Job identifier
     * @param job failed Job
     * @param length computational length of the new Job
     * @param taskList retry task list
     * @param updateDep whether to write the retry Job into the original dependency relations
     * @return the newly created retry Job
     */
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
        // Data preparation precedes dispatch: a static mapping must already exist on retry.
        // Online schedulers may replace this hint when they make their next decision.
        newJob.setVmId(job.getVmId());
        newJob.setNumberOfPes(job.getNumberOfPes());
        try {
            newJob.setCloudletStatus(Cloudlet.CREATED);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not initialize retry Job " + id, exception);
        }

        // DC/DR build retry Jobs incrementally, so the initial placeholder task list may be null;
        // every genuinely executable retry Job must hold its own task copies.
        newJob.setTaskList(taskList == null ? new ArrayList<Task>() : copyTasks(taskList));
        populateRetryFileMetadata(newJob);
        newJob.setDepth(job.getDepth());
        if (updateDep) {
            newJob.setChildList(job.getChildList());
            newJob.setParentList(job.getParentList());
            for (Iterator it = job.getChildList().iterator(); it.hasNext(); ) {
                Job cJob = (Job) it.next();
                cJob.addParent(newJob);
            }
        }
        return newJob;
    }

    /**
     * Create the list of retry Jobs for a failed Job according to the current
     * fault-tolerant clustering algorithm.
     *
     * @param job failed Job
     * @param id identifier of the first retry Job
     * @return list of newly created retry Jobs
     */
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
                // The retry Job is not yet submitted, so the submission state of the failed Job needs no handling.
                break;
                /**
                 * Dynamic reclustering.
                 */
            case FTCLUSTERING_DC:
                jobList = DCReclustering(jobList, job, id, job.getTaskList());
                break;
                /**
                 * Selective reclustering.
                 */
            case FTCLUSTERING_SR:
                jobList = SRReclustering(jobList, job, id);

                break;
                /**
                 * Estimator-based dynamic reclustering.
                 */
            case FTCLUSTERING_DR:
                jobList = DRReclustering(jobList, job, id, job.getTaskList());
                break;
                /**
                 * Block reclustering by DAG depth.
                 */
            case FTCLUSTERING_BLOCK:
                jobList = BlockReclustering(jobList, job, id);
                break;
                /**
                 * Binary reclustering by DAG depth.
                 */
            case FTCLUSTERING_VERTICAL:
                jobList = VerticalReclustering(jobList, job, id);
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

    /**
     * A retry is a fresh execution attempt. Copy the task identity and modeling metadata,
     * but never share mutable state or timing information with the failed attempt, so the
     * retained failure evidence is not polluted.
     */
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
            // Do not copy the old effective work/timestamps: resolve the new attempt afresh.
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

    /**
     * Rebuild the Job-level file list and required-file union from the copied retry tasks.
     *
     * <p>A retry Job may contain only part of the tasks of the original clustering Job, so the
     * full file list of the original Job cannot be copied verbatim. The reaggregated compute Job
     * still enters the existing data-stage input model, which decides from the copy directory
     * whether an actual transfer is required.</p>
     */
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

    /**
     * Split candidate tasks stably into retry Jobs under a fixed upper bound.
     *
     * <p>DC and DR share this path, which guarantees that each candidate task enters exactly one
     * output Job. A full cluster is closed before the current task is added, so tasks that sit
     * exactly on a cluster boundary are not dropped as in the old implementation. When
     * {@code failedOnly} is {@code true}, only tasks whose status is {@link Cloudlet#FAILED} are
     * retained.</p>
     *
     * @param job failed source Job
     * @param firstId identifier of the first new Job
     * @param candidates candidate tasks in their original order
     * @param clusterSize maximum number of tasks per retry Job, must be positive
     * @param failedOnly whether to retry failed tasks only
     * @return retry Jobs with consecutive identifiers, rebuilt file metadata, and connected
     *         dependency edges
     */
    static List<Job> partitionRetryTasks(
            Job job, int firstId, List<Task> candidates, int clusterSize, boolean failedOnly) {
        if (job == null || candidates == null) {
            throw new IllegalArgumentException("Retry partition requires a Job and candidate tasks");
        }
        if (firstId < 0 || clusterSize <= 0) {
            throw new IllegalArgumentException("Retry Job ID must be non-negative and cluster size positive");
        }

        List<Job> retries = new ArrayList<Job>();
        List<Task> currentTasks = new ArrayList<Task>();
        long currentLength = 0L;
        int nextId = firstId;
        for (Task task : candidates) {
            if (task == null) {
                throw new IllegalArgumentException("Retry candidate task cannot be null");
            }
            if (failedOnly && task.getCloudletStatus() != Cloudlet.FAILED) {
                continue;
            }
            if (currentTasks.size() == clusterSize) {
                retries.add(createJob(nextId++, job, currentLength, currentTasks, false));
                currentTasks = new ArrayList<Task>();
                currentLength = 0L;
            }
            if (task.getCloudletLength() > Long.MAX_VALUE - currentLength) {
                throw new IllegalStateException("Retry Job length overflow for failed Job " + job.getCloudletId());
            }
            currentTasks.add(task);
            currentLength += task.getCloudletLength();
        }
        if (!currentTasks.isEmpty()) {
            retries.add(createJob(nextId, job, currentLength, currentTasks, false));
        }
        if (!retries.isEmpty()) {
            updateDependencies(job, retries);
        }
        return retries;
    }

    /**
     * Partition the task list by task DAG depth.
     *
     * @param list task list to partition
     * @return mapping from DAG depth to tasks
     */
    private static Map<Integer, List<Task>> getDepthMap(List<Task> list) {
        Map<Integer, List<Task>> map = new HashMap<>();

        for (Task task : list) {
            int depth = task.getDepth();
            if (!map.containsKey(depth)) {
                map.put(depth, new ArrayList<>());
            }
            List<Task> dl = map.get(depth);
            if (!dl.contains(task)) {
                dl.add(task);
            }
        }

        return map;
    }
    // Shared helper logic for dynamic reclustering and layer-wise recovery.

    /**
     * Return the minimum level in a depth map for the dynamic and layer-wise recovery policies.
     *
     * @param map mapping from DAG depth to tasks
     * @return minimum key in the map; -1 for an empty map
     */
    private static int getMin(Map<Integer, List<Task>> map) {
        if (map != null && !map.isEmpty()) {
            int min = Integer.MAX_VALUE;
            for (int value : map.keySet()) {
                if (value < min) {
                    min = value;
                }
            }
            return min;
        }
        return -1;
    }

    /**
     * Determine whether a task list contains a failed task.
     *
     * @param list task list to inspect
     * @return {@code true} if a failed task is present
     */
    private static boolean checkFailed(List<Task> list) {
        boolean all = false;
        for (Task task : list) {
            if (task.getCloudletStatus() == Cloudlet.FAILED) {
                all = true;
                break;
            }
        }
        return all;
    }

    /**
     * Recluster the tasks of a failed Job after bisecting them by DAG depth.
     *
     * @param jobList accumulated retry Job list
     * @param job failed Job
     * @param id identifier of the next allocatable Job
     * @return updated retry Job list
     */
    private static List<Job> VerticalReclustering(List<Job> jobList, Job job, int id) {
        Map<Integer, List<Task>> map = getDepthMap(job.getTaskList());

        /**
         * When there is only one DAG level, fall back to dynamic reclustering.
         */
        if (map.size() == 1) {

            jobList = DCReclustering(jobList, job, id, job.getTaskList());

            return jobList;
        }
        int min = getMin(map);
        int max = min + map.size() - 1;
        int mid = (min + max) / 2;
        List listUp = new ArrayList<>();
        List listDown = new ArrayList<>();
        for (int i = min; i < min + map.size(); i++) {
            List<Task> list = map.get(i);
            if (i <= mid) {
                listUp.addAll(list);
            } else {
                listDown.addAll(list);
            }
        }
        List<Job> newUpList = DCReclustering(new ArrayList(), job, id, listUp);
        id += newUpList.size();
        jobList.addAll(newUpList);
        jobList.addAll(DCReclustering(new ArrayList(), job, id, listDown));
        return jobList;
    }

    /**
     * Run dynamic reclustering separately for each DAG depth block that contains a failed task.
     *
     * @param jobList accumulated retry Job list
     * @param job failed Job
     * @param id identifier of the next allocatable Job
     * @return updated retry Job list
     */
    private static List BlockReclustering(List jobList, Job job, int id) {
        Map map = getDepthMap(job.getTaskList());
        if (map.size() == 1) {
            jobList = DRReclustering(jobList, job, id, job.getTaskList());
            return jobList;
        }
        // Process each depth block separately.
        int min = getMin(map);
        for (int i = min; i < min + map.size(); i++) {

            List list = (List) map.get(i);

            if (checkFailed(list)) {
                // A depth block that contains a failed task must be recovered independently.
                List newList = DRReclustering(new ArrayList(), job, id, list);
                id = newList.size() + id; // later retry Jobs continue with the identifiers not yet taken.
                jobList.addAll(newList);
            } else {
                // This depth block has no failed task, so no retry Job is needed.
            }
        }

        return jobList;
    }

    /**
     * Run dynamic reclustering with the clustering factor suggested by the monitor.
     *
     * @param jobList accumulated retry Job list
     * @param job failed Job
     * @param id identifier of the next allocatable Job
     * @param allTaskList candidate retry task list
     * @return updated retry Job list
     */
    private static List<Job> DCReclustering(List<Job> jobList, Job job, int id, List<Task> allTaskList) {

        Task firstTask = allTaskList.get(0);
        // The FailureRecord length is the estimated single-task runtime, not the length of the
        // whole Job. This historical formula combines task length, clustering overhead, and a
        // depth-dependent divisor.
        OverheadParameters overhead = Parameters.getOverheadParams();
        double clusteringDelay = overhead == null ? 0.0 : overhead.getClustDelay(job);
        double taskLength =
                (double) firstTask.getCloudletLength() / 1000 + clusteringDelay / getDividend(job.getDepth());
        FailureRecord record = new FailureRecord(
                taskLength, 0, job.getDepth(), allTaskList.size(), job.getVmId(), job.getCloudletId(), job.getUserId());
        record.delayLength = getCumulativeDelay(job.getDepth());
        int suggestedK = FailureMonitor.getClusteringFactor(record);

        // A zero from the estimator means that no finite recommendation exists; fall back to
        // one retry cluster that covers all candidate tasks.
        int clusterSize = suggestedK > 0 ? suggestedK : Math.max(1, allTaskList.size());
        jobList.addAll(partitionRetryTasks(job, id, allTaskList, clusterSize, false));
        return jobList;
    }

    /**
     * Create a single retry Job for the failed tasks only.
     *
     * @param jobList accumulated retry Job list
     * @param job failed Job
     * @param id identifier of the new retry Job
     * @return updated retry Job list
     */
    private static List<Job> SRReclustering(List<Job> jobList, Job job, int id) {
        List<Task> newTaskList = new ArrayList<>();
        long length = 0;
        for (Task task : job.getTaskList()) {
            if (task.getCloudletStatus() == Cloudlet.FAILED) {
                newTaskList.add(task);
                length += task.getCloudletLength();
            } else {
            }
        }
        jobList.add(createJob(id, job, length, newTaskList, true));
        return jobList;
    }

    /**
     * Return the depth-dependent divisor used by the legacy dynamic reclustering formula.
     *
     * <p>These constants cover only a few depths of the historical implementation and have no
     * experimental calibration basis; they are retained for compatibility with the old
     * behavior, not as a general platform model.</p>
     *
     * @param depth DAG depth
     * @return the corresponding historical divisor, or 1 for an unknown depth
     */
    private static int getDividend(int depth) {
        int dividend = 1;
        switch (depth) {
            case 1:
                dividend = 78;
                break;
            case 2:
                dividend = 229;
                break;
            case 5:
                dividend = 64;
                break;
            default:
                Log.printLine("Eroor");
                break;
        }
        return dividend;
    }

    /**
     * Sum the mean queue, workflow engine, and post-processing delays available for a DAG depth.
     * @param depth DAG depth
     * @return cumulative delay
     */
    private static double getCumulativeDelay(int depth) {
        double delay = 0.0;
        OverheadParameters overhead = Parameters.getOverheadParams();
        if (overhead == null) {
            return delay;
        }
        if (overhead.getQueueDelay() != null && overhead.getQueueDelay().containsKey(depth)) {
            delay += overhead.getQueueDelay().get(depth).getMLEMean();
        }
        if (overhead.getWEDDelay() != null && overhead.getWEDDelay().containsKey(depth)) {
            delay += overhead.getWEDDelay().get(depth).getMLEMean();
        }
        if (overhead.getPostDelay() != null && overhead.getPostDelay().containsKey(depth)) {
            delay += overhead.getPostDelay().get(depth).getMLEMean();
        }
        return delay;
    }

    /**
     * Return the first available overhead distribution prior for a depth, for the legacy
     * dynamic reclustering estimator.
     *
     * <p>This selection order is a historical compatibility rule; it does not express a
     * statistical merge of multiple overhead sources.</p>
     *
     * @param depth DAG depth
     * @return selected prior; 0 when no corresponding overhead exists
     */
    private static double getOverheadLikelihoodPrior(int depth) {
        double prior = 0.0;
        OverheadParameters overhead = Parameters.getOverheadParams();
        if (overhead == null) {
            return prior;
        }

        if (overhead.getQueueDelay() != null && overhead.getQueueDelay().containsKey(depth)) {
            prior = overhead.getQueueDelay().get(depth).getLikelihoodPrior();
        } else if (overhead.getWEDDelay() != null && overhead.getWEDDelay().containsKey(depth)) {
            prior = overhead.getWEDDelay().get(depth).getMLEMean();
        } else if (overhead.getPostDelay() != null && overhead.getPostDelay().containsKey(depth)) {
            prior = overhead.getPostDelay().get(depth).getMLEMean();
        }
        return prior;
    }

    /**
     * Estimate the cluster size from the failure arrival time and overhead parameters, then
     * retry the failed tasks only.
     *
     * @param jobList accumulated retry Job list
     * @param job failed Job
     * @param id identifier of the next allocatable Job
     * @param allTaskList candidate retry task list
     * @return updated retry Job list
     */
    private static List DRReclustering(List<Job> jobList, Job job, int id, List<Task> allTaskList) {
        Task firstTask = allTaskList.get(0);

        // The legacy DR path estimates the single-task runtime from the first task length only;
        // cluster overhead is deliberately not added here.
        double taskLength = (double) firstTask.getCloudletLength()
                / 1000; // + Parameters.getOverheadParams().getClustDelay(job) / getDividend(job.getDepth());

        // The historical implementation computed suggestedK through FailureMonitor; the direct
        // estimation path is kept now.

        double phi_ts = getOverheadLikelihoodPrior(job.getDepth());
        double delay = getCumulativeDelay(job.getDepth());
        int suggestedK; // the historical implementation could instead take the suggestion from FailureMonitor.

        double theta = FailureParameters.getGeneratorForFailureScope(job.getVmId(), job.getDepth())
                .getMLEMean();

        double phi_gamma = FailureParameters.getGeneratorForFailureScope(job.getVmId(), job.getDepth())
                .getLikelihoodPrior();
        suggestedK = ClusteringSizeEstimator.estimateK(taskLength, delay, theta, phi_gamma, phi_ts);

        Log.printLine("t=" + taskLength + " d=" + delay + " theta=" + theta + " k=" + suggestedK);
        // When the estimator has no finite suggestion, still retry the failed tasks only and
        // place them in a single retry cluster.
        int clusterSize = suggestedK > 0 ? suggestedK : Math.max(1, allTaskList.size());
        jobList.addAll(partitionRetryTasks(job, id, allTaskList, clusterSize, true));
        return jobList;
    }

    private static void updateDependencies(Job job, List<Job> jobList) {
        // Every retry Job references the same set of dependency edges; the whole child list must
        // not be rewritten inside the loop, otherwise later retry Jobs overwrite the dependency
        // relations established earlier.
        List<Task> parents = job.getParentList();
        List<Task> children = job.getChildList();
        for (Job rawJob : jobList) {
            rawJob.setChildList(children);
            rawJob.setParentList(parents);
        }
        for (Task parent : parents) {
            parent.getChildList().addAll(jobList);
        }
        for (Task childTask : children) {
            Job childJob = (Job) childTask;
            childJob.getParentList().addAll(jobList);
        }
    }
}
