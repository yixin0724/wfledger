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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.data.NetworkFlowBinding;
import org.workflowsim.data.TransferContentionEngine;
import org.workflowsim.experiment.SimulationEventRecorder;
import org.workflowsim.experiment.SimulationEventType;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.failure.RetryLimitExceededException;
import org.workflowsim.reclustering.ReclusteringEngine;
import org.workflowsim.scheduling.StaticSchedulePlan;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.ReplicaCatalog;

public final class WorkflowEngine extends SimEntity {

    protected List<? extends Cloudlet> jobsList;

    protected List<? extends Cloudlet> jobsSubmittedList;

    protected List<? extends Cloudlet> jobsReceivedList;

    private final Set<Integer> receivedSuccessfulJobIds = new HashSet<Integer>();

    private final Map<Integer, Integer> retryOfFailedJobIds = new HashMap<Integer, Integer>();

    protected int jobsSubmitted;

    protected List<? extends Vm> vmList;

    private List<Integer> schedulerId;

    private List<WorkflowScheduler> scheduler;

    private SimulationEventRecorder eventRecorder = SimulationEventRecorder.disabled();

    private int retryJobsCreated;

    private int boundDatacenterId = -1;

    private final Map<Long, Job> contentionTransferJobs = new LinkedHashMap<Long, Job>();

    private final Map<Integer, Set<Long>> contentionJobPendingTransfers = new LinkedHashMap<Integer, Set<Long>>();

    private long nextContentionTransferId = 1L;

    private org.workflowsim.data.v2.CoherentDataflowRuntime coherentDataflow;
    private org.workflowsim.data.v2.DataflowAssignmentConfig dataflowAssignment =
            org.workflowsim.data.v2.DataflowAssignmentConfig.off();
    private org.workflowsim.data.v2.DataflowVmAssigner dataflowVmAssigner;

    public void setDataflowAssignmentConfig(org.workflowsim.data.v2.DataflowAssignmentConfig value) {
        if (value == null || dataflowVmAssigner != null) {
            throw new IllegalArgumentException("Assignment configuration must precede binding");
        }
        dataflowAssignment = value;
    }

    private final Map<Integer, Job> coherentPendingJobs = new LinkedHashMap<Integer, Job>();
    private Double coherentCheckTime;
    private long coherentCheckGeneration;
    private boolean coherentEndRequested;

    public void setCoherentDataflowRuntime(org.workflowsim.data.v2.CoherentDataflowRuntime value) {
        if (value == null || coherentDataflow != null || getSchedulers().size() != 1) {
            throw new IllegalArgumentException("Coherent V2 requires one run-owned coordinator and scheduler");
        }
        coherentDataflow = value;
    }

    private org.workflowsim.data.v2.CoherentDataflowRuntime.Preparation prepareCoherentInput(Job job) {
        if (job.getClassType() != ClassType.COMPUTE.value || boundDatacenterId < 0) {
            return null;
        }
        SimEntity entity = CloudSim.getEntity(boundDatacenterId);
        if (!(entity instanceof WorkflowDatacenter)) {
            return null;
        }
        WorkflowDatacenter datacenter = (WorkflowDatacenter) entity;
        if (!datacenter.getDataMovementModel().usesCoherentDataflowRuntime()) {
            return null;
        }
        if (coherentDataflow == null) {
            throw new IllegalStateException("Coherent V2 has no dedicated workflow run context");
        }
        datacenter.initializeCoherentFabric();
        if (dataflowAssignment.isEnabled()) {
            if (dataflowVmAssigner == null) {
                dataflowVmAssigner = new org.workflowsim.data.v2.DataflowVmAssigner(
                        coherentDataflow, datacenter.actualDataflowVmOptions());
            }
            org.workflowsim.data.v2.DataflowVmAssigner.Prepared prepared =
                    dataflowVmAssigner.prepare(job, CloudSim.clock());
            eventRecorder.record(
                    SimulationEventType.DATAFLOW_VM_ASSIGNED,
                    CloudSim.clock(),
                    job,
                    prepared.getDecision().getAttributes());
            return prepared.getPreparation();
        }
        List<Integer> tasks = new ArrayList<Integer>();
        for (Task task : job.getTaskList()) {
            tasks.add(task.getCloudletId());
        }
        return coherentDataflow.requestJob(job.getCloudletId(), tasks, job.getVmId(), CloudSim.clock());
    }

    private void appendCoherentReady(Map<Integer, List> allocation) {
        if (coherentDataflow == null) {
            return;
        }
        for (Integer id : coherentDataflow.drainReadyJobIds()) {
            Job ready = coherentPendingJobs.remove(id);
            if (ready == null) {
                throw new IllegalStateException("Data-ready V2 Job has no engine waiter: " + id);
            }
            if (coherentDataflow.isStorageVersion()) {
                recordStorageInputPreparation(ready);
            }
            allocation.get(ready.getUserId()).add(ready);
        }
    }

    private void recordStorageInputPreparation(Job job) {
        org.workflowsim.data.v2.CoherentDataflowRuntime.Preparation preparation =
                coherentDataflow.getPreparation(job.getCloudletId());
        eventRecorder.record(
                SimulationEventType.DATA_STAGE_IN_MODELED,
                CloudSim.clock(),
                job,
                SimulationEventRecorder.attributes(
                        "modeledTransferSeconds",
                        preparation.getIsolatedSeconds(),
                        "requiredFileBytes",
                        preparation.getRequiredBytes(),
                        "modeledTransferFileCount",
                        preparation.getReferenceCount(),
                        "dataMovementModel",
                        ((WorkflowDatacenter) CloudSim.getEntity(boundDatacenterId))
                                .getDataMovementModel()
                                .getKind()
                                .name(),
                        "newFileCopies",
                        preparation.getNewCopies(),
                        "joinedFileCopies",
                        preparation.getJoinedCopies(),
                        "transferUnit",
                        "LOGICAL_FILE_STORAGE_V3",
                        "observedInputPreparationSeconds",
                        coherentDataflow.getObservedInputPreparationSeconds(job.getCloudletId())));
    }

    private void scheduleCoherentCheck() {
        if (coherentDataflow == null || !coherentDataflow.isFabricBound()) {
            return;
        }
        Double next = coherentDataflow.getNextCompletionTime();
        if (next == null) {
            return;
        }
        double now = CloudSim.clock(),
                delay = Math.max(next - now, CloudSim.getMinTimeBetweenEvents()),
                due = now + delay;
        if (!Double.isFinite(due) || due <= now) {
            throw new IllegalStateException("V2 completion observation cannot advance Kernel clock");
        }
        if (coherentCheckTime != null && coherentCheckTime <= due) {
            return;
        }
        long generation = Math.addExact(coherentCheckGeneration, 1);
        schedule(getId(), delay, WorkflowSimTags.COHERENT_DATAFLOW_CHECK, Long.valueOf(generation));
        coherentCheckGeneration = generation;
        coherentCheckTime = due;
    }

    private void processCoherentDataflowCheck(SimEvent event) {
        if (coherentDataflow == null || !coherentDataflow.isFabricBound()) {
            throw new IllegalStateException("V2 check has no initialized run context");
        }
        if (event.getData() instanceof Long) {
            if (((Long) event.getData()).longValue() != coherentCheckGeneration) {
                return;
            }
            coherentCheckTime = null;
        }
        coherentDataflow.advance(CloudSim.clock());
        Map<Integer, List> allocation = new LinkedHashMap<Integer, List>();
        for (int scheduler : getSchedulerIds()) {
            allocation.put(scheduler, new ArrayList<Job>());
        }
        appendCoherentReady(allocation);
        for (int scheduler : getSchedulerIds()) {
            dispatchReadyBatch(scheduler, allocation.get(scheduler));
        }
        scheduleCoherentCheck();
        finishCoherentIfIdle();
    }

    private void finishCoherentIfIdle() {
        if (coherentDataflow == null
                || coherentEndRequested
                || !getJobsList().isEmpty()
                || jobsSubmitted != 0
                || !coherentDataflow.isQuiescent()) {
            return;
        }
        coherentEndRequested = true;
        for (int scheduler : getSchedulerIds()) {
            sendNow(scheduler, CloudSimTags.END_OF_SIMULATION, null);
        }
    }

    private List<Double> workflowArrivalSeconds;

    private Map<Integer, Integer> taskWorkflowIndices = Collections.emptyMap();

    private Double pendingArrivalScanSecond;

    private final Set<Integer> announcedWorkflowIndices = new HashSet<>();

    public WorkflowEngine(String name) throws Exception {
        this(name, 1);
    }

    public WorkflowEngine(String name, int schedulers) throws Exception {
        super(name);

        setJobsList(new ArrayList<>());
        setJobsSubmittedList(new ArrayList<>());
        setJobsReceivedList(new ArrayList<>());

        jobsSubmitted = 0;
        retryJobsCreated = 0;

        setSchedulers(new ArrayList<>());
        setSchedulerIds(new ArrayList<>());

        for (int i = 0; i < schedulers; i++) {
            WorkflowScheduler wfs = new WorkflowScheduler(name + "_Scheduler_" + i);
            getSchedulers().add(wfs);
            getSchedulerIds().add(wfs.getId());
            wfs.setWorkflowEngineId(this.getId());
        }
    }

    public void submitVmList(List<? extends Vm> list, int schedulerId) {
        getScheduler(schedulerId).submitVmList(list);
    }

    public void submitVmList(List<? extends Vm> list) {

        getScheduler(0).submitVmList(list);
        setVmList(list);
    }

    public List<? extends Vm> getAllVmList() {
        if (this.vmList != null && !this.vmList.isEmpty()) {
            return this.vmList;
        } else {
            List list = new ArrayList();
            for (int i = 0; i < getSchedulers().size(); i++) {
                list.addAll(getScheduler(i).getVmList());
            }
            return list;
        }
    }

    public void setEventRecorder(SimulationEventRecorder value) {
        if (value == null) {
            throw new IllegalArgumentException("Event recorder cannot be null");
        }
        this.eventRecorder = value;
        for (WorkflowScheduler item : getSchedulers()) {
            item.setEventRecorder(value);
        }
    }

    public void setStaticSchedulePlan(StaticSchedulePlan value) {
        if (value == null) {
            throw new IllegalArgumentException("Static schedule plan cannot be null");
        }
        for (WorkflowScheduler item : getSchedulers()) {
            item.setStaticSchedulePlan(value);
        }
    }

    public void submitCloudletList(List<? extends Cloudlet> list) {
        getJobsList().addAll(list);
    }

    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case CloudSimTags.RESOURCE_CHARACTERISTICS_REQUEST:
                processResourceCharacteristicsRequest(ev);
                break;

            case CloudSimTags.CLOUDLET_SUBMIT:
                submitJobs();
                break;
            case CloudSimTags.CLOUDLET_RETURN:
                processJobReturn(ev);
                break;
            case CloudSimTags.END_OF_SIMULATION:
                shutdownEntity();
                break;
            case WorkflowSimTags.JOB_SUBMIT:
                processJobSubmit(ev);
                break;
            case WorkflowSimTags.JOB_STAGE_IN_COMPLETE:
                processPreExecutionStageInComplete(ev);
                break;
            case WorkflowSimTags.TRANSFER_CONTENTION_CHECK:
                processTransferContentionCheck(ev);
                break;
            case WorkflowSimTags.COHERENT_DATAFLOW_CHECK:
                processCoherentDataflowCheck(ev);
                break;
            case WorkflowSimTags.WORKFLOW_ARRIVAL_SCAN:
                pendingArrivalScanSecond = null;
                submitJobs();
                break;
            default:
                processOtherEvent(ev);
                break;
        }
    }

    protected void processResourceCharacteristicsRequest(SimEvent ev) {
        for (int i = 0; i < getSchedulerIds().size(); i++) {
            schedule(getSchedulerId(i), 0, CloudSimTags.RESOURCE_CHARACTERISTICS_REQUEST);
        }
    }

    public void bindSchedulerDatacenter(int datacenterId, int schedulerId) {
        getScheduler(schedulerId).bindSchedulerDatacenter(datacenterId);

        this.boundDatacenterId = datacenterId;
    }

    public void bindSchedulerDatacenter(int datacenterId) {
        bindSchedulerDatacenter(datacenterId, 0);
    }

    protected void processJobSubmit(SimEvent ev) {
        List<? extends Cloudlet> list = (List) ev.getData();
        setJobsList(list);

        submitJobs();
    }

    public void setWorkflowArrivals(List<Double> arrivalSeconds, Map<Integer, Integer> taskWorkflowIndices) {
        this.workflowArrivalSeconds = arrivalSeconds == null ? null : new ArrayList<>(arrivalSeconds);
        this.taskWorkflowIndices = taskWorkflowIndices == null
                ? Collections.<Integer, Integer>emptyMap()
                : new HashMap<>(taskWorkflowIndices);
    }

    private double arrivalSecondFor(Job job) {
        if (workflowArrivalSeconds == null || taskWorkflowIndices.isEmpty()) {
            return 0.0;
        }
        if (job.getClassType() == ClassType.STAGE_IN.value) {
            return 0.0;
        }
        Integer workflowIndex = workflowIndexOf(job);
        if (workflowIndex == null) {
            return 0.0;
        }
        if (workflowIndex.intValue() < 0 || workflowIndex.intValue() >= workflowArrivalSeconds.size()) {
            throw new IllegalStateException("Workflow index " + workflowIndex + " of Job " + job.getCloudletId()
                    + " is outside the configured arrival seconds");
        }
        Double arrivalSecond = workflowArrivalSeconds.get(workflowIndex.intValue());
        return arrivalSecond == null ? 0.0 : arrivalSecond.doubleValue();
    }

    private Integer workflowIndexOf(Job job) {
        List<Task> tasks = job.getTaskList();
        if (tasks == null || tasks.isEmpty()) {
            if (job.getClassType() == ClassType.COMPUTE.value) {
                throw new IllegalStateException(
                        "Job " + job.getCloudletId() + " carries no task for workflow arrival attribution");
            }
            return null;
        }
        Integer workflowIndex = null;
        for (Task task : tasks) {
            Integer index = taskWorkflowIndices.get(task.getCloudletId());
            if (index == null) {
                throw new IllegalStateException("Task " + task.getCloudletId() + " of Job " + job.getCloudletId()
                        + " has no workflow arrival attribution");
            }
            if (workflowIndex == null) {
                workflowIndex = index;
            } else if (!workflowIndex.equals(index)) {
                throw new IllegalStateException("Job " + job.getCloudletId()
                        + " spans multiple workflow inputs; arrival attribution is ambiguous");
            }
        }
        return workflowIndex;
    }

    private boolean arrivalEvidenceEnabled() {
        if (workflowArrivalSeconds == null) {
            return false;
        }
        if (workflowArrivalSeconds.size() > 1) {
            return true;
        }
        for (Double arrivalSecond : workflowArrivalSeconds) {
            if (arrivalSecond != null && arrivalSecond > 0.0) {
                return true;
            }
        }
        return false;
    }

    private void announceWorkflowArrivalFor(Job job) {
        if (!arrivalEvidenceEnabled()) {
            return;
        }

        if (job.getClassType() == ClassType.STAGE_IN.value) {
            return;
        }
        Integer workflowIndex = workflowIndexOf(job);
        if (workflowIndex == null || announcedWorkflowIndices.contains(workflowIndex)) {
            return;
        }
        announcedWorkflowIndices.add(workflowIndex);
        double arrivalSecond = workflowArrivalSeconds.get(workflowIndex);
        eventRecorder.record(
                SimulationEventType.WORKFLOW_ARRIVED,
                CloudSim.clock(),
                job,
                SimulationEventRecorder.attributes("workflowIndex", workflowIndex, "arrivalSecond", arrivalSecond));
    }

    private void scheduleArrivalRescan(double arrivalSecond) {
        if (pendingArrivalScanSecond == null || arrivalSecond < pendingArrivalScanSecond) {
            schedule(getId(), arrivalSecond - CloudSim.clock(), WorkflowSimTags.WORKFLOW_ARRIVAL_SCAN, null);
            pendingArrivalScanSecond = arrivalSecond;
        }
    }

    private double preExecutionStageInHoldSeconds(Job job) {
        if (boundDatacenterId < 0) {
            return -1.0;
        }
        SimEntity entity = CloudSim.getEntity(boundDatacenterId);
        if (!(entity instanceof WorkflowDatacenter)) {
            return -1.0;
        }
        WorkflowDatacenter datacenter = (WorkflowDatacenter) entity;
        if (!datacenter.getDataMovementModel().isPreExecutionTransferDelayV1()) {
            return -1.0;
        }
        if (job.getClassType() != ClassType.COMPUTE.value) {
            return -1.0;
        }

        if (job.getVmId() < 0) {
            throw new IllegalStateException(
                    "Pre-execution transfer requires a static VM mapping for Job " + job.getCloudletId());
        }

        double now = CloudSim.clock();
        PreExecutionTransfer transfer = aggregatePreExecutionTransfer(job, datacenter, now);
        double totalSeconds = transfer.totalSeconds;
        double requiredBytes = transfer.requiredBytes;
        int fileCount = transfer.fileCount;
        double maxArrival = transfer.maxArrival;
        double delay = maxArrival - now;

        eventRecorder.record(
                SimulationEventType.DATA_STAGE_IN_MODELED,
                now,
                job,
                SimulationEventRecorder.attributes(
                        "modeledTransferSeconds",
                        totalSeconds,
                        "requiredFileBytes",
                        requiredBytes,
                        "modeledTransferFileCount",
                        fileCount,
                        "dataMovementModel",
                        datacenter.getDataMovementModel().getKind().name(),
                        "dataArrivalDelaySeconds",
                        Math.max(delay, 0.0)));
        if (delay <= 0.0) {

            try {
                datacenter.registerStageInReplicasForComputeJob(job);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "WorkflowEngine could not register pre-execution stage-in " + "replicas for Job "
                                + job.getCloudletId(),
                        e);
            }
            return 0.0;
        }

        return Math.max(delay, CloudSim.getMinTimeBetweenEvents());
    }

    private PreExecutionTransfer aggregatePreExecutionTransfer(Job job, WorkflowDatacenter datacenter, double now) {
        List<FileItem> fileList = job.getFileList();
        PreExecutionTransfer transfer = new PreExecutionTransfer();
        transfer.maxArrival = now;
        Set<String> attributedNames = new HashSet<String>();
        try {

            for (Object parentObj : job.getParentList()) {
                Job parent = (Job) parentObj;
                Set<String> produced = new HashSet<String>();
                for (FileItem producedFile : parent.getFileList()) {
                    if (producedFile.getType() == Parameters.FileType.OUTPUT) {
                        produced.add(producedFile.getName());
                    }
                }
                List<FileItem> fromParent = new ArrayList<FileItem>();
                for (FileItem file : fileList) {
                    if (file.isRealInputFile(fileList) && produced.contains(file.getName())) {
                        fromParent.add(file);
                        attributedNames.add(file.getName());
                    }
                }
                if (fromParent.isEmpty()) {
                    continue;
                }
                double seconds = datacenter.estimateTransferSecondsForFiles(fromParent, job);
                validateTransferEstimate(sumTransferableBytes(fromParent, job, datacenter), seconds);
                transfer.totalSeconds =
                        addFiniteTransferQuantity(transfer.totalSeconds, seconds, "modeled transfer seconds");
                transfer.maxArrival = Math.max(transfer.maxArrival, parent.getFinishTime() + seconds);
            }

            List<FileItem> external = new ArrayList<FileItem>();
            for (FileItem file : fileList) {
                if (file.isRealInputFile(fileList) && !attributedNames.contains(file.getName())) {
                    external.add(file);
                }
            }
            if (!external.isEmpty()) {
                double seconds = datacenter.estimateTransferSecondsForFiles(external, job);
                validateTransferEstimate(sumTransferableBytes(external, job, datacenter), seconds);
                transfer.totalSeconds =
                        addFiniteTransferQuantity(transfer.totalSeconds, seconds, "modeled transfer seconds");
                transfer.maxArrival = Math.max(transfer.maxArrival, now + seconds);
            }
            for (FileItem file : fileList) {
                if (file.isRealInputFile(fileList)) {
                    transfer.requiredBytes =
                            addFiniteTransferQuantity(transfer.requiredBytes, file.getSize(), "required input bytes");
                    transfer.fileCount++;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "WorkflowEngine could not estimate pre-execution stage-in for Job " + job.getCloudletId(), e);
        }
        return transfer;
    }

    private static final class PreExecutionTransfer {
        private double totalSeconds;
        private double requiredBytes;
        private int fileCount;
        private double maxArrival;
    }

    protected void processPreExecutionStageInComplete(SimEvent ev) {
        Job job = (Job) ev.getData();
        WorkflowDatacenter datacenter = (WorkflowDatacenter) CloudSim.getEntity(boundDatacenterId);
        try {
            datacenter.registerStageInReplicasForComputeJob(job);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "WorkflowEngine could not register pre-execution stage-in " + "replicas for Job "
                            + job.getCloudletId(),
                    e);
        }
        List<Job> batch = new ArrayList<Job>();
        batch.add(job);
        dispatchReadyBatch(job.getUserId(), batch);
    }

    private boolean startContentionStageIn(Job job) {
        if (boundDatacenterId < 0) {
            return false;
        }
        SimEntity entity = CloudSim.getEntity(boundDatacenterId);
        if (!(entity instanceof WorkflowDatacenter)) {
            return false;
        }
        WorkflowDatacenter datacenter = (WorkflowDatacenter) entity;
        boolean fatTree = datacenter.getDataMovementModel().isFatTreeContentionV1();
        if (!datacenter.getDataMovementModel().isPreExecutionTransferDelayWithContentionV1() && !fatTree) {
            return false;
        }
        if (job.getClassType() != ClassType.COMPUTE.value) {
            return false;
        }
        if (job.getVmId() < 0) {
            throw new IllegalStateException(
                    "Contention transfer requires a static VM mapping for Job " + job.getCloudletId());
        }
        TransferContentionEngine contention = datacenter.getTransferContentionEngine();
        double now = CloudSim.clock();
        settleContentionTransfers(datacenter, contention.advance(now).getCompletedTransferIds());
        ContentionStageInAggregates aggregates =
                registerContentionStageInGroups(job, datacenter, contention, now, fatTree);
        Map<String, Object> stageInAttributes = SimulationEventRecorder.attributes(
                "modeledTransferSeconds", aggregates.modeledSeconds,
                "requiredFileBytes", aggregates.requiredBytes,
                "modeledTransferFileCount", aggregates.fileCount,
                "dataMovementModel", datacenter.getDataMovementModel().getKind().name(),
                "contentionTransferGroupCount", (double) aggregates.pending.size());
        if (fatTree) {
            stageInAttributes.put("fatTreePathLinkCount", (double) aggregates.pathLinkCount);
        }
        eventRecorder.record(SimulationEventType.DATA_STAGE_IN_MODELED, now, job, stageInAttributes);
        if (aggregates.pending.isEmpty()) {

            try {
                datacenter.registerStageInReplicasForComputeJob(job);
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "Could not register zero-transfer inputs for Job " + job.getCloudletId(), exception);
            }
            return false;
        }
        contentionJobPendingTransfers.put(job.getCloudletId(), aggregates.pending);
        Double nextCompletion = contention.advance(now).getNextCompletionTime();
        schedule(
                getId(),
                Math.max(nextCompletion.doubleValue() - now, CloudSim.getMinTimeBetweenEvents()),
                WorkflowSimTags.TRANSFER_CONTENTION_CHECK,
                null);
        return true;
    }

    private ContentionStageInAggregates registerContentionStageInGroups(
            Job job, WorkflowDatacenter datacenter, TransferContentionEngine contention, double now, boolean fatTree) {
        List<FileItem> fileList = job.getFileList();
        ContentionStageInAggregates aggregates = new ContentionStageInAggregates();
        boolean localFileSystem = ReplicaCatalog.getFileSystem() == ReplicaCatalog.FileSystem.LOCAL;
        try {
            Set<String> attributedNames = new HashSet<String>();

            for (Object parentObj : job.getParentList()) {
                Job parent = (Job) parentObj;
                List<FileItem> fromParent = collectFilesProducedByParent(parent, fileList, attributedNames);
                if (fromParent.isEmpty()) {
                    continue;
                }
                registerContentionTransferFromParent(
                        job, parent, fromParent, datacenter, contention, now, fatTree, localFileSystem, aggregates);
            }

            registerExternalInputTransfers(
                    job, fileList, datacenter, contention, now, fatTree, attributedNames, aggregates);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "WorkflowEngine could not register contention stage-in " + "for Job " + job.getCloudletId(), e);
        }
        return aggregates;
    }

    private static List<FileItem> collectFilesProducedByParent(
            Job parent, List<FileItem> fileList, Set<String> attributedNames) {
        Set<String> produced = new HashSet<String>();
        for (FileItem producedFile : parent.getFileList()) {
            if (producedFile.getType() == Parameters.FileType.OUTPUT) {
                produced.add(producedFile.getName());
            }
        }
        List<FileItem> fromParent = new ArrayList<FileItem>();
        for (FileItem file : fileList) {
            if (file.isRealInputFile(fileList) && produced.contains(file.getName())) {
                fromParent.add(file);
                attributedNames.add(file.getName());
            }
        }
        return fromParent;
    }

    private void registerContentionTransferFromParent(
            Job job,
            Job parent,
            List<FileItem> fromParent,
            WorkflowDatacenter datacenter,
            TransferContentionEngine contention,
            double now,
            boolean fatTree,
            boolean localFileSystem,
            ContentionStageInAggregates aggregates)
            throws Exception {
        double seconds = datacenter.estimateTransferSecondsForFiles(fromParent, job);
        double bytes = sumRealInputBytes(fromParent);
        aggregates.requiredBytes = addFiniteTransferQuantity(aggregates.requiredBytes, bytes, "required input bytes");
        aggregates.fileCount += fromParent.size();
        aggregates.modeledSeconds =
                addFiniteTransferQuantity(aggregates.modeledSeconds, seconds, "modeled transfer seconds");

        double transferredBytes = sumTransferableBytes(fromParent, job, datacenter);
        validateTransferEstimate(transferredBytes, seconds);
        if (seconds <= 0.0 || transferredBytes <= 0.0) {
            return;
        }
        String sourceEndpoint = localFileSystem ? "VM:" + parent.getVmId() : Parameters.SOURCE;
        long transferId = nextContentionTransferId++;
        String destinationEndpoint = "VM:" + job.getVmId();
        List<String> resources;
        if (fatTree) {

            List<String> pathLinks = Parameters.SOURCE.equals(sourceEndpoint)
                    ? java.util.Collections.<String>emptyList()
                    : datacenter.fatTreePathResources(parent.getVmId(), job.getVmId(), job.getUserId());
            aggregates.pathLinkCount += pathLinks.size();
            resources = new ArrayList<String>();
            resources.add(sourceEndpoint);
            resources.add(destinationEndpoint);
            resources.addAll(pathLinks);
            settleContentionTransfers(
                    datacenter,
                    contention
                            .addTransfer(transferId, transferredBytes, resources, transferredBytes / seconds, now)
                            .getCompletedTransferIds());
        } else {
            resources = java.util.Arrays.asList(sourceEndpoint, destinationEndpoint);
            settleContentionTransfers(
                    datacenter,
                    contention
                            .addTransfer(transferId, transferredBytes, resources, transferredBytes / seconds, now)
                            .getCompletedTransferIds());
        }
        datacenter.recordNetworkFlowBinding(
                transferId,
                job,
                Integer.valueOf(parent.getCloudletId()),
                NetworkFlowBinding.GroupKind.PARENT_GROUP_V1,
                sourceEndpoint,
                destinationEndpoint,
                resources);
        contentionTransferJobs.put(transferId, job);
        aggregates.pending.add(transferId);
    }

    private void registerExternalInputTransfers(
            Job job,
            List<FileItem> fileList,
            WorkflowDatacenter datacenter,
            TransferContentionEngine contention,
            double now,
            boolean fatTree,
            Set<String> attributedNames,
            ContentionStageInAggregates aggregates)
            throws Exception {
        List<FileItem> external = new ArrayList<FileItem>();
        for (FileItem file : fileList) {
            if (file.isRealInputFile(fileList) && !attributedNames.contains(file.getName())) {
                external.add(file);
            }
        }
        if (!external.isEmpty()) {
            double seconds = datacenter.estimateTransferSecondsForFiles(external, job);
            double bytes = sumRealInputBytes(external);
            aggregates.requiredBytes =
                    addFiniteTransferQuantity(aggregates.requiredBytes, bytes, "required input bytes");
            aggregates.fileCount += external.size();
            aggregates.modeledSeconds =
                    addFiniteTransferQuantity(aggregates.modeledSeconds, seconds, "modeled transfer seconds");

            double transferredBytes = sumTransferableBytes(external, job, datacenter);
            validateTransferEstimate(transferredBytes, seconds);
            if (seconds > 0.0 && transferredBytes > 0.0) {
                long transferId = nextContentionTransferId++;
                String destinationEndpoint = "VM:" + job.getVmId();
                List<String> resources;
                if (fatTree) {

                    resources = java.util.Collections.singletonList(destinationEndpoint);
                    settleContentionTransfers(
                            datacenter,
                            contention
                                    .addTransfer(
                                            transferId, transferredBytes, resources, transferredBytes / seconds, now)
                                    .getCompletedTransferIds());
                } else {
                    resources = java.util.Arrays.asList(Parameters.SOURCE, destinationEndpoint);
                    settleContentionTransfers(
                            datacenter,
                            contention
                                    .addTransfer(
                                            transferId, transferredBytes, resources, transferredBytes / seconds, now)
                                    .getCompletedTransferIds());
                }
                datacenter.recordNetworkFlowBinding(
                        transferId,
                        job,
                        null,
                        NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1,
                        Parameters.SOURCE,
                        destinationEndpoint,
                        resources);
                contentionTransferJobs.put(transferId, job);
                aggregates.pending.add(transferId);
            }
        }
    }

    private static final class ContentionStageInAggregates {
        private final Set<Long> pending = new LinkedHashSet<Long>();
        private double requiredBytes;
        private int fileCount;
        private int pathLinkCount;
        private double modeledSeconds;
    }

    private static double sumRealInputBytes(List<FileItem> files) {
        double bytes = 0.0;
        for (FileItem file : files) {
            bytes = addFiniteTransferQuantity(bytes, file.getSize(), "required input bytes");
        }
        return bytes;
    }

    private double sumTransferableBytes(List<FileItem> files, Job job, WorkflowDatacenter datacenter) {
        double bytes = 0.0;
        for (FileItem file : files) {
            boolean local = datacenter.isFileLocalForJob(file, job);
            datacenter.recordNetworkInputDemand(file, local);
            if (!local) {
                bytes = addFiniteTransferQuantity(bytes, file.getSize(), "transferable bytes");
            }
        }
        return bytes;
    }

    private static double addFiniteTransferQuantity(double total, double value, String field) {
        double result = total + value;
        if (!Double.isFinite(total)
                || total < 0.0
                || !Double.isFinite(value)
                || value < 0.0
                || !Double.isFinite(result)) {
            throw new IllegalArgumentException("Non-finite or negative " + field + " aggregate");
        }
        return result;
    }

    private static void validateTransferEstimate(double transferredBytes, double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0.0) {
            throw new IllegalArgumentException("Transfer estimate must be finite and nonnegative");
        }
        if (transferredBytes > 0.0) {
            double rate = transferredBytes / seconds;
            if (!(seconds > 0.0) || !(rate > 0.0) || !Double.isFinite(rate)) {
                throw new IllegalArgumentException(
                        "Positive nonlocal bytes require representable positive transfer seconds and rate");
            }
        }
    }

    protected void processTransferContentionCheck(SimEvent ev) {
        WorkflowDatacenter datacenter = (WorkflowDatacenter) CloudSim.getEntity(boundDatacenterId);
        TransferContentionEngine contention = datacenter.getTransferContentionEngine();
        double now = CloudSim.clock();
        TransferContentionEngine.AdvanceResult result = contention.advance(now);
        settleContentionTransfers(datacenter, result.getCompletedTransferIds());
        Double nextCompletion = result.getNextCompletionTime();
        if (nextCompletion != null) {
            schedule(
                    getId(),
                    Math.max(nextCompletion.doubleValue() - now, CloudSim.getMinTimeBetweenEvents()),
                    WorkflowSimTags.TRANSFER_CONTENTION_CHECK,
                    null);
        }
    }

    private void settleContentionTransfers(WorkflowDatacenter datacenter, List<Long> completed) {
        List<Job> readyJobs = new ArrayList<Job>();
        for (Long transferId : completed) {
            Job job = contentionTransferJobs.remove(transferId);
            if (job == null) {
                continue;
            }
            Set<Long> pending = contentionJobPendingTransfers.get(job.getCloudletId());
            if (pending == null) {
                throw new IllegalStateException("Completed transfer has no pending Job " + job.getCloudletId());
            }
            pending.remove(transferId);
            if (pending.isEmpty()) {
                contentionJobPendingTransfers.remove(job.getCloudletId());
                readyJobs.add(job);
            }
        }
        for (Job job : readyJobs) {
            dispatchContentionStageInComplete(datacenter, job);
        }
    }

    private void dispatchContentionStageInComplete(WorkflowDatacenter datacenter, Job job) {
        try {
            datacenter.registerStageInReplicasForComputeJob(job);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "WorkflowEngine could not register contention stage-in " + "replicas for Job "
                            + job.getCloudletId(),
                    e);
        }
        List<Job> batch = new ArrayList<Job>();
        batch.add(job);
        dispatchReadyBatch(job.getUserId(), batch);
    }

    protected void processJobReturn(SimEvent ev) {

        Job job = (Job) ev.getData();
        if (job.getCloudletStatus() == Cloudlet.FAILED) {

            int newId = nextAvailableJobId(job);
            List<Job> retries = ReclusteringEngine.process(job, newId);
            if (retries.isEmpty()) {
                throw new IllegalStateException("Failed job " + job.getCloudletId() + " produced no retry jobs");
            }
            enforceRetryBudget(job, retries.size());
            getJobsList().addAll(retries);
            retryJobsCreated += retries.size();
            for (Job retry : retries) {

                retryOfFailedJobIds.put(retry.getCloudletId(), job.getCloudletId());
                eventRecorder.record(
                        SimulationEventType.RETRY_JOB_CREATED,
                        CloudSim.clock(),
                        retry,
                        SimulationEventRecorder.attributes("failedJobId", job.getCloudletId()));
            }
        }

        getJobsReceivedList().add(job);
        if (job.getCloudletStatus() == Cloudlet.SUCCESS) {
            receivedSuccessfulJobIds.add(job.getCloudletId());
        }
        jobsSubmitted--;
        if (getJobsList().isEmpty() && jobsSubmitted == 0) {
            if (coherentDataflow != null) {
                scheduleCoherentCheck();
                finishCoherentIfIdle();
                return;
            }

            for (int i = 0; i < getSchedulerIds().size(); i++) {
                sendNow(getSchedulerId(i), CloudSimTags.END_OF_SIMULATION, null);
            }
        } else {
            sendNow(this.getId(), CloudSimTags.CLOUDLET_SUBMIT, null);
        }
    }

    private int nextAvailableJobId(Job currentJob) {
        int maximum = currentJob.getCloudletId();
        maximum = Math.max(maximum, maximumJobId(getJobsList()));
        maximum = Math.max(maximum, maximumJobId(getJobsSubmittedList()));
        maximum = Math.max(maximum, maximumJobId(getJobsReceivedList()));
        if (maximum == Integer.MAX_VALUE) {
            throw new IllegalStateException("Cannot allocate a retry Job ID after Integer.MAX_VALUE");
        }
        return maximum + 1;
    }

    private void enforceRetryBudget(Job failedJob, int requestedRetryJobs) {
        int budget = FailureParameters.getMaxTotalRetryJobs();
        if (budget < 0) {
            return;
        }
        if (requestedRetryJobs <= 0 || retryJobsCreated > budget - requestedRetryJobs) {
            throw new RetryLimitExceededException(
                    failedJob.getCloudletId(),
                    retryJobsCreated,
                    requestedRetryJobs,
                    budget,
                    FailureParameters.getRootSeed());
        }
    }

    private static int maximumJobId(List<? extends Cloudlet> jobs) {
        int maximum = -1;
        for (Cloudlet cloudlet : jobs) {
            if (!(cloudlet instanceof Job)) {
                throw new IllegalStateException("Workflow Engine contains a non-Job cloudlet");
            }
            maximum = Math.max(maximum, cloudlet.getCloudletId());
        }
        return maximum;
    }

    protected void processOtherEvent(SimEvent ev) {
        if (ev == null) {
            Log.printLine(getName() + ".processOtherEvent(): " + "Error - an event is null.");
            return;
        }
        Log.printLine(getName() + ".processOtherEvent(): " + "Error - event unknown by this DatacenterBroker.");
    }

    protected void submitJobs() {

        List<Job> list = getJobsList();
        Map<Integer, List> allocationList = new HashMap<>();
        for (int i = 0; i < getSchedulers().size(); i++) {
            List<Job> submittedList = new ArrayList<>();
            allocationList.put(getSchedulerId(i), submittedList);
        }
        int num = list.size();
        for (int i = 0; i < num; i++) {

            Job job = list.get(i);

            if (!receivedSuccessfulJobIds.contains(job.getCloudletId())) {
                List<Job> parentList = job.getParentList();
                boolean flag = true;
                for (Job parent : parentList) {
                    if (!receivedSuccessfulJobIds.contains(parent.getCloudletId())) {
                        flag = false;
                        break;
                    }
                }

                if (flag) {

                    double arrivalSecond = arrivalSecondFor(job);
                    if (arrivalSecond > CloudSim.clock()) {
                        scheduleArrivalRescan(arrivalSecond);
                        continue;
                    }
                    announceWorkflowArrivalFor(job);
                    org.workflowsim.data.v2.CoherentDataflowRuntime.Preparation coherentPreparation =
                            prepareCoherentInput(job);

                    recordJobReady(job, retryOfFailedJobIds.get(job.getCloudletId()), parentList.size());
                    jobsSubmitted++;
                    getJobsSubmittedList().add(job);
                    list.remove(job);
                    i--;
                    num--;

                    if (coherentPreparation != null) {
                        recordDataStageInModeled(job, coherentPreparation);
                    } else if (!startContentionStageIn(job)) {
                        double stageInHoldSeconds = preExecutionStageInHoldSeconds(job);
                        if (stageInHoldSeconds < 0.0 || stageInHoldSeconds == 0.0) {
                            allocationList.get(job.getUserId()).add(job);
                        } else {
                            schedule(getId(), stageInHoldSeconds, WorkflowSimTags.JOB_STAGE_IN_COMPLETE, job);
                        }
                    }
                }
            }
        }
        appendCoherentReady(allocationList);
        scheduleCoherentCheck();
        for (int i = 0; i < getSchedulers().size(); i++) {
            dispatchReadyBatch(getSchedulerId(i), allocationList.get(getSchedulerId(i)));
        }
    }

    private void recordJobReady(Job job, Integer retryOfFailedJobId, int parentCount) {
        eventRecorder.record(
                SimulationEventType.JOB_READY,
                CloudSim.clock(),
                job,
                retryOfFailedJobId != null
                        ? SimulationEventRecorder.attributes(
                                "parentJobCount", parentCount, "retryOfFailedJobId", retryOfFailedJobId)
                        : SimulationEventRecorder.attributes("parentJobCount", parentCount));
    }

    private void recordDataStageInModeled(
            Job job, org.workflowsim.data.v2.CoherentDataflowRuntime.Preparation coherentPreparation) {
        coherentPendingJobs.put(job.getCloudletId(), job);
        if (!coherentDataflow.isStorageVersion()) {
            eventRecorder.record(
                    SimulationEventType.DATA_STAGE_IN_MODELED,
                    CloudSim.clock(),
                    job,
                    SimulationEventRecorder.attributes(
                            "modeledTransferSeconds",
                            coherentPreparation.getIsolatedSeconds(),
                            "requiredFileBytes",
                            coherentPreparation.getRequiredBytes(),
                            "modeledTransferFileCount",
                            coherentPreparation.getReferenceCount(),
                            "dataMovementModel",
                            ((WorkflowDatacenter) CloudSim.getEntity(boundDatacenterId))
                                    .getDataMovementModel()
                                    .getKind()
                                    .name(),
                            "newFileCopies",
                            coherentPreparation.getNewCopies(),
                            "joinedFileCopies",
                            coherentPreparation.getJoinedCopies(),
                            "transferUnit",
                            "LOGICAL_FILE_V2"));
        }
    }

    private void dispatchReadyBatch(int scheduler, List<Job> jobs) {
        if (jobs.isEmpty()) {
            return;
        }
        int interval = Parameters.getOverheadParams().getWEDInterval();
        int batchSize = interval > 0 ? Math.min(interval, jobs.size()) : jobs.size();
        double baseDelay = Parameters.getOverheadParams().getWEDDelay(jobs);
        if (!Double.isFinite(baseDelay) || baseDelay < 0.0) {
            throw new IllegalStateException("Workflow-engine delay must be finite and non-negative");
        }
        double delay = 0.0;
        for (int first = 0; first < jobs.size(); ) {
            int end = first + Math.min(batchSize, jobs.size() - first);
            delay += baseDelay;
            if (!Double.isFinite(delay)) {
                throw new IllegalStateException("Workflow-engine batch delay overflow");
            }
            schedule(scheduler, delay, CloudSimTags.CLOUDLET_SUBMIT, new ArrayList<Job>(jobs.subList(first, end)));
            first = end;
        }
    }

    @Override
    public void shutdownEntity() {
        Log.printLine(getName() + " is shutting down...");
    }

    @Override
    public void startEntity() {
        Log.printLine(getName() + " is starting...");
        schedule(getId(), 0, CloudSimTags.RESOURCE_CHARACTERISTICS_REQUEST);
    }

    @SuppressWarnings("unchecked")
    public <T extends Cloudlet> List<T> getJobsList() {
        return (List<T>) jobsList;
    }

    public int getInFlightJobCount() {
        return jobsSubmitted;
    }

    private <T extends Cloudlet> void setJobsList(List<T> jobsList) {
        this.jobsList = jobsList;
    }

    @SuppressWarnings("unchecked")
    public <T extends Cloudlet> List<T> getJobsSubmittedList() {
        return (List<T>) jobsSubmittedList;
    }

    private <T extends Cloudlet> void setJobsSubmittedList(List<T> jobsSubmittedList) {
        this.jobsSubmittedList = jobsSubmittedList;
    }

    @SuppressWarnings("unchecked")
    public <T extends Cloudlet> List<T> getJobsReceivedList() {
        return (List<T>) jobsReceivedList;
    }

    private <T extends Cloudlet> void setJobsReceivedList(List<T> jobsReceivedList) {
        this.jobsReceivedList = jobsReceivedList;

        receivedSuccessfulJobIds.clear();
        if (jobsReceivedList != null) {
            for (Cloudlet item : jobsReceivedList) {
                if (item.getCloudletStatus() == Cloudlet.SUCCESS) {
                    receivedSuccessfulJobIds.add(item.getCloudletId());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends Vm> List<T> getVmList() {
        return (List<T>) vmList;
    }

    private <T extends Vm> void setVmList(List<T> vmList) {
        this.vmList = vmList;
    }

    public List<WorkflowScheduler> getSchedulers() {
        return this.scheduler;
    }

    private void setSchedulers(List list) {
        this.scheduler = list;
    }

    public List<Integer> getSchedulerIds() {
        return this.schedulerId;
    }

    private void setSchedulerIds(List list) {
        this.schedulerId = list;
    }

    public int getSchedulerId(int index) {
        if (this.schedulerId != null) {
            return this.schedulerId.get(index);
        }
        return 0;
    }

    public WorkflowScheduler getScheduler(int schedulerId) {
        if (this.scheduler != null) {
            return this.scheduler.get(schedulerId);
        }
        return null;
    }
}
