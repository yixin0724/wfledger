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

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.clustering.BasicClustering;
import org.workflowsim.clustering.BlockClustering;
import org.workflowsim.clustering.HorizontalClustering;
import org.workflowsim.clustering.VerticalClustering;
import org.workflowsim.clustering.balancing.BalancedClustering;
import org.workflowsim.experiment.SimulationEventRecorder;
import org.workflowsim.experiment.SimulationEventType;
import org.workflowsim.scheduling.StaticSchedulePlan;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConstants;

/**
 * Optional task clustering and unified stage-in construction entity.
 *
 * <p>The entity merges the task DAG into {@link Job} instances and then creates a stage-in job
 * that collects the external input files of the workflow and hands it to {@link WorkflowEngine}.
 * stage-in is a simplified data-preparation model: it does not simulate chunked transfer, link
 * contention, cache coherence, or a real storage protocol.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 *
 */
public final class ClusteringEngine extends SimEntity {

    /** Original task list to be clustered. */
    protected List<Task> taskList;
    /** Job list to be executed after clustering. */
    protected List<Job> jobList;
    /** Submitted task list. */
    protected List<? extends Task> taskSubmittedList;
    /** Received task list. */
    protected List<? extends Task> taskReceivedList;
    /** Number of submitted tasks. */
    protected int cloudletsSubmitted;
    /** Currently selected concrete clustering implementation. */
    protected BasicClustering engine;
    /** CloudSim entity id of the downstream workflow engine. */
    private final int workflowEngineId;
    /** Downstream workflow engine. */
    private final WorkflowEngine workflowEngine;

    private SimulationEventRecorder eventRecorder = SimulationEventRecorder.disabled();

    /**
     * Creates the clustering entity and its downstream workflow engine.
     *
     * @param name CloudSim entity name
     * @param schedulers number of downstream runtime schedulers
     * @throws Exception when the CloudSim entities cannot be created
     */
    public ClusteringEngine(String name, int schedulers) throws Exception {
        super(name);
        setJobList(new ArrayList<>());
        setTaskList(new ArrayList<>());
        setTaskSubmittedList(new ArrayList<>());
        setTaskReceivedList(new ArrayList<>());

        cloudletsSubmitted = 0;
        this.workflowEngine = new WorkflowEngine(name + "_Engine_0", schedulers);
        this.workflowEngineId = this.workflowEngine.getId();
    }

    /** @return CloudSim entity id of the downstream workflow engine */
    public int getWorkflowEngineId() {
        return this.workflowEngineId;
    }

    /** @return downstream workflow engine */
    public WorkflowEngine getWorkflowEngine() {
        return this.workflowEngine;
    }

    /**
     * Installs the run-private event recorder and propagates it to the workflow engine.
     *
     * @param value event recorder of this run
     */
    public void setEventRecorder(SimulationEventRecorder value) {
        if (value == null) {
            throw new IllegalArgumentException("Event recorder cannot be null");
        }
        this.eventRecorder = value;
        this.workflowEngine.setEventRecorder(value);
    }

    /**
     * Appends a batch of tasks to be clustered.
     *
     * @param list task list to be clustered
     */
    public void submitTaskList(List<Task> list) {
        getTaskList().addAll(list);
    }

    /**
     * Selects the clustering implementation from the current configuration and generates the jobs.
     *
     * <p>When clustering is not enabled, {@link BasicClustering} is used and the original task
     * granularity is preserved.</p>
     */
    protected void processClustering() {

        // Both the clustering method and the granularity come from this session's explicit configuration.
        ClusteringParameters params = Parameters.getClusteringParameters();
        switch (params.getClusteringMethod()) {
                // Horizontal clustering merges tasks grouped by the same DAG level.
            case HORIZONTAL:
                // Construct by target job count per level first.
                if (params.getClustersNum() != 0) {
                    this.engine = new HorizontalClustering(params.getClustersNum(), 0);
                } else if (params.getClustersSize() != 0) { // Otherwise construct by target task count per job.
                    this.engine = new HorizontalClustering(0, params.getClustersSize());
                }
                break;
                // Vertical clustering merges along DAG edges; the current entry starts at the first level.
            case VERTICAL:
                int depth = 1;
                this.engine = new VerticalClustering(depth);
                break;
                // Block clustering is interpreted by the implementation from the count and size parameters.
            case BLOCK:
                this.engine = new BlockClustering(params.getClustersNum(), params.getClustersSize());
                break;
                // Balanced clustering uses the task impact factor computed in the planning phase.
            case BALANCED:
                this.engine = new BalancedClustering(params.getClustersNum());
                break;
                // By default tasks are not merged, preserving one-task-per-job execution granularity.
            default:
                this.engine = new BasicClustering();
                break;
        }
        engine.setTaskList(getTaskList());
        engine.run();
        setJobList(engine.getJobList());
        eventRecorder.record(
                SimulationEventType.JOBS_CLUSTERED,
                CloudSim.clock(),
                null,
                SimulationEventRecorder.attributes(
                        "sourceTaskCount",
                        getTaskList().size(),
                        "jobCount",
                        getJobList().size(),
                        "clusteringMethod",
                        params.getClusteringMethod().name()));
    }

    /**
     * Creates one unified stage-in job for all real external input files.
     *
     * <p>This historical model consolidates all external inputs into a minimum-length predecessor
     * job and makes every root job depend on it. It is not an actual data-movement scheduler;
     * real link concurrency, transfer overlap, and storage capacity are not modeled here.</p>
     */
    protected void processDatastaging() {

        // The clustering engine has collected every associated file of the current workflow.
        List<FileItem> list = this.engine.getTaskFiles();
        /*
         * The historical CloudSim path requires the stage-in Cloudlet to have at least the
         * specified minimum length; the current job count is used as the id to continue the
         * existing job-numbering convention.
         */
        Job job = new Job(getJobList().size(), SimulationConstants.STAGE_IN_JOB_LENGTH_MI);

        // The unified stage-in job owns all external inputs so that the existing execution path
        // computes transfer delays.
        List<FileItem> fileList = new ArrayList<>();
        for (FileItem file : list) {
            // Register every real external input file exactly once.
            if (file.isRealInputFile(list)) {
                ReplicaCatalog.addFileToStorage(file.getName(), Parameters.SOURCE);
                fileList.add(file);
            }
        }
        job.setFileList(fileList);
        job.setClassType(ClassType.STAGE_IN.value);

        // stage-in must run before the root jobs of the workflow.
        job.setDepth(0);
        job.setPriority(0);

        // Historical semantics: with multiple schedulers, stage-in always goes to the first scheduler.
        job.setUserId(getWorkflowEngine().getSchedulerId(0));

        // All root jobs depend on the stage-in job, forming a unified data-preparation precondition.
        for (Job cJob : getJobList()) {
            // A root job has no parent and is a direct successor of the stage-in job.
            if (cJob.getParentList().isEmpty()) {
                cJob.addParent(job);
                job.addChild(cJob);
            }
        }
        getJobList().add(job);
        double inputBytes = 0.0;
        for (FileItem file : fileList) {
            double bytes = file.getSize();
            if (!Double.isFinite(bytes) || bytes < 0.0 || !Double.isFinite(inputBytes + bytes)) {
                throw new IllegalArgumentException("External input byte demand must be finite and non-negative");
            }
            inputBytes += bytes;
        }
        eventRecorder.record(
                SimulationEventType.STAGE_IN_JOB_CREATED,
                CloudSim.clock(),
                job,
                SimulationEventRecorder.attributes("inputFileCount", fileList.size(), "inputBytes", inputBytes));
    }

    /**
     * Handles CloudSim events in the clustering lifecycle.
     *
     * @param ev the received event
     */
    @Override
    public void processEvent(SimEvent ev) {

        switch (ev.getTag()) {
            case WorkflowSimTags.START_SIMULATION:
                break;
            case WorkflowSimTags.JOB_SUBMIT:
                List list = (List) ev.getData();
                setTaskList(list);
                // Without clustering configured, processClustering keeps task granularity; it never merges.
                processClustering();
                // Append the unified stage-in job to express the simplified workflow input-preparation stage.
                processDatastaging();
                getWorkflowEngine().setStaticSchedulePlan(StaticSchedulePlan.fromJobs(getJobList()));
                sendNow(this.workflowEngineId, WorkflowSimTags.JOB_SUBMIT, getJobList());
                break;
            case CloudSimTags.END_OF_SIMULATION:
                shutdownEntity();
                break;
            default:
                processOtherEvent(ev);
                break;
        }
    }

    /**
     * Handles an unknown event and logs an error.
     *
     * @param ev event not recognized by this clustering entity
     */
    protected void processOtherEvent(SimEvent ev) {
        if (ev == null) {
            Log.printLine(getName() + ".processOtherEvent(): " + "Error - an event is null.");
            return;
        }

        Log.printLine(getName() + ".processOtherEvent(): " + "Error - event unknown by this DatacenterBroker.");
    }

    /** Reserved hook for end-of-simulation notification; the current historical implementation sends no extra event. */
    protected void finishExecution() {
        // sendNow(getId(), CloudSimTags.END_OF_SIMULATION);
    }

    /** Shuts down the clustering entity. */
    @Override
    public void shutdownEntity() {
        Log.printLine(getName() + " is shutting down...");
    }

    /** Starts the clustering entity and schedules the initial event. */
    @Override
    public void startEntity() {
        Log.printLine(getName() + " is starting...");
        schedule(getId(), 0, WorkflowSimTags.START_SIMULATION);
    }

    /** @return current task list to be clustered */
    @SuppressWarnings("unchecked")
    public List<Task> getTaskList() {
        return (List<Task>) taskList;
    }

    /** @return current job list after clustering */
    public List<Job> getJobList() {
        return jobList;
    }

    /**
     * Replaces the task list to be clustered.
     *
     * @param taskList new task list
     */
    protected void setTaskList(List<Task> taskList) {
        this.taskList = taskList;
    }

    /**
     * Replaces the job list after clustering.
     *
     * @param jobList new job list
     */
    protected void setJobList(List<Job> jobList) {
        this.jobList = jobList;
    }

    /** @return submitted task list */
    @SuppressWarnings("unchecked")
    public List<Task> getTaskSubmittedList() {
        return (List<Task>) taskSubmittedList;
    }

    /**
     * Replaces the submitted task list.
     *
     * @param taskSubmittedList new submitted task list
     */
    protected void setTaskSubmittedList(List<Task> taskSubmittedList) {
        this.taskSubmittedList = taskSubmittedList;
    }

    /** @return received task list */
    @SuppressWarnings("unchecked")
    public List<Task> getTaskReceivedList() {
        return (List<Task>) taskReceivedList;
    }

    /**
     * Replaces the received task list.
     *
     * @param taskReceivedList new received task list
     */
    protected void setTaskReceivedList(List<Task> taskReceivedList) {
        this.taskReceivedList = taskReceivedList;
    }
}
