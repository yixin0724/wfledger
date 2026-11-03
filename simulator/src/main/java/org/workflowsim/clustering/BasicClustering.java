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
package org.workflowsim.clustering;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.Parameters.FileType;

/**
 * Default no-clustering policy: every logical task maps to one independent job.
 *
 * <p>This implementation also converts logical task dependencies, file references, static
 * schedule times, and clustering overhead into a job-level representation; it is the common
 * base of the other clustering policies.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class BasicClustering implements ClusteringInterface {

    /**
     * Logical tasks to convert.
     */
    private List<Task> taskList;
    /**
     * Jobs produced by clustering.
     */
    private final List<Job> jobList;
    /**
     * Map from a logical task to the job it produced.
     */
    private final Map mTask2Job;
    /**
     * Files referenced by all jobs after clustering.
     */
    private final List<FileItem> allFileList;
    /**
     * Temporarily added virtual root task.
     */
    private Task root;
    /**
     * ID of the next generated job.
     */
    private int idIndex;

    /**
     * Returns the file references collected during clustering.
     *
     * @return file reference list
     */
    @Override
    public final List<FileItem> getTaskFiles() {
        return this.allFileList;
    }

    /**
     * Creates a no-clustering policy instance.
     */
    public BasicClustering() {
        this.jobList = new ArrayList<>();
        this.taskList = new ArrayList<>();
        this.mTask2Job = new HashMap<>();
        this.allFileList = new ArrayList<>();
        this.idIndex = 0;
        this.root = null;
    }

    /**
     * Sets the logical tasks to convert.
     *
     * @param list logical task list
     */
    @Override
    public final void setTaskList(List<Task> list) {
        this.taskList = list;
    }

    /**
     * Returns the generated job list.
     *
     * @return job list
     */
    @Override
    public final List<Job> getJobList() {
        return this.jobList;
    }

    /**
     * Returns the logical tasks to convert.
     *
     * @return logical task list
     */
    @Override
    public final List<Task> getTaskList() {
        return this.taskList;
    }

    /**
     * Returns the logical-task-to-job map.
     *
     * @return task-to-job map
     */
    public final Map getTask2Job() {
        return this.mTask2Job;
    }

    /**
     * Converts every logical task into an independent job and rebuilds the job-level
     * dependency relations.
     */
    @Override
    public void run() {
        getTask2Job().clear();
        for (Task task : getTaskList()) {
            List<Task> list = new ArrayList<>();
            list.add(task);
            Job job = addTasks2Job(list);
            job.setVmId(task.getVmId());
            if (!Double.isNaN(task.getStaticScheduleStartTime())) {
                job.setStaticScheduleStartTime(task.getStaticScheduleStartTime());
            }
            getTask2Job().put(task, job);
        }
        /**
         * Projects the logical-task parent/child edges onto job parent/child edges.
         */
        updateDependencies();
    }

    /**
     * Adds a single logical task as a new job.
     *
     * @param task logical task
     * @return newly created job
     */
    protected final Job addTasks2Job(Task task) {
        List<Task> tasks = new ArrayList<>();
        tasks.add(task);
        return addTasks2Job(tasks);
    }

    /**
     * Merges a group of logical tasks into one new job.
     *
     * @param taskList logical task list
     * @return newly created job; {@code null} when the input is empty
     */
    protected final Job addTasks2Job(List<Task> taskList) {
        if (taskList != null && !taskList.isEmpty()) {
            long length = 0L;

            int userId = 0;
            int priority = 0;
            int depth = 0;
            // CloudSim fixes the input/output file sizes at construction, so create the job
            // with a placeholder length first.
            Job job = new Job(idIndex, length /*, inputFileSize, outputFileSize*/);
            job.setClassType(ClassType.COMPUTE.value);
            for (Task task : taskList) {
                length = Math.addExact(length, task.getCloudletLength());

                userId = task.getUserId();
                priority = task.getPriority();
                depth = task.getDepth();
                List<FileItem> fileList = task.getFileList();
                job.getTaskList().add(task);

                getTask2Job().put(task, job);
                for (FileItem file : fileList) {
                    boolean hasFile = job.getFileList().contains(file);
                    if (!hasFile) {
                        job.getFileList().add(file);
                        if (file.getType() == FileType.INPUT) {
                            // Used by the later stage-in jobs.
                            if (!this.allFileList.contains(file)) {
                                this.allFileList.add(file);
                            }
                        } else if (file.getType() == FileType.OUTPUT) {
                            this.allFileList.add(file);
                        }
                    }
                }
                for (String fileName : task.getRequiredFiles()) {
                    if (!job.getRequiredFiles().contains(fileName)) {
                        job.getRequiredFiles().add(fileName);
                    }
                }
            }

            job.setCloudletLength(length);
            job.setUserId(userId);
            job.setDepth(depth);
            job.setPriority(priority);

            idIndex++;
            getJobList().add(job);
            return job;
        }

        return null;
    }

    /**
     * Adds the declared clustering delay to every generated job; the default delay is zero.
     */
    public void addClustDelay() {

        for (Job job : getJobList()) {
            double delay = Parameters.getOverheadParams().getClustDelay(job);
            delay *= 1000; // Matches the conversion ratio used by the historical workflow parsing stage
            long length = job.getCloudletLength();
            length += (long) delay;
            job.setCloudletLength(length);
        }
    }

    /**
     * Updates the job-level parent/child relations from the logical task dependencies.
     */
    protected final void updateDependencies() {
        for (Task task : getTaskList()) {
            Job job = (Job) getTask2Job().get(task);
            for (Task parentTask : task.getParentList()) {
                Job parentJob = (Job) getTask2Job().get(parentTask);
                if (!job.getParentList().contains(parentJob) && parentJob != job) { // no duplicate edges or self-loops
                    job.addParent(parentJob);
                }
            }
            for (Task childTask : task.getChildList()) {
                Job childJob = (Job) getTask2Job().get(childTask);
                if (!job.getChildList().contains(childJob) && childJob != job) { // no duplicate edges or self-loops
                    job.addChild(childJob);
                }
            }
        }
        getTask2Job().clear();
        getTaskList().clear();
    }
    /*
     * Adds a virtual root task so that all original root tasks become its children.
     * Callers must invoke {@link #clean()} afterwards to remove the temporary root node.
     */

    public Task addRoot() {

        if (root == null) {
            // Use a temporary ID that does not collide with the current task list.
            root = new Task(taskList.size() + 1, 0 /*,0,0*/);
            for (Task node : taskList) {
                if (node.getParentList().isEmpty()) {
                    node.addParent(root);
                    root.addChild(node);
                }
            }
            taskList.add(root);
        }
        return root;
    }

    /**
     * Removes the previously added virtual root task and restores the original root
     * relations.
     */
    public void clean() {
        if (root != null) {
            for (int i = 0; i < root.getChildList().size(); i++) {
                Task node = (Task) root.getChildList().get(i);
                node.getParentList().remove(root);
                root.getChildList().remove(node);
                i--;
            }
            taskList.remove(root);
        }
    }
}
