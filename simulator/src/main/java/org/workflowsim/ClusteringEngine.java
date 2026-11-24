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
import org.workflowsim.experiment.SimulationEventRecorder;
import org.workflowsim.experiment.SimulationEventType;
import org.workflowsim.scheduling.StaticSchedulePlan;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConstants;

public final class ClusteringEngine extends SimEntity {

    protected List<Task> taskList;

    protected List<Job> jobList;

    protected List<? extends Task> taskSubmittedList;

    protected List<? extends Task> taskReceivedList;

    protected int cloudletsSubmitted;

    protected BasicClustering engine;

    private final int workflowEngineId;

    private final WorkflowEngine workflowEngine;

    private SimulationEventRecorder eventRecorder = SimulationEventRecorder.disabled();

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

    public int getWorkflowEngineId() {
        return this.workflowEngineId;
    }

    public WorkflowEngine getWorkflowEngine() {
        return this.workflowEngine;
    }

    public void setEventRecorder(SimulationEventRecorder value) {
        if (value == null) {
            throw new IllegalArgumentException("Event recorder cannot be null");
        }
        this.eventRecorder = value;
        this.workflowEngine.setEventRecorder(value);
    }

    public void submitTaskList(List<Task> list) {
        getTaskList().addAll(list);
    }

    protected void processClustering() {

        ClusteringParameters params = Parameters.getClusteringParameters();
        this.engine = new BasicClustering();
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

    protected void processDatastaging() {

        List<FileItem> list = this.engine.getTaskFiles();

        Job job = new Job(getJobList().size(), SimulationConstants.STAGE_IN_JOB_LENGTH_MI);

        List<FileItem> fileList = new ArrayList<>();
        for (FileItem file : list) {

            if (file.isRealInputFile(list)) {
                ReplicaCatalog.addFileToStorage(file.getName(), Parameters.SOURCE);
                fileList.add(file);
            }
        }
        job.setFileList(fileList);
        job.setClassType(ClassType.STAGE_IN.value);

        job.setDepth(0);
        job.setPriority(0);

        job.setUserId(getWorkflowEngine().getSchedulerId(0));

        for (Job cJob : getJobList()) {

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

    @Override
    public void processEvent(SimEvent ev) {

        switch (ev.getTag()) {
            case WorkflowSimTags.START_SIMULATION:
                break;
            case WorkflowSimTags.JOB_SUBMIT:
                List list = (List) ev.getData();
                setTaskList(list);

                processClustering();

                processDatastaging();
                StaticSchedulePlan staticPlan = StaticSchedulePlan.fromJobs(getJobList());
                if (Parameters.getPlanningAlgorithm() == Parameters.PlanningAlgorithm.CAWOSCHED_SLACK
                        || Parameters.getPlanningAlgorithm() == Parameters.PlanningAlgorithm.CAWOSCHED_PRESSURE) {
                    staticPlan = staticPlan.withStartTimeEnforcement();
                }
                getWorkflowEngine().setStaticSchedulePlan(staticPlan);
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

    protected void processOtherEvent(SimEvent ev) {
        if (ev == null) {
            Log.printLine(getName() + ".processOtherEvent(): " + "Error - an event is null.");
            return;
        }

        Log.printLine(getName() + ".processOtherEvent(): " + "Error - event unknown by this DatacenterBroker.");
    }

    protected void finishExecution() {}

    @Override
    public void shutdownEntity() {
        Log.printLine(getName() + " is shutting down...");
    }

    @Override
    public void startEntity() {
        Log.printLine(getName() + " is starting...");
        schedule(getId(), 0, WorkflowSimTags.START_SIMULATION);
    }

    @SuppressWarnings("unchecked")
    public List<Task> getTaskList() {
        return (List<Task>) taskList;
    }

    public List<Job> getJobList() {
        return jobList;
    }

    protected void setTaskList(List<Task> taskList) {
        this.taskList = taskList;
    }

    protected void setJobList(List<Job> jobList) {
        this.jobList = jobList;
    }

    @SuppressWarnings("unchecked")
    public List<Task> getTaskSubmittedList() {
        return (List<Task>) taskSubmittedList;
    }

    protected void setTaskSubmittedList(List<Task> taskSubmittedList) {
        this.taskSubmittedList = taskSubmittedList;
    }

    @SuppressWarnings("unchecked")
    public List<Task> getTaskReceivedList() {
        return (List<Task>) taskReceivedList;
    }

    protected void setTaskReceivedList(List<Task> taskReceivedList) {
        this.taskReceivedList = taskReceivedList;
    }
}
