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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletScheduler;
import org.cloudbus.cloudsim.Consts;
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.NetworkFlowBinding;
import org.workflowsim.data.NetworkInputDemandSnapshot;
import org.workflowsim.data.NetworkInputDemandTracker;
import org.workflowsim.data.NetworkRunEvidence;
import org.workflowsim.data.TransferContentionEngine;
import org.workflowsim.experiment.SimulationEventRecorder;
import org.workflowsim.experiment.SimulationEventType;
import org.workflowsim.failure.FailureGenerator;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationTiming;
import org.workflowsim.utils.TaskExecutionModel;

public class WorkflowDatacenter extends Datacenter {

    private SimulationEventRecorder eventRecorder = SimulationEventRecorder.disabled();

    private DataMovementModel dataMovementModel = DataMovementModel.legacyWorkflowsimV1();

    private TransferContentionEngine transferContentionEngine;

    private NetworkEvidenceConfig networkEvidenceConfig = NetworkEvidenceConfig.off();

    private Map<Long, NetworkFlowBinding> networkFlowBindings;

    private NetworkInputDemandTracker inputDemandTracker;

    private org.workflowsim.network.FatTreeTopology fatTreeTopology;

    private org.workflowsim.data.v2.CoherentDataflowRuntime coherentDataflow;
    private int coherentEngineId = -1;

    private final Map<Integer, double[]> runningAttemptCompletions = new LinkedHashMap<Integer, double[]>();

    private org.workflowsim.data.v2.DataflowStorageSpec sourceStorage;

    public void setSourceStorage(org.workflowsim.data.v2.DataflowStorageSpec storage) {
        if (storage == null || sourceStorage != null || !dataMovementModel.isCoherentStorageDataflowV3()) {
            throw new IllegalArgumentException("Storage spec requires one storage-V3 installation");
        }
        sourceStorage = storage;
    }

    public void setCoherentDataflowRuntime(org.workflowsim.data.v2.CoherentDataflowRuntime runtime, int engineId) {
        if (runtime == null
                || engineId < 0
                || coherentDataflow != null
                || !dataMovementModel.usesCoherentDataflowRuntime()) {
            throw new IllegalArgumentException("Invalid V2 runtime installation");
        }
        coherentDataflow = runtime;
        coherentEngineId = engineId;
    }

    private org.workflowsim.data.v2.CoherentDataflowRuntime requireCoherentRuntime() {
        if (coherentDataflow == null) {
            throw new IllegalStateException("Coherent V2 model requires its dedicated initialized run context");
        }
        return coherentDataflow;
    }

    void initializeCoherentFabric() {
        org.workflowsim.data.v2.CoherentDataflowRuntime runtime = requireCoherentRuntime();
        if (runtime.isFabricBound()) {
            return;
        }
        Map<Integer, Double> capacities = new LinkedHashMap<Integer, Double>();
        Map<Integer, Integer> hosts = new LinkedHashMap<Integer, Integer>();
        for (Host host : getVmAllocationPolicy().getHostList()) {
            for (Vm vm : host.getVmList()) {
                capacities.put(vm.getId(), vm.getBw() * (double) Consts.MILLION);
                hosts.put(vm.getId(), host.getId());
            }
        }
        java.util.List<String> sources = Collections.singletonList("source");
        runtime.bindFabric(
                runtime.isStorageVersion()
                        ? org.workflowsim.data.v2.DataTransferFabric.withStorage(
                                capacities, hosts, fatTreeTopology, sourceStorage)
                        : fatTreeTopology == null
                                ? org.workflowsim.data.v2.DataTransferFabric.endpoints(capacities, sources)
                                : org.workflowsim.data.v2.DataTransferFabric.fatTree(
                                        capacities, hosts, fatTreeTopology, sources));
    }

    java.util.List<org.workflowsim.data.v2.DataflowVmAssigner.VmOption> actualDataflowVmOptions() {
        java.util.List<org.workflowsim.data.v2.DataflowVmAssigner.VmOption> result = new ArrayList<>();
        for (Host host : getVmAllocationPolicy().getHostList()) {
            for (Vm vm : host.getVmList()) {
                result.add(new org.workflowsim.data.v2.DataflowVmAssigner.VmOption(
                        vm.getId(), vm.getMips(), vm.getNumberOfPes()));
            }
        }
        return Collections.unmodifiableList(result);
    }

    public org.workflowsim.data.v2.FileLifecycleEvidence captureFileLifecycleEvidence() {
        return networkEvidenceConfig.getMode() == NetworkEvidenceConfig.Mode.FILE_LIFECYCLE_V2
                ? requireCoherentRuntime().captureEvidence()
                : null;
    }

    public org.workflowsim.data.v2.StorageLifecycleEvidence captureStorageLifecycleEvidence() {
        return networkEvidenceConfig.getMode() == NetworkEvidenceConfig.Mode.FILE_STORAGE_LIFECYCLE_V3
                ? requireCoherentRuntime().captureStorageEvidence()
                : null;
    }

    private static java.util.List<Integer> coherentTaskIds(Job job) {
        java.util.List<Integer> ids = new ArrayList<Integer>();
        for (Task task : job.getTaskList()) {
            ids.add(task.getCloudletId());
        }
        return ids;
    }

    public WorkflowDatacenter(
            String name,
            DatacenterCharacteristics characteristics,
            VmAllocationPolicy vmAllocationPolicy,
            List<Storage> storageList,
            double schedulingInterval)
            throws Exception {
        super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval);
    }

    public void setEventRecorder(SimulationEventRecorder value) {
        if (value == null) {
            throw new IllegalArgumentException("Event recorder cannot be null");
        }
        this.eventRecorder = value;
    }

    public void setDataMovementModel(DataMovementModel value) {
        if (value == null) {
            throw new IllegalArgumentException("Data movement model cannot be null");
        }
        this.dataMovementModel = value;
    }

    public DataMovementModel getDataMovementModel() {
        return dataMovementModel;
    }

    public void setNetworkEvidenceConfig(NetworkEvidenceConfig value) {
        if (value == null) {
            throw new IllegalArgumentException("Network evidence configuration cannot be null");
        }
        if (transferContentionEngine != null) {
            throw new IllegalStateException("Network evidence must be configured before creating the transfer engine");
        }
        networkEvidenceConfig = value;
    }

    public NetworkEvidenceConfig getNetworkEvidenceConfig() {
        return networkEvidenceConfig;
    }

    void recordNetworkInputDemand(FileItem file, boolean local) {
        if (networkEvidenceConfig.getMode() != NetworkEvidenceConfig.Mode.FLUID_GROUP_LEDGER_V1) {
            return;
        }
        if (transferContentionEngine == null) {
            throw new IllegalStateException("Input demand can only be observed after creating the transfer engine");
        }
        if (file == null) {
            throw new IllegalArgumentException("Input reference is required");
        }
        if (inputDemandTracker == null) {
            inputDemandTracker = new NetworkInputDemandTracker();
        }
        inputDemandTracker.record(file.getSize(), local);
    }

    void recordNetworkFlowBinding(
            long transferId,
            Job job,
            Integer parentJobId,
            NetworkFlowBinding.GroupKind groupKind,
            String sourceEndpoint,
            String destinationEndpoint,
            List<String> occupiedResources) {
        if (networkEvidenceConfig.getMode() != NetworkEvidenceConfig.Mode.FLUID_GROUP_LEDGER_V1) {
            return;
        }
        if (transferContentionEngine == null) {
            throw new IllegalStateException("Cannot bind a network flow before a successful transfer admission");
        }
        long ordinal = transferContentionEngine.getActiveTransferAdmissionOrdinal(transferId);

        if (ordinal > networkEvidenceConfig.getMaxTraceRecords()) {
            return;
        }
        List<Integer> taskIds = new ArrayList<Integer>();
        for (Task task : job.getTaskList()) {
            taskIds.add(task.getCloudletId());
        }
        NetworkFlowBinding binding = NetworkFlowBinding.of(
                transferId,
                ordinal,
                job.getCloudletId(),
                taskIds,
                parentJobId,
                groupKind,
                sourceEndpoint,
                destinationEndpoint,
                occupiedResources);
        if (networkFlowBindings == null) {
            networkFlowBindings = new LinkedHashMap<Long, NetworkFlowBinding>();
        }
        if (networkFlowBindings.containsKey(ordinal)) {
            throw new IllegalStateException("Network admission already has a binding: " + ordinal);
        }
        networkFlowBindings.put(ordinal, binding);
    }

    public NetworkRunEvidence captureNetworkEvidence() {
        if (networkEvidenceConfig.getMode() != NetworkEvidenceConfig.Mode.FLUID_GROUP_LEDGER_V1) {
            return null;
        }
        if (transferContentionEngine == null) {
            return NetworkRunEvidence.empty(networkEvidenceConfig, dataMovementModel.getKind());
        }
        List<NetworkFlowBinding> candidates = networkFlowBindings == null
                ? Collections.<NetworkFlowBinding>emptyList()
                : new ArrayList<NetworkFlowBinding>(networkFlowBindings.values());
        NetworkInputDemandSnapshot inputDemand =
                inputDemandTracker == null ? NetworkInputDemandSnapshot.empty() : inputDemandTracker.snapshot();
        return NetworkRunEvidence.capture(
                networkEvidenceConfig,
                dataMovementModel.getKind(),
                transferContentionEngine.getTraceSnapshot(),
                candidates,
                inputDemand);
    }

    public void setFatTreeTopology(org.workflowsim.network.FatTreeTopology value) {
        if (value == null) {
            throw new IllegalArgumentException("Fat-tree topology cannot be null");
        }
        this.fatTreeTopology = value;
    }

    public org.workflowsim.network.FatTreeTopology getFatTreeTopology() {
        return fatTreeTopology;
    }

    public TransferContentionEngine getTransferContentionEngine() {
        if (!dataMovementModel.isPreExecutionTransferDelayWithContentionV1()
                && !dataMovementModel.isFatTreeContentionV1()) {
            return null;
        }
        if (transferContentionEngine == null) {
            transferContentionEngine = networkEvidenceConfig.isEnabled()
                    ? new TransferContentionEngine(networkEvidenceConfig.getMaxTraceRecords())
                    : new TransferContentionEngine();
            for (Host host : getVmAllocationPolicy().getHostList()) {
                for (Vm vm : host.getVmList()) {
                    transferContentionEngine.setEndpointCapacity(
                            "VM:" + vm.getId(), vm.getBw() * (double) Consts.MILLION);
                }
            }
            if (dataMovementModel.isFatTreeContentionV1()) {
                if (fatTreeTopology == null) {
                    throw new IllegalStateException("Fat-tree contention model requires an installed "
                            + "topology (WorkflowDatacenter.setFatTreeTopology)");
                }
                fatTreeTopology.registerCapacities(transferContentionEngine);
            }
        }
        return transferContentionEngine;
    }

    public java.util.List<String> fatTreePathResources(int sourceVmId, int destinationVmId, int userId) {
        if (!dataMovementModel.isFatTreeContentionV1() || fatTreeTopology == null) {
            return java.util.Collections.emptyList();
        }
        Host sourceHost = getVmAllocationPolicy().getHost(sourceVmId, userId);
        Host destinationHost = getVmAllocationPolicy().getHost(destinationVmId, userId);
        if (sourceHost == null || destinationHost == null) {
            throw new IllegalStateException("Fat-tree contention requires placed source and " + "destination VMs ("
                    + sourceVmId + " -> " + destinationVmId + ")");
        }
        return fatTreeTopology.route(sourceHost.getId(), destinationHost.getId());
    }

    public double estimateTransferSecondsForFiles(List<FileItem> files, Job job) throws Exception {
        if (dataMovementModel.usesCoherentDataflowRuntime()) {
            throw new UnsupportedOperationException(
                    "Coherent V2 requires one immutable visible-source/resource-route decision, not the V1 scalar"
                            + "estimator");
        }
        double time = 0.0;
        for (FileItem file : files) {
            if (!file.isRealInputFile(files)) {
                continue;
            }
            List siteList = ReplicaCatalog.getStorageList(file.getName());
            if (siteList == null || siteList.isEmpty()) {
                throw new IllegalStateException(
                        "Required input file '" + file.getName() + "' has no registered replica");
            }
            switch (ReplicaCatalog.getFileSystem()) {
                case SHARED:
                    double maxRate = 0.0;
                    for (Storage storage : getStorageList()) {
                        double rate = storage.getMaxTransferRate();
                        if (rate > maxRate) {
                            maxRate = rate;
                        }
                    }
                    if (!(maxRate > 0.0) || Double.isInfinite(maxRate)) {
                        throw new IllegalStateException("Shared storage has no positive transfer rate for "
                                + "required input file '" + file.getName() + "'");
                    }
                    time += file.getSize() / (double) Consts.MILLION / maxRate;
                    break;
                case LOCAL:
                    time += localStageInSecondsForFile(file, siteList, job);
                    break;
                default:
                    throw new IllegalStateException(
                            "Unsupported replica file system " + ReplicaCatalog.getFileSystem());
            }
        }
        return time;
    }

    public boolean isFileLocalForJob(FileItem file, Job job) {
        if (ReplicaCatalog.getFileSystem() == ReplicaCatalog.FileSystem.SHARED) {
            return false;
        }
        List siteList = ReplicaCatalog.getStorageList(file.getName());
        return siteList != null && siteList.contains(Integer.toString(job.getVmId()));
    }

    public void registerStageInReplicasForComputeJob(Job job) throws Exception {
        if (ReplicaCatalog.getFileSystem() != ReplicaCatalog.FileSystem.LOCAL) {
            return;
        }
        boolean hasRealInput = false;
        for (FileItem file : job.getFileList()) {
            if (file.isRealInputFile(job.getFileList())) {
                hasRealInput = true;
                break;
            }
        }
        if (!hasRealInput) {
            return;
        }
        int vmId = job.getVmId();
        int userId = job.getUserId();
        requiredVm(vmId, userId, "pre-execution stage-in replica registration");
        for (FileItem file : job.getFileList()) {
            if (file.isRealInputFile(job.getFileList())) {
                ReplicaCatalog.addFileToStorage(file.getName(), Integer.toString(vmId));
            }
        }
    }

    private double localStageInSecondsForFile(FileItem file, List siteList, Job job) throws Exception {
        int vmId = job.getVmId();
        int userId = job.getUserId();
        Host host = getVmAllocationPolicy().getHost(vmId, userId);
        if (host == null) {
            throw new IllegalStateException("No host contains VM " + vmId + " for local stage-in");
        }
        Vm vm = host.getVm(vmId, userId);
        if (vm == null) {
            throw new IllegalStateException("No VM " + vmId + " is available for local stage-in");
        }
        boolean requiredFileStagein = true;
        double maxBwth = 0.0;
        for (Iterator it = siteList.iterator(); it.hasNext(); ) {

            String site = (String) it.next();
            if (site.equals(this.getName())) {
                continue;
            }

            if (site.equals(Integer.toString(vmId))) {
                requiredFileStagein = false;
                break;
            }
            double bwth;
            if (site.equals(Parameters.SOURCE)) {

                bwth = vm.getBw();
            } else {

                int sourceVmId;
                try {
                    sourceVmId = Integer.parseInt(site);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException(
                            "Replica site '" + site + "' is neither the source nor a VM identifier", e);
                }
                Host sourceHost = getVmAllocationPolicy().getHost(sourceVmId, userId);
                Vm sourceVm = sourceHost == null ? null : sourceHost.getVm(sourceVmId, userId);
                if (sourceVm == null) {
                    throw new IllegalStateException("Replica site VM " + sourceVmId + " is unavailable for input file '"
                            + file.getName() + "'");
                }
                bwth = Math.min(vm.getBw(), sourceVm.getBw());
            }
            if (bwth > maxBwth) {
                maxBwth = bwth;
            }
        }
        if (requiredFileStagein && maxBwth > 0.0) {
            return file.getSize() / (double) Consts.MILLION / maxBwth;
        }
        return 0.0;
    }

    @Override
    protected void processCloudletSubmit(SimEvent ev, boolean ack) {

        updateCloudletProcessing(true);

        try {
            Object payload = ev.getData();
            if (!(payload instanceof Job)) {
                throw new IllegalArgumentException("Cloudlet submission must contain a WorkflowSim Job");
            }
            Job job = (Job) payload;

            if (job.isFinished()) {
                returnFinishedJobToUser(job, ack);
                return;
            }

            CondorVM vm = resolveSubmittedJobVm(job);
            projectComputeExecutionLength(job, vm);
            applyCostModelResourceParameters(job, vm);

            if (job.getClassType() == ClassType.STAGE_IN.value) {
                stageInFile2FileSystem(job);
            }
            double fileTransferTime = resolveComputeJobTransferSeconds(job);
            submitJobToScheduler(job, vm, fileTransferTime, ack);
        } catch (Exception e) {
            throw new IllegalStateException(getName() + " could not submit a workflow Job", e);
        }
        checkCloudletCompletion();
    }

    private void returnFinishedJobToUser(Job job, boolean ack) {
        String name = CloudSim.getEntityName(job.getUserId());
        Log.printLine(getName() + ": Warning - Cloudlet #" + job.getCloudletId() + " owned by " + name
                + " is already completed/finished.");
        Log.printLine("Therefore, it is not being executed again");
        Log.printLine();

        if (ack) {
            int[] data = new int[3];
            data[0] = getId();
            data[1] = job.getCloudletId();
            data[2] = CloudSimTags.FALSE;

            int tag = CloudSimTags.CLOUDLET_SUBMIT_ACK;
            sendNow(job.getUserId(), tag, data);
        }

        sendNow(job.getUserId(), CloudSimTags.CLOUDLET_RETURN, job);

        return;
    }

    private CondorVM resolveSubmittedJobVm(Job job) {
        int userId = job.getUserId();
        int vmId = job.getVmId();
        Host host = getVmAllocationPolicy().getHost(vmId, userId);
        if (host == null) {
            throw new IllegalStateException(
                    "No host contains VM " + vmId + " for submitted Job " + job.getCloudletId());
        }
        Vm candidateVm = host.getVm(vmId, userId);
        if (!(candidateVm instanceof CondorVM)) {
            throw new IllegalStateException(
                    "Submitted Job " + job.getCloudletId() + " is assigned to an unavailable or non-Condor VM " + vmId);
        }
        CondorVM vm = (CondorVM) candidateVm;
        return vm;
    }

    private void projectComputeExecutionLength(Job job, CondorVM vm) {
        int vmId = job.getVmId();

        long computeLengthMi = job.getEffectiveExecutionLengthMi();
        if (job.getClassType() == ClassType.COMPUTE.value) {
            for (Task task : job.getTaskList()) {
                task.setEffectiveExecutionLengthMi(TaskExecutionModel.executionLengthMi(task, vmId, vm.getMips()));
            }
            if (job.getTaskList().size() == 1 && job.getTaskList().get(0).hasVmExecutionCostSeconds()) {
                computeLengthMi = job.getTaskList().get(0).getEffectiveExecutionLengthMi();
            }
            if (org.workflowsim.utils.CrchRegistry.isConfigured()) {
                computeLengthMi = projectCrchAttemptLength(job, vm);
            }
        }

        job.setEffectiveExecutionLengthMi(computeLengthMi);
        job.setCloudletLength(computeLengthMi);
    }

    private long projectCrchAttemptLength(Job job, CondorVM vm) {
        if (job.getTaskList().size() != 1) {
            throw new IllegalStateException("CRCH attempts must contain exactly one Task; Job " + job.getCloudletId()
                    + " has " + job.getTaskList().size());
        }
        Task task = job.getTaskList().get(0);
        long credited = 0L;
        long workMi = TaskExecutionModel.executionLengthMi(task, vm.getId(), vm.getMips());
        if (org.workflowsim.utils.CrchRegistry.hasRecovery(job.getCloudletId())) {
            org.workflowsim.utils.CrchRecovery recovery =
                    org.workflowsim.utils.CrchRegistry.getRecovery(job.getCloudletId());
            credited = recovery.creditOn(vm.getId());
            if (credited > 0L) {
                workMi = recovery.getRemainingWorkMi();
            }
        }
        org.workflowsim.utils.CrchConfig crch = org.workflowsim.utils.CrchRegistry.get();
        long checkpoints = org.workflowsim.utils.CrchCheckpointModel.checkpointCount(
                workMi, vm.getMips(), crch.getCheckpointIntervalSeconds());
        long attemptMi = org.workflowsim.utils.CrchCheckpointModel.attemptLengthMi(workMi, vm.getMips(), crch);
        job.setCrchAttemptPlan(credited, workMi, checkpoints);
        task.setEffectiveExecutionLengthMi(attemptMi);
        return attemptMi;
    }

    private void applyCostModelResourceParameters(Job job, CondorVM vm) {
        switch (Parameters.getCostModel()) {
            case DATACENTER:
                job.setResourceParameter(
                        getId(),
                        getCharacteristics().getCostPerSecond(),
                        getCharacteristics().getCostPerBw());
                break;
            case VM:
                job.setResourceParameter(getId(), vm.getCost(), vm.getCostPerBW());
                break;
            default:
                break;
        }
    }

    private double resolveComputeJobTransferSeconds(Job job) throws Exception {
        double fileTransferTime = 0.0;
        if (job.getClassType() == ClassType.COMPUTE.value) {
            if (dataMovementModel.usesCoherentDataflowRuntime()) {
                requireCoherentRuntime();
                fileTransferTime = 0.0;
            } else if (dataMovementModel.isPreExecutionTransferDelayV1()
                    || dataMovementModel.isPreExecutionTransferDelayWithContentionV1()
                    || dataMovementModel.isFatTreeContentionV1()) {

                fileTransferTime = 0.0;
            } else {
                DataTransferEstimate estimate = estimateDataStageInForComputeJob(job.getFileList(), job);
                fileTransferTime = estimate.getTransferSeconds();
                eventRecorder.record(
                        SimulationEventType.DATA_STAGE_IN_MODELED,
                        CloudSim.clock(),
                        job,
                        SimulationEventRecorder.attributes(
                                "modeledTransferSeconds",
                                fileTransferTime,
                                "requiredFileBytes",
                                estimate.getRequiredFileBytes(),
                                "modeledTransferFileCount",
                                estimate.getRequiredFileCount(),
                                "dataMovementModel",
                                dataMovementModel.getKind().name()));
            }
        }
        return fileTransferTime;
    }

    public double sharedEnvelopeInputReadSeconds(Job job) {
        if (job.getClassType() != ClassType.COMPUTE.value
                || ReplicaCatalog.getFileSystem() != ReplicaCatalog.FileSystem.SHARED
                || dataMovementModel.usesCoherentDataflowRuntime()
                || dataMovementModel.isPreExecutionTransferDelayV1()
                || dataMovementModel.isPreExecutionTransferDelayWithContentionV1()
                || dataMovementModel.isFatTreeContentionV1()) {
            return 0.0;
        }
        try {
            return estimateDataStageInForComputeJob(job.getFileList(), job).getTransferSeconds();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not estimate SHARED input read of Job " + job.getCloudletId(), exception);
        }
    }

    private void submitJobToScheduler(Job job, CondorVM vm, double fileTransferTime, boolean ack) {
        int vmId = job.getVmId();

        double envelopeMi = job.getCloudletLength() + vm.getMips() * fileTransferTime;
        if (!Double.isFinite(envelopeMi) || envelopeMi >= (double) Long.MAX_VALUE) {
            throw new IllegalArgumentException("Stage-in overflows the executable Job envelope");
        }
        TaskExecutionModel.requireRepresentableLength((long) envelopeMi, job.getNumberOfPes());
        CloudletScheduler scheduler = vm.getCloudletScheduler();
        long cloudletLengthBeforeStageIn = job.getCloudletLength();
        if (job.getClassType() == ClassType.COMPUTE.value && dataMovementModel.usesCoherentDataflowRuntime()) {
            requireCoherentRuntime().cpuStarted(job.getCloudletId(), coherentTaskIds(job), vmId, CloudSim.clock());
            sendNow(coherentEngineId, WorkflowSimTags.COHERENT_DATAFLOW_CHECK, null);
        }
        double estimatedFinishTime = scheduler.cloudletSubmit(job, fileTransferTime);

        if (!(estimatedFinishTime > 0.0) || Double.isInfinite(estimatedFinishTime)) {
            throw new IllegalStateException(getName()
                    + " received a Job submitted to a busy VM (cloudlet "
                    + job.getCloudletId() + ", VM " + vm.getId()
                    + "): WorkflowSim scheduling contract only dispatches Jobs"
                    + " to idle VMs; a busy-VM submission would anchor the task"
                    + " time window at 0.0 and corrupt failure attribution");
        }
        double effectiveStageInSeconds =
                effectiveCloudletStageInSeconds(job, vm, cloudletLengthBeforeStageIn, fileTransferTime);
        updateTaskExecTime(job, vm, effectiveStageInSeconds, fileTransferTime);
        runningAttemptCompletions.put(vmId, new double[] {
            job.getCloudletId(),
            SimulationTiming.earliestCloudletCompletionTime(
                    CloudSim.clock(), CloudSim.clock() + estimatedFinishTime, CloudSim.getMinTimeBetweenEvents())
        });
        if (job.hasFailureInstant()) {
            estimatedFinishTime = truncateAtFailure(job, vm, job.getCloudletLength() - cloudletLengthBeforeStageIn);
        }

        double completionTime = SimulationTiming.earliestCloudletCompletionTime(
                CloudSim.clock(), CloudSim.clock() + estimatedFinishTime, CloudSim.getMinTimeBetweenEvents());
        send(getId(), completionTime - CloudSim.clock(), CloudSimTags.VM_DATACENTER_EVENT);

        if (ack) {
            int[] data = new int[3];
            data[0] = getId();
            data[1] = job.getCloudletId();
            data[2] = CloudSimTags.TRUE;

            int tag = CloudSimTags.CLOUDLET_SUBMIT_ACK;
            sendNow(job.getUserId(), tag, data);
        }
    }

    public double noFailureCompletionSeconds(int vmId, int jobId) {
        double[] record = runningAttemptCompletions.get(vmId);
        if (record == null || (int) record[0] != jobId) {
            return Double.NaN;
        }
        return record[1];
    }

    private double truncateAtFailure(Job job, CondorVM vm, long stageInMi) {
        if (job.getTaskList().size() != 1) {
            throw new IllegalStateException("A failing attempt must contain exactly one Task under clustering NONE; "
                    + "Job " + job.getCloudletId() + " has " + job.getTaskList().size());
        }
        double computeStart = job.getTaskList().get(0).getExecStartTime();
        double executed = (job.getFailureInstantSeconds() - computeStart) * vm.getMips();
        long computeMi = Math.max(0L, (long) Math.ceil(executed - 1.0e-9));
        long length = Math.max(1L, stageInMi + computeMi);
        if (length > job.getCloudletLength()) {
            throw new IllegalStateException(
                    "Failure instant of Job " + job.getCloudletId() + " lies beyond its compute window");
        }
        job.setCloudletLength(length);
        return length / vm.getMips();
    }

    private double effectiveCloudletStageInSeconds(
            Job job, Vm vm, long cloudletLengthBeforeStageIn, double requestedStageInSeconds) {
        if (requestedStageInSeconds < 0.0
                || Double.isNaN(requestedStageInSeconds)
                || Double.isInfinite(requestedStageInSeconds)
                || vm.getMips() <= 0.0
                || Double.isNaN(vm.getMips())
                || Double.isInfinite(vm.getMips())) {
            throw new IllegalArgumentException("Requested stage-in and VM MIPS must be finite and valid");
        }
        long addedMi = job.getCloudletLength() - cloudletLengthBeforeStageIn;
        if (addedMi < 0L) {
            throw new IllegalStateException("Cloudlet scheduler shortened a Job while applying stage-in");
        }
        return addedMi / vm.getMips();
    }

    private void updateTaskExecTime(Job job, Vm vm, double effectiveStageInSeconds, double requestedStageInSeconds) {

        double startTime = job.getExecStartTime() + effectiveStageInSeconds;

        eventRecorder.record(
                SimulationEventType.JOB_EXECUTION_STARTED,
                CloudSim.clock(),
                job,
                SimulationEventRecorder.attributes(
                        "jobEnvelopeStartSeconds",
                        Double.valueOf(job.getExecStartTime()),
                        "effectiveDataStageSeconds",
                        Double.valueOf(effectiveStageInSeconds),
                        "requestedDataStageSeconds",
                        Double.valueOf(requestedStageInSeconds),
                        "modeledJobStartSeconds",
                        Double.valueOf(startTime)));
        boolean firstTask = true;

        Double assignedTierMips = job.getTierMips();
        double executionSpeedMips = assignedTierMips != null ? assignedTierMips.doubleValue() : vm.getMips();
        for (Task task : job.getTaskList()) {
            task.setExecStartTime(startTime);
            double taskRuntime = task.getEffectiveExecutionLengthMi() / executionSpeedMips;
            startTime += taskRuntime;

            task.setTaskFinishTime(startTime);
            if (job.getClassType() == ClassType.COMPUTE.value && !job.hasFailureInstant()) {
                double failure = failureInstant(task, job.getVmId(), task.getExecStartTime(), startTime);
                if (!Double.isNaN(failure)) {
                    job.setFailureInstantSeconds(failure);
                    task.setTaskFinishTime(failure);
                }
            }
            eventRecorder.record(
                    SimulationEventType.TASK_EXECUTION_MODELED,
                    CloudSim.clock(),
                    job,
                    SimulationEventRecorder.attributes(
                            "taskId",
                            task.getCloudletId(),
                            "taskStartTime",
                            task.getExecStartTime(),
                            "taskFinishTime",
                            task.getTaskFinishTime(),
                            "taskLengthMi",
                            task.getCloudletLength(),
                            "effectiveExecutionLengthMi",
                            task.getEffectiveExecutionLengthMi(),
                            "modeledStageInSecondsBeforeTask",
                            firstTask ? effectiveStageInSeconds : 0.0,
                            "requestedDataStageInSecondsForJob",
                            requestedStageInSeconds,
                            "taskTimingScope",
                            "MODEL_DERIVED_COMPUTE_WINDOW"));
            firstTask = false;
        }
    }

    private static double failureInstant(Task task, int vmId, double start, double finish) {
        try {
            return FailureGenerator.failureInstant(task, vmId, start, finish);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failure generation failed for Task " + task.getCloudletId() + " on VM " + vmId, exception);
        }
    }

    private static double realInputBytes(List<FileItem> files) {
        double result = 0.0;
        for (FileItem file : files) {
            if (file.isRealInputFile(files)) {
                result += file.getSize();
            }
        }
        return result;
    }

    private void stageInFile2FileSystem(Job job) {
        List<FileItem> fList = job.getFileList();

        for (FileItem file : fList) {
            switch (ReplicaCatalog.getFileSystem()) {
                case LOCAL:
                    ReplicaCatalog.addFileToStorage(file.getName(), this.getName());

                    break;

                case SHARED:
                    ReplicaCatalog.addFileToStorage(file.getName(), this.getName());
                    break;
                default:
                    break;
            }
        }
    }

    private DataTransferEstimate estimateDataStageInForComputeJob(List<FileItem> requiredFiles, Job job)
            throws Exception {
        if (dataMovementModel.usesCoherentDataflowRuntime()) {
            throw new IllegalStateException(
                    "Coherent V2 cannot fall through to execution-envelope transfer estimation");
        }
        int requiredFileCount = realInputFileCount(requiredFiles);
        double requiredBytes = realInputBytes(requiredFiles);
        if (dataMovementModel.isLegacyWorkflowsimV1()) {
            return new DataTransferEstimate(
                    legacyProcessDataStageInForComputeJob(requiredFiles, job), requiredBytes, requiredFileCount);
        }
        return fixedEndpointDataStageIn(requiredFiles, job, requiredBytes, requiredFileCount);
    }

    private static int realInputFileCount(List<FileItem> files) {
        int result = 0;
        for (FileItem file : files) {
            if (file.isRealInputFile(files)) {
                result++;
            }
        }
        return result;
    }

    private DataTransferEstimate fixedEndpointDataStageIn(
            List<FileItem> requiredFiles, Job job, double requiredBytes, int requiredFileCount) throws Exception {
        double time = 0.0;
        for (FileItem file : requiredFiles) {
            if (!file.isRealInputFile(requiredFiles)) {
                continue;
            }
            List siteList = ReplicaCatalog.getStorageList(file.getName());
            if (siteList == null || siteList.isEmpty()) {
                throw new IllegalStateException(
                        "Required input file '" + file.getName() + "' has no registered replica");
            }
            switch (ReplicaCatalog.getFileSystem()) {
                case SHARED:
                    time += fixedEndpointTransferSeconds(file, sharedStorageTransferRate(), "shared storage");
                    break;
                case LOCAL:
                    time += fixedEndpointLocalTransferSeconds(file, job, siteList);
                    break;
                default:
                    throw new IllegalStateException(
                            "Unsupported replica file system " + ReplicaCatalog.getFileSystem());
            }
        }
        return new DataTransferEstimate(time, requiredBytes, requiredFileCount);
    }

    private double fixedEndpointLocalTransferSeconds(FileItem file, Job job, List siteList) throws Exception {
        int destinationVmId = job.getVmId();
        int userId = job.getUserId();
        Vm destinationVm = requiredVm(destinationVmId, userId, "fixed local stage-in");
        String destination = Integer.toString(destinationVmId);
        double best = Double.POSITIVE_INFINITY;
        for (Object item : siteList) {
            if (!(item instanceof String)) {
                throw new IllegalStateException("Replica site is not a string for input file '" + file.getName() + "'");
            }
            String site = (String) item;
            if (destination.equals(site)) {
                return 0.0;
            }
            double endpointBandwidth;
            if (Parameters.SOURCE.equals(site)) {
                endpointBandwidth =
                        Math.min(dataMovementModel.getSourceEndpointBandwidthMbPerSecond(), destinationVm.getBw());
            } else if (getName().equals(site)) {
                endpointBandwidth = Math.min(sharedStorageTransferRate(), destinationVm.getBw());
            } else {
                int sourceVmId;
                try {
                    sourceVmId = Integer.parseInt(site);
                } catch (NumberFormatException exception) {
                    throw new IllegalStateException(
                            "Replica site '" + site + "' is neither the source, shared endpoint, nor a VM identifier",
                            exception);
                }
                Vm sourceVm = requiredVm(sourceVmId, userId, "fixed local stage-in source");
                endpointBandwidth = Math.min(sourceVm.getBw(), destinationVm.getBw());
            }
            best = Math.min(best, fixedEndpointTransferSeconds(file, endpointBandwidth, "replica " + site));
        }
        if (Double.isInfinite(best)) {
            throw new IllegalStateException(
                    "Required input file '" + file.getName() + "' has no usable fixed-endpoint replica");
        }
        ReplicaCatalog.addFileToStorage(file.getName(), destination);
        return best;
    }

    private Vm requiredVm(int vmId, int userId, String operation) {
        Host host = getVmAllocationPolicy().getHost(vmId, userId);
        if (host == null) {
            throw new IllegalStateException("No host contains VM " + vmId + " for " + operation);
        }
        Vm vm = host.getVm(vmId, userId);
        if (vm == null) {
            throw new IllegalStateException("No VM " + vmId + " is available for " + operation);
        }
        return vm;
    }

    private double sharedStorageTransferRate() {
        double maxRate = 0.0;
        for (Storage storage : getStorageList()) {
            maxRate = Math.max(maxRate, storage.getMaxTransferRate());
        }
        if (!(maxRate > 0.0) || Double.isInfinite(maxRate) || Double.isNaN(maxRate)) {
            throw new IllegalStateException("Shared storage has no finite positive transfer rate");
        }
        return maxRate;
    }

    private double fixedEndpointTransferSeconds(FileItem file, double endpointBandwidth, String endpoint) {
        double rate = Math.min(endpointBandwidth, dataMovementModel.getAccessLinkBandwidthMbPerSecond());
        if (!(rate > 0.0) || Double.isInfinite(rate) || Double.isNaN(rate)) {
            throw new IllegalStateException("Fixed endpoint " + endpoint
                    + " has no finite positive bottleneck bandwidth for input file '" + file.getName() + "'");
        }
        return dataMovementModel.getAccessLinkLatencySeconds() + file.getSize() / (double) Consts.MILLION / rate;
    }

    private double legacyProcessDataStageInForComputeJob(List<FileItem> requiredFiles, Job job) throws Exception {

        double time = estimateTransferSecondsForFiles(requiredFiles, job);
        registerStageInReplicasForComputeJob(job);
        return time;
    }

    @Override
    protected void updateCloudletProcessing() {
        updateCloudletProcessing(false);
    }

    private void updateCloudletProcessing(boolean executionSetChanging) {
        if (executionSetChanging
                || CloudSim.clock()
                        < SimulationTiming.initialProcessingThresholdSeconds(CloudSim.getMinTimeBetweenEvents())
                || CloudSim.clock() >= getLastProcessTime() + CloudSim.getMinTimeBetweenEvents()) {
            List<? extends Host> list = getVmAllocationPolicy().getHostList();
            double smallerTime = Double.MAX_VALUE;

            for (Host host : list) {

                double time = host.updateVmsProcessing(CloudSim.clock());

                if (time < smallerTime) {
                    smallerTime = time;
                }
            }

            if (smallerTime != Double.MAX_VALUE) {
                smallerTime = SimulationTiming.earliestCloudletCompletionTime(
                        CloudSim.clock(), smallerTime, CloudSim.getMinTimeBetweenEvents());
            }
            if (smallerTime != Double.MAX_VALUE) {
                schedule(getId(), (smallerTime - CloudSim.clock()), CloudSimTags.VM_DATACENTER_EVENT);
            }
            setLastProcessTime(CloudSim.clock());
        }
    }

    @Override
    protected void checkCloudletCompletion() {
        List<? extends Host> list = getVmAllocationPolicy().getHostList();
        for (Host host : list) {
            for (Vm vm : host.getVmList()) {
                while (vm.getCloudletScheduler().isFinishedCloudlets()) {
                    Cloudlet cl = vm.getCloudletScheduler().getNextFinishedCloudlet();
                    if (cl != null) {
                        if (cl instanceof Job && ((Job) cl).getClassType() == ClassType.COMPUTE.value) {

                            FailureGenerator.applySubmittedOutcome((Job) cl);
                        }
                        register(cl);

                        double[] running = runningAttemptCompletions.get(vm.getId());
                        if (running != null && (int) running[0] == cl.getCloudletId()) {
                            runningAttemptCompletions.remove(vm.getId());
                        }
                        sendNow(cl.getUserId(), CloudSimTags.CLOUDLET_RETURN, cl);
                    }
                }
            }
        }
    }

    private void register(Cloudlet cl) {
        if (!(cl instanceof Task)) {
            throw new IllegalArgumentException("Completed workflow cloudlet is not a Task");
        }
        if (cl instanceof Job
                && ((Job) cl).getClassType() == ClassType.COMPUTE.value
                && dataMovementModel.usesCoherentDataflowRuntime()) {
            Job job = (Job) cl;
            java.util.List<Boolean> successes = new ArrayList<Boolean>();
            for (Task task : job.getTaskList()) {
                successes.add(task.getCloudletStatus() == Cloudlet.SUCCESS);
            }
            requireCoherentRuntime()
                    .jobFinished(job.getCloudletId(), coherentTaskIds(job), job.getVmId(), successes, CloudSim.clock());
            sendNow(coherentEngineId, WorkflowSimTags.COHERENT_DATAFLOW_CHECK, null);
            return;
        }
        String storageLocation;
        switch (ReplicaCatalog.getFileSystem()) {
            case SHARED:
                storageLocation = this.getName();
                break;
            case LOCAL:
                int vmId = cl.getVmId();
                int userId = cl.getUserId();
                Host host = getVmAllocationPolicy().getHost(vmId, userId);
                if (host == null || !(host.getVm(vmId, userId) instanceof CondorVM)) {
                    throw new IllegalStateException("Completed workflow cloudlet " + cl.getCloudletId()
                            + " references unavailable local-storage VM " + vmId);
                }
                storageLocation = Integer.toString(vmId);
                break;
            default:
                throw new IllegalStateException("Unsupported replica file system " + ReplicaCatalog.getFileSystem());
        }

        if (cl instanceof Job && ((Job) cl).getClassType() == ClassType.COMPUTE.value) {
            for (Task task : ((Job) cl).getTaskList()) {
                registerSuccessfulTaskOutputs(task, storageLocation);
            }
            return;
        }
        registerSuccessfulTaskOutputs((Task) cl, storageLocation);
    }

    static int registerSuccessfulTaskOutputs(Task task, String storageLocation) {
        if (task == null || storageLocation == null || storageLocation.trim().isEmpty()) {
            throw new IllegalArgumentException("Task and non-empty output storage location are required");
        }
        if (task.getCloudletStatus() != Cloudlet.SUCCESS) {
            return 0;
        }
        int registered = 0;
        for (FileItem file : task.getFileList()) {
            if (file.getType() == FileType.OUTPUT) {
                ReplicaCatalog.addFileToStorage(file.getName(), storageLocation);
                registered++;
            }
        }
        return registered;
    }

    private static final class DataTransferEstimate {
        private final double transferSeconds;
        private final double requiredFileBytes;
        private final int requiredFileCount;

        private DataTransferEstimate(double transferSeconds, double requiredFileBytes, int requiredFileCount) {
            if (transferSeconds < 0.0
                    || requiredFileBytes < 0.0
                    || requiredFileCount < 0
                    || Double.isNaN(transferSeconds)
                    || Double.isInfinite(transferSeconds)
                    || Double.isNaN(requiredFileBytes)
                    || Double.isInfinite(requiredFileBytes)) {
                throw new IllegalArgumentException("Data transfer estimate must be finite and non-negative");
            }
            this.transferSeconds = transferSeconds;
            this.requiredFileBytes = requiredFileBytes;
            this.requiredFileCount = requiredFileCount;
        }

        private double getTransferSeconds() {
            return transferSeconds;
        }

        private double getRequiredFileBytes() {
            return requiredFileBytes;
        }

        private int getRequiredFileCount() {
            return requiredFileCount;
        }
    }
}
