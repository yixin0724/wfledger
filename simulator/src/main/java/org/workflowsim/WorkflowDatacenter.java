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

/**
 * Workflow-specific datacenter that layers {@link Job}, {@link CondorVM}, input file replica
 * and task-level timing evidence handling on top of the CloudSim {@link Datacenter}.
 *
 * <p>The submission lifecycle is: validate the event payload and the target VM; set the resource
 * billing parameters of the Job; for a stage-in Job register file replicas, for a compute Job
 * estimate the input transfer time from the {@link DataMovementModel}; then hand the Job to the
 * CloudletScheduler of the VM and schedule the next datacenter processing event. Completion
 * detection first decides the failure outcome of a compute Job, then registers output replicas
 * only for successful logical Tasks, and finally returns the completed Cloudlet to its user.
 *
 * <p>The class delegates shared-link contention of the selected model to the fluid engine; it
 * implements no physical storage capacity allocation and no retry policy. It fails fast and
 * throws {@link IllegalStateException} when the payload is not a {@link Job}, when the target VM
 * is missing or is not a {@link CondorVM}, or when replica and bandwidth constraints are invalid.
 * Recovery of a failed Job is the responsibility of the workflow engine and the reclustering
 * flow.
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class WorkflowDatacenter extends Datacenter {

    /** Run-local event evidence sink, disabled by default. */
    private SimulationEventRecorder eventRecorder = SimulationEventRecorder.disabled();
    /** Run-local input data movement model, defaulting to the historical WorkflowSim v1 semantics. */
    private DataMovementModel dataMovementModel = DataMovementModel.legacyWorkflowsimV1();
    /**
     * Fluid transfer contention engine under link contention models; created lazily, see
     * {@link #getTransferContentionEngine()}.
     */
    private TransferContentionEngine transferContentionEngine;
    /** Run-local opt-in; no global Parameters flag is consulted. */
    private NetworkEvidenceConfig networkEvidenceConfig = NetworkEvidenceConfig.off();
    /** Bounded early-admission metadata, allocated only when recording an enabled binding. */
    private Map<Long, NetworkFlowBinding> networkFlowBindings;
    /** Enabled-only aggregate input observations; no per-file history is retained. */
    private NetworkInputDemandTracker inputDemandTracker;
    /** Placed topology used by the Fat-tree link contention model (installed by the standard runner; may be null). */
    private org.workflowsim.network.FatTreeTopology fatTreeTopology;

    private org.workflowsim.data.v2.CoherentDataflowRuntime coherentDataflow;
    private int coherentEngineId = -1;
    private org.workflowsim.data.v2.DataflowStorageSpec sourceStorage;
    /** @param storage explicit bounded storage for the dedicated storage version */
    public void setSourceStorage(org.workflowsim.data.v2.DataflowStorageSpec storage) {
        if (storage == null || sourceStorage != null || !dataMovementModel.isCoherentStorageDataflowV3()) {
            throw new IllegalArgumentException("Storage spec requires one storage-V3 installation");
        }
        sourceStorage = storage;
    }

    /** @param runtime same run-owned coordinator as the engine/planner @param engineId owning workflow engine */
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
    /** Bind routes to actual created VM placements, never the logical producer's planned host. */
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
    /** Actual-created immutable compute options for the engine's opt-in destination binding. */
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
    /** @return independently versioned file lifecycle capture, null for OFF/V1 */
    public org.workflowsim.data.v2.FileLifecycleEvidence captureFileLifecycleEvidence() {
        return networkEvidenceConfig.getMode() == NetworkEvidenceConfig.Mode.FILE_LIFECYCLE_V2
                ? requireCoherentRuntime().captureEvidence()
                : null;
    }
    /** @return separately versioned storage capture, null for OFF/V1/V2 */
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

    /**
     * Create a workflow datacenter.
     *
     * <p>{@code storageList} is passed to the CloudSim superclass and also provides the maximum
     * transfer rate in the shared-storage input transfer model. When the shared-storage model is
     * used, the list should contain at least one storage device with a finite positive transfer
     * rate; that condition is validated when the transfer time is actually estimated, rather than
     * changing the historical initialization behavior at construction time.
     *
     * @param name datacenter entity name
     * @param characteristics datacenter resource and cost characteristics
     * @param vmAllocationPolicy VM-to-Host allocation policy
     * @param storageList available storage device list
     * @param schedulingInterval CloudSim datacenter scheduling interval
     * @throws Exception when the CloudSim datacenter initialization fails
     */
    public WorkflowDatacenter(
            String name,
            DatacenterCharacteristics characteristics,
            VmAllocationPolicy vmAllocationPolicy,
            List<Storage> storageList,
            double schedulingInterval)
            throws Exception {
        super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval);
    }

    /**
     * Install the event recorder used by this datacenter inside one simulation run.
     *
     * @param value non-null run-private recorder
     * @throws IllegalArgumentException when the recorder is {@code null}
     */
    public void setEventRecorder(SimulationEventRecorder value) {
        if (value == null) {
            throw new IllegalArgumentException("Event recorder cannot be null");
        }
        this.eventRecorder = value;
    }

    /**
     * Install the data movement time model for this run.
     *
     * <p>The model only determines the input stage-in transfer duration and the evidence records of
     * a compute Job; it does not change the logical registration rules of the replica catalog.
     *
     * @param value non-null run-private data movement model
     * @throws IllegalArgumentException when the model is {@code null}
     */
    public void setDataMovementModel(DataMovementModel value) {
        if (value == null) {
            throw new IllegalArgumentException("Data movement model cannot be null");
        }
        this.dataMovementModel = value;
    }

    /** Return the data movement time model of this run. */
    public DataMovementModel getDataMovementModel() {
        return dataMovementModel;
    }

    /**
     * Install run-local observation before the fluid engine is created.
     * @param value required immutable option; OFF preserves the old default
     * @throws IllegalArgumentException for a null option
     * @throws IllegalStateException after engine creation
     */
    public void setNetworkEvidenceConfig(NetworkEvidenceConfig value) {
        if (value == null) {
            throw new IllegalArgumentException("Network evidence configuration cannot be null");
        }
        if (transferContentionEngine != null) {
            throw new IllegalStateException("Network evidence must be configured before creating the transfer engine");
        }
        networkEvidenceConfig = value;
    }

    /** @return immutable run-local observation option */
    public NetworkEvidenceConfig getNetworkEvidenceConfig() {
        return networkEvidenceConfig;
    }

    /** Observe the locality decision already made by the original grouped-input loop. */
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

    /** Copy attribution after successful admission, before any later advance can remove it. */
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
        // Each START consumes at least one record. Later ordinals cannot be in a retained prefix.
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

    /**
     * Freeze current trace and bounded attribution without creating or advancing an engine.
     * @return immutable enabled evidence, or null when OFF
     * @throws IllegalStateException if retained admissions and bindings disagree
     */
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

    /**
     * Install the placed topology used by the Fat-tree link contention model.
     *
     * @param value non-null placed topology (hosts covering every Host of the platform)
     * @throws IllegalArgumentException when the topology is {@code null}
     */
    public void setFatTreeTopology(org.workflowsim.network.FatTreeTopology value) {
        if (value == null) {
            throw new IllegalArgumentException("Fat-tree topology cannot be null");
        }
        this.fatTreeTopology = value;
    }

    /** Return the installed Fat-tree topology; models without a declared topology return {@code null}. */
    public org.workflowsim.network.FatTreeTopology getFatTreeTopology() {
        return fatTreeTopology;
    }

    /**
     * Return (creating lazily) the fluid transfer contention engine used by the link contention
     * models; other data movement models return {@code null}.
     *
     * <p>Creation registers the network interface bandwidth of every VM as endpoint capacity (key
     * {@code "VM:<vmId>"}, capacity {@code vm.getBw() * 1000000} bytes/second); the SOURCE endpoint
     * registers no capacity (unbounded). The Fat-tree model additionally registers every topology
     * link capacity (a duplex link is split into per-direction resource keys, capacity = uniform
     * link bandwidth). The workflow engine drives the engine state in the data-ready and
     * advance-check events.</p>
     *
     * @return contention engine instance; {@code null} when the data movement model is not a link
     *         contention model
     */
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

    /**
     * Return the sequence of link resource keys occupied by a transfer from the source VM to the
     * destination VM under the Fat-tree topology; non-Fat-tree models return an empty list. A
     * transfer between VMs on the same host occupies no topology link.
     *
     * @param sourceVmId source VM identifier
     * @param destinationVmId destination VM identifier
     * @param userId user identifier
     * @return list of link resource keys of the deterministic route
     */
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

    /**
     * Pure estimate of the transfer seconds of a given set of real input files on the target VM of a
     * compute Job (no side effects).
     *
     * <p>The bandwidth rules match historical WorkflowSim v1: the shared file system takes the
     * maximum storage transfer rate, while the local file system takes, per file, the fastest
     * accessible replica ({@code SOURCE} site uses the target VM bandwidth, a VM site uses
     * {@code min(bw_src, bw_dst)}), and zero when the file is already on the target VM. This method
     * registers no replica; the pre-execution transfer delay model uses it to estimate at
     * data-ready time, and the planner mirror runtime uses it for its own estimate.</p>
     *
     * @param files candidate files (only real input files among them are counted)
     * @param job target compute Job
     * @return transfer seconds
     * @throws Exception when a required replica or VM cannot be resolved
     */
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

    /**
     * Determine whether a file is already local to the target Job (the same locality semantics as
     * {@link #estimateTransferSecondsForFiles}).
     *
     * <p>Under the LOCAL file system a replica list that contains the target VM means local with
     * zero transfer seconds; under the SHARED file system every real input file is charged at the
     * shared storage rate, so no local hit exists. R8 audit fix (F2): on this basis the contention
     * model creates flows only for bytes that really move, keeping the same semantics as the
     * seconds.</p>
     *
     * @param file real input file under test
     * @param job target compute Job
     * @return true when no transfer is needed (local hit)
     */
    public boolean isFileLocalForJob(FileItem file, Job job) {
        if (ReplicaCatalog.getFileSystem() == ReplicaCatalog.FileSystem.SHARED) {
            return false;
        }
        List siteList = ReplicaCatalog.getStorageList(file.getName());
        return siteList != null && siteList.contains(Integer.toString(job.getVmId()));
    }

    /**
     * Register replicas of all real input files of one compute Job on the target VM.
     *
     * <p>The pre-execution transfer delay model registers replicas only when the transfer
     * completes, so other Jobs inside the transfer window do not misjudge these files as locally
     * present. The shared file system has no VM-level replica registration, so the call is a
     * no-op.</p>
     *
     * @param job compute Job whose input transfer has completed
     * @throws Exception when the target VM cannot be resolved
     */
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

    /**
     * Estimate the transfer seconds of a single file to the target VM under the historical
     * WorkflowSim v1 local rules.
     *
     * @param file input file
     * @param siteList registered replica locations
     * @param job target compute Job
     * @return transfer seconds; 0 when the target VM already holds a replica
     * @throws Exception when the target or source VM cannot be resolved
     */
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
            // site denotes one registered replica location of this file.
            String site = (String) it.next();
            if (site.equals(this.getName())) {
                continue;
            }
            /** The file is already on the target VM, so no stage-in is required. */
            if (site.equals(Integer.toString(vmId))) {
                requiredFileStagein = false;
                break;
            }
            double bwth;
            if (site.equals(Parameters.SOURCE)) {
                // Under the historical rules, a source-to-VM transfer is limited only by the target VM bandwidth.
                bwth = vm.getBw();
            } else {
                // Under the historical rules, a VM-to-VM transfer is limited by the smaller of the two bandwidths.
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

    /**
     * Process the submission event of one workflow Job.
     *
     * <p>The event payload must be a {@link Job}, and the VM the Job points to must exist and be a
     * {@link CondorVM}. A completed Job is not executed again but is still returned to its user;
     * when acknowledgement is requested a failure acknowledgement is returned as well. A stage-in
     * Job only registers file locations, while a compute Job first estimates the input data
     * movement and records evidence, and only then calls the CloudletScheduler.
     *
     * <p>After a successful submission, if a finite completion time can be derived, a
     * {@link org.cloudbus.cloudsim.core.CloudSimTags#VM_DATACENTER_EVENT} is scheduled to itself;
     * a success acknowledgement is sent when acknowledgement is requested. Any validation, data
     * movement or VM access error is wrapped as an {@link IllegalStateException}, and no success
     * acknowledgement or Cloudlet return event is fabricated.
     *
     * @param ev CloudSim event whose payload is a Job
     * @param ack whether a submit acknowledgement must be sent to the user owning the Job
     * @pre ev != null
     * @post $none
     */
    @Override
    protected void processCloudletSubmit(SimEvent ev, boolean ack) {
        // A change to an execution set is a work-accounting boundary, not a periodic tick.
        // Settle existing work and idle clocks before adding the new Job, even within cadence.
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

            /** A stage-in Job registers file locations only; semantics follow the replica file system mode. */
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

    /** Submit response for an already completed Job (failure ack + return event; transcribed
     * verbatim from processCloudletSubmit). */
    private void returnFinishedJobToUser(Job job, boolean ack) {
        String name = CloudSim.getEntityName(job.getUserId());
        Log.printLine(getName() + ": Warning - Cloudlet #" + job.getCloudletId() + " owned by " + name
                + " is already completed/finished.");
        Log.printLine("Therefore, it is not being executed again");
        Log.printLine();

        // A completed Cloudlet is not executed again; the acknowledgement (when requested) and the
        // return event are still required, otherwise the upstream entity waiting on it blocks forever.
        if (ack) {
            int[] data = new int[3];
            data[0] = getId();
            data[1] = job.getCloudletId();
            data[2] = CloudSimTags.FALSE;

            // The acknowledgement event uses the unique CloudSim tag that corresponds to the submit operation.
            int tag = CloudSimTags.CLOUDLET_SUBMIT_ACK;
            sendNow(job.getUserId(), tag, data);
        }

        sendNow(job.getUserId(), CloudSimTags.CLOUDLET_RETURN, job);

        return;
    }

    /** Resolve the CondorVM referenced by a submitted Job (transcribed verbatim). */
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

    /** Project the heterogeneous cost matrix and set the job length (transcribed verbatim). */
    private void projectComputeExecutionLength(Job job, CondorVM vm) {
        int vmId = job.getVmId();
        /**
         * Task-by-VM heterogeneous cost matrix projection: STATIC dispatching converts the exact
         * execution seconds of a logical task into a job length here (MI = round(seconds * vm.mips)),
         * so that the runtime execution time matches the paper cost table (subject to CloudSim
         * integer MI rounding). The retry path goes through this method as well, so the conversion
         * applies automatically. Tasks that carry no projection keep the original MI/mips scaling
         * behavior.
         */
        long computeLengthMi = job.getEffectiveExecutionLengthMi();
        if (job.getClassType() == ClassType.COMPUTE.value) {
            for (Task task : job.getTaskList()) {
                task.setEffectiveExecutionLengthMi(TaskExecutionModel.executionLengthMi(task, vmId, vm.getMips()));
            }
            if (job.getTaskList().size() == 1 && job.getTaskList().get(0).hasVmExecutionCostSeconds()) {
                computeLengthMi = job.getTaskList().get(0).getEffectiveExecutionLengthMi();
            }
        }
        // Keep compute work separate from CloudSim's mutable, stage-in-inclusive envelope.
        // A retry starts here from pure compute work and resolves its matrix again.
        job.setEffectiveExecutionLengthMi(computeLengthMi);
        job.setCloudletLength(computeLengthMi);
    }

    /** Write the billing parameters according to the cost model (transcribed verbatim). */
    private void applyCostModelResourceParameters(Job job, CondorVM vm) {
        switch (Parameters.getCostModel()) {
            case DATACENTER:
                // Write the billing parameters of this datacenter or VM under the configured cost model.
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

    /** Compute the input data movement time of a Job as the CloudletScheduler transfer-time parameter. */
    private double resolveComputeJobTransferSeconds(Job job) throws Exception {
        double fileTransferTime = 0.0;
        if (job.getClassType() == ClassType.COMPUTE.value) {
            if (dataMovementModel.usesCoherentDataflowRuntime()) {
                requireCoherentRuntime();
                fileTransferTime = 0.0;
            } else if (dataMovementModel.isPreExecutionTransferDelayV1()
                    || dataMovementModel.isPreExecutionTransferDelayWithContentionV1()
                    || dataMovementModel.isFatTreeContentionV1()) {
                // The paper-semantics path (PRE_EXECUTION_TRANSFER_DELAY_V1), the link contention path
                // (R2 PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1) and the Fat-tree topology
                // contention path: the input transfer is already modeled by the engine at data-ready
                // time as a pre-execution delay (under contention models concurrent transfers share
                // the VM endpoint bandwidth fairly, and under Fat-tree the shared links along the
                // deterministic route are added on top), so the transfer window may overlap the busy
                // period of the target VM; the transfer is no longer folded into the execution
                // envelope here -- the VM is occupied only by the compute MI, and computation starts
                // at submit time. DATA_STAGE_IN_MODELED evidence and input replica registration are
                // handled by the engine inside the transfer window.
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

    /** Envelope check, scheduler submit, busy-VM contract check, task windows, completion event and
     * acknowledgement (transcribed verbatim). */
    private void submitJobToScheduler(Job job, CondorVM vm, double fileTransferTime, boolean ack) {
        int vmId = job.getVmId();
        // Validate the total envelope before CloudSim converts MI * PEs to long instructions.
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
        // Fail-fast: the busy-VM submission path. CloudSim returns 0.0 for a busy VM and keeps the
        // job QUEUED (execStartTime unset, retaining the default 0.0); if execution continued,
        // updateTaskExecTime would anchor the task time window at ~0.0 -- earlier than the
        // JOB_READY instant of that job, which forms a time inversion and makes FailureGenerator
        // attribute failures wrongly from the corrupted window (silent data corruption).
        // The standard WorkflowSim scheduling contract is that every algorithm dispatches jobs only
        // to idle VMs, so submitting to a busy VM is a contract violation and must fail explicitly
        // instead of degrading silently.
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
    /**
     * Convert the MI length that CloudSim actually appends to the Job into the effective stage-in
     * duration.
     *
     * <p>CloudSim represents {@code fileTransferTime} as an integer MI, so the effective duration is
     * {@code (Job length after submit - Job length before submit) / vmMips}, which may differ from
     * the requested data movement seconds by a rounding difference. The requested value is still
     * written to the evidence record separately, and the result of this method must not replace the
     * data model observation.
     *
     * @param job Job already passed to the CloudletScheduler
     * @param vm VM that executes the Job
     * @param cloudletLengthBeforeStageIn Job length before submit (MI)
     * @param requestedStageInSeconds transfer seconds requested by the data movement model
     * @return stage-in seconds actually injected by CloudSim
     * @throws IllegalArgumentException when the requested time or the VM MIPS is invalid
     * @throws IllegalStateException when the scheduler unexpectedly shortens the Job length
     */
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

    /**
     * Derive the contiguous compute time window of every original Task inside a Job from the model,
     * and write the task-level evidence events.
     *
     * <p>The first Task starts at the execution start time of the Job plus the effective stage-in
     * duration, and subsequent Tasks advance serially in order by {@code taskLengthMi /
     * executionSpeed}. What is updated here is the task-level model window; CloudSim does not allow
     * the real finish time of the Job to be rewritten at this point, so the two must not be
     * conflated into one piece of execution evidence.</p>
     *
     * <p>BAT-D6 (2026-10-29): the execution speed is the frequency-tier speed assigned when this
     * Job is dispatched ({@code job.getTierMips()}, tiers are unique by MIPS); a Job without a tier
     * (tierless VM of the v1 platform / hybrid platform, non-compute bookkeeping) keeps the VM
     * declared speed -- strictly the same source as the Job length scaling of
     * {@code WorkflowScheduler.assignFrequencyTier} (work unchanged, duration = work/tier speed).
     * The previous convention (always the VM speed) produced, when the tier speed differs from the
     * VM base speed, out-of-window task windows where the Task finishes after its Job -- the
     * independent Python audit of {@code task-window-within-job}/{@code cpu-lower-bound} catches it
     * immediately (the D2 missed gap, not retainable behavior).</p>
     *
     * @param job submitted clustered Job
     * @param vm executing VM
     * @param effectiveStageInSeconds stage-in seconds actually in effect in CloudSim
     * @param requestedStageInSeconds stage-in seconds requested by the data movement model
     */
    private void updateTaskExecTime(Job job, Vm vm, double effectiveStageInSeconds, double requestedStageInSeconds) {
        // CloudSim converts fileTransferTime into appended integer MI; the task time window starts
        // after that effective duration, while the original requested value is preserved separately
        // as a data movement model observation.
        double startTime = job.getExecStartTime() + effectiveStageInSeconds;
        // F-007 contract revision (2026-10-23, user-adjudicated): record the execution start of
        // this Job instance ahead of its TASK_EXECUTION_MODELED family, carrying the envelope
        // start and both data-stage seconds so the decision-to-start / waiting families stay
        // recomputable from the event stream without relying on exactJobTiming luck.
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
        // BAT-D6 (2026-10-29): the execution speed of the task-level model window is the assigned tier
        // speed of this Job (tierless = VM declared speed) -- the same convention as the scheduler
        // scaling of the Job cloudlet length, so the task windows stay inside the actual execution
        // window of the Job (work keeps the declared MI, duration = work/tier speed).
        Double assignedTierMips = job.getTierMips();
        double executionSpeedMips = assignedTierMips != null ? assignedTierMips.doubleValue() : vm.getMips();
        for (Task task : job.getTaskList()) {
            task.setExecStartTime(startTime);
            double taskRuntime = task.getEffectiveExecutionLengthMi() / executionSpeedMips;
            startTime += taskRuntime;
            // CloudSim does not support rewriting the Job finish time here, so only the Task-level model
            // finish instant is recorded.
            task.setTaskFinishTime(startTime);
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

    /** Return the total bytes of the real input files in the list; outputs of the same Job are not counted. */
    private static double realInputBytes(List<FileItem> files) {
        double result = 0.0;
        for (FileItem file : files) {
            if (file.isRealInputFile(files)) {
                result += file.getSize();
            }
        }
        return result;
    }

    /**
     * Register file replicas for a stage-in Job.
     *
     * <p>Regardless of whether this run uses the local or the shared file system, the current
     * implementation registers the file in {@link ReplicaCatalog} under the datacenter name. The
     * operation updates the logical replica catalog; it allocates no physical storage capacity and
     * copies no file content. The real data movement duration is modeled separately when a compute
     * Job is submitted.
     *
     * @param job stage-in Job
     * @pre $none
     * @post $none
     */
    private void stageInFile2FileSystem(Job job) {
        List<FileItem> fList = job.getFileList();

        for (FileItem file : fList) {
            switch (ReplicaCatalog.getFileSystem()) {
                    /** The local file system also uses the datacenter name as the logical replica location. */
                case LOCAL:
                    ReplicaCatalog.addFileToStorage(file.getName(), this.getName());
                    /** Physical ClusterStorage writes are still not wired in; kept as a placeholder for future work. */
                    // ClusterStorage storage = (ClusterStorage) getStorageList().get(0);
                    // storage.addFile(file);
                    break;
                    /** The shared file system likewise registers at the shared endpoint of the current datacenter. */
                case SHARED:
                    ReplicaCatalog.addFileToStorage(file.getName(), this.getName());
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Stage-in extension point kept for historical subclasses; returns the estimated transfer
     * seconds of one compute Job.
     *
     * <p>The standard run path obtains seconds, real input bytes and file count from the more
     * complete {@link DataTransferEstimate}, so that the evidence record covers every quantity; old
     * subclasses that override this method still keep their original calling convention.
     *
     * @param requiredFiles candidate input files
     * @param job compute Job about to execute
     * @return estimated data stage-in seconds
     * @throws Exception when a replica or VM cannot be resolved
     */
    protected double processDataStageInForComputeJob(List<FileItem> requiredFiles, Job job) throws Exception {
        return estimateDataStageInForComputeJob(requiredFiles, job).getTransferSeconds();
    }

    /**
     * Compute the input transfer estimate of one Job from the current data movement model.
     *
     * <p>Both modes count only files for which {@link FileItem#isRealInputFile(List)} is true. The
     * historical model keeps the WorkflowSim v1 bandwidth selection rules; the fixed-endpoint model
     * accumulates {@code latency + bytes / (1,000,000 * bottleneckRate)} per file and models neither
     * concurrency nor shared-link contention.
     *
     * @param requiredFiles candidate input files
     * @param job target compute Job
     * @return immutable estimate carrying time, byte count and file count
     * @throws Exception when a required replica or VM cannot be resolved
     */
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

    /** Return the number of real input files; outputs produced inside the same Job are not counted. */
    private static int realInputFileCount(List<FileItem> files) {
        int result = 0;
        for (FileItem file : files) {
            if (file.isRealInputFile(files)) {
                result++;
            }
        }
        return result;
    }

    /**
     * Accumulate the transfer duration of every real input file under the fixed-endpoint,
     * contention-free model.
     *
     * <p>The shared file system uses the maximum transfer rate of the shared storage; the local
     * file system selects the shortest single-file transfer time over the replicas reachable from
     * the target VM. Each file is added independently, so parallel transfers, cache eviction and
     * shared-link contention are not expressed.
     *
     * @param requiredFiles candidate input files
     * @param job target compute Job
     * @param requiredBytes already counted total bytes of real input
     * @param requiredFileCount already counted number of real input files
     * @return transfer estimate of the fixed-endpoint model
     * @throws Exception when a replica or the target VM cannot be resolved
     */
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

    /**
     * Select the shortest single-file transfer time over the replicas reachable from the target VM
     * for the local file system.
     *
     * <p>If the target VM already holds a replica, zero is returned immediately; a source endpoint,
     * the shared endpoint and another VM replica are each evaluated at the available endpoint
     * bandwidth, and the file is registered on the target VM only after the minimum is taken. A
     * replica site that is not a string or cannot be parsed as a valid endpoint is treated as a
     * configuration error and fails.
     *
     * @param file input file
     * @param job target compute Job
     * @param siteList registered replica locations
     * @return fixed-endpoint transfer seconds of the selected replica
     * @throws Exception when the target or source VM cannot be resolved
     */
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

    /**
     * Look up a VM of one user inside this datacenter, and fail fast with an operation-scoped
     * exception when it is missing.
     */
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

    /**
     * 返回共享存储列表中的最大有限正传输率。
     *
     * <p>这是固定端点模型的受控简化：它不选择具体存储设备，也不模拟多文件竞争。
     */
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

    /**
     * 计算固定端点模型下单文件传输秒数。
     *
     * <p>有效带宽为端点带宽和访问链路带宽的较小值，公式为
     * {@code accessLinkLatencySeconds + fileSizeBytes / (1,000,000 * effectiveRateMbPerSecond)}。
     */
    private double fixedEndpointTransferSeconds(FileItem file, double endpointBandwidth, String endpoint) {
        double rate = Math.min(endpointBandwidth, dataMovementModel.getAccessLinkBandwidthMbPerSecond());
        if (!(rate > 0.0) || Double.isInfinite(rate) || Double.isNaN(rate)) {
            throw new IllegalStateException("Fixed endpoint " + endpoint
                    + " has no finite positive bottleneck bandwidth for input file '" + file.getName() + "'");
        }
        return dataMovementModel.getAccessLinkLatencySeconds() + file.getSize() / (double) Consts.MILLION / rate;
    }

    /**
     * 保留 WorkflowSim v1 的历史阶段输入估计行为。
     *
     * <p>共享文件系统取存储列表中的最大传输率；本地文件系统优先识别已在目标 VM 上的副本，
     * 否则从可用来源中取最大带宽。该兼容路径不加入访问链路延迟、不模拟竞争或容量，且在
     * 估算完成后只更新逻辑副本目录；不应将其解释为物理网络拓扑模型。
     *
     * @param requiredFiles 候选输入文件
     * @param job 目标计算 Job
     * @return 历史规则得到的传输秒数
     * @throws Exception 当副本或 VM 解析失败时抛出
     */
    private double legacyProcessDataStageInForComputeJob(List<FileItem> requiredFiles, Job job) throws Exception {
        // 先按历史带宽规则估算全部真实输入文件的传输秒数，再把输入副本登记到目标 VM。
        // 估算与登记的先后顺序不影响结果：单个文件的估算只读取该文件的副本目录。
        double time = estimateTransferSecondsForFiles(requiredFiles, job);
        registerStageInReplicasForComputeJob(job);
        return time;
    }

    /**
     * 推进所有 Host/VM 的 Cloudlet 处理，并在可预测完成时刻安排下一次数据中心事件。
     *
     * <p>启动阶段保留历史的 {@code 0.111} 时间阈值，确保初始处理循环不会被 CloudSim 的最小
     * 事件间隔跳过；之后只在满足最小事件间隔时更新。该阈值是仿真事件兼容条件，不是任务运行
     * 时间或数据移动模型的一部分。
     */
    @Override
    protected void updateCloudletProcessing() {
        updateCloudletProcessing(false);
    }

    /** Settle work before execution-set changes; cadence limits periodic updates only. */
    private void updateCloudletProcessing(boolean executionSetChanging) {
        if (executionSetChanging
                || CloudSim.clock() < 0.111
                || CloudSim.clock() >= getLastProcessTime() + CloudSim.getMinTimeBetweenEvents()) {
            List<? extends Host> list = getVmAllocationPolicy().getHostList();
            double smallerTime = Double.MAX_VALUE;
            // 遍历每个 Host，要求其 VM 推进到当前仿真时刻。
            for (Host host : list) {
                // 通知 Host 下的 VM 更新执行状态。
                double time = host.updateVmsProcessing(CloudSim.clock());
                // 汇总最早的下一次 Cloudlet 完成预测时刻。
                if (time < smallerTime) {
                    smallerTime = time;
                }
            }
            // 规范化为满足 CloudSim 最小事件间隔的完成时刻。
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

    /**
     * 取出所有 VM 调度器已完成的 Cloudlet，并将其返回给所属用户。
     *
     * <p>每个完成的 compute Job 会先按照其已记录的 Task 执行时间窗判定故障，再只登记
     * 成功 Task 的输出文件副本。登记是逻辑目录更新，不代表物理数据复制或容量校验；失败
     * Task 的输出不会进入目录。完成结果随后才通过 {@code CLOUDLET_RETURN} 返回给调度器。
     *
     * @pre $none
     * @post $none
     */
    @Override
    protected void checkCloudletCompletion() {
        List<? extends Host> list = getVmAllocationPolicy().getHostList();
        for (Host host : list) {
            for (Vm vm : host.getVmList()) {
                while (vm.getCloudletScheduler().isFinishedCloudlets()) {
                    Cloudlet cl = vm.getCloudletScheduler().getNextFinishedCloudlet();
                    if (cl != null) {
                        if (cl instanceof Job && ((Job) cl).getClassType() == ClassType.COMPUTE.value) {
                            // 必须在登记输出副本前完成故障判定，避免失败尝试污染副本目录。
                            FailureGenerator.generate((Job) cl);
                        }
                        register(cl);
                        sendNow(cl.getUserId(), CloudSimTags.CLOUDLET_RETURN, cl);
                    }
                }
            }
        }
    }
    /**
     * 将一个已完成 {@link Task}（包括其子类 {@link Job}）的成功输出登记为可用副本。
     *
     * <p>compute Job 按其包含的逻辑 Task 分别提交，只有状态为 {@link Cloudlet#SUCCESS}
     * 的 Task 输出可见。这样可支持选择性重试：同一聚类 Job 中已经成功的 Task 可保留产物，
     * 失败 Task 的产物则不会成为下游输入。共享文件系统登记数据中心名称，本地文件系统登记
     * 执行 VM ID。该方法假定调用者已经验证 Cloudlet 属于 Task 层次；缺失 Host/VM 仍按原有
     * 行为在访问时失败。
     *
     * @param cl 已完成的工作流 Cloudlet
     */
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

    /**
     * 将一个逻辑 Task 的输出提交到已确定的逻辑位置。
     *
     * <p>该方法是 package-private，以便核心语义测试直接验证失败 Task 不会污染副本目录。
     * 调用方必须已经完成该 Task 的最终状态判定。</p>
     *
     * @param task 已完成并已判定状态的逻辑 Task
     * @param storageLocation 输出副本的逻辑位置
     * @return 新提交的输出文件数；失败 Task 时为零
     */
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

    /** 在传输提交、事件证据和聚合指标之间共享的不可变数据移动估计。 */
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
