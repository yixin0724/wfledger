package org.workflowsim.experiment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowInputReport;
import org.workflowsim.data.NetworkRunEvidence;
import org.workflowsim.planning.SharedStorageDagPlanTrace;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.SimulationConfig;

/** Immutable evidence record of one completed WorkflowSim run. */
public final class SimulationReport {

    private final SimulationConfig config;
    private final PlatformProfile platform;
    private final double makespan;
    private final List<InputArtifact> inputs;
    private final List<WorkflowInputReport> inputReports;
    private final List<JobOutcome> jobs;
    private final List<TaskOutcome> tasks;
    private final Map<Integer, VmSummary> vmSummaries;
    private final Map<Integer, Integer> actualVmHostAssignments;
    private final int successfulJobs;
    private final int failedJobs;
    private final List<SimulationEvent> events;
    private final EnergyAccounting energyAccounting;
    private final CarbonAccounting carbonAccounting;
    private final WorkflowProfile workflowProfile;
    private final SimulationMetrics metrics;
    private final SharedStorageDagPlanTrace sharedStorageDagPlanTrace;
    private final NetworkRunEvidence networkEvidence;
    private final transient org.workflowsim.data.v2.FileLifecycleEvidence fileLifecycleEvidence;
    private final transient com.google.gson.JsonObject dataflowPlan;
    private final transient org.workflowsim.data.v2.StorageLifecycleEvidence storageLifecycleEvidence;
    private final List<WorkflowOutcome> workflowOutcomes;
    private final List<TaskNode> workflowGraph;
    private final transient List<DataflowComputeRequest> dataflowComputeRequests;

    private SimulationReport(
            SimulationConfig config,
            PlatformProfile platform,
            double makespan,
            List<InputArtifact> inputs,
            List<WorkflowInputReport> inputReports,
            List<JobOutcome> jobs,
            List<TaskOutcome> tasks,
            Map<Integer, VmSummary> vmSummaries,
            Map<Integer, Integer> actualVmHostAssignments,
            int successfulJobs,
            int failedJobs,
            List<SimulationEvent> events,
            List<Task> sourceTasks,
            SharedStorageDagPlanTrace sharedStorageDagPlanTrace,
            NetworkRunEvidence networkEvidence,
            org.workflowsim.data.v2.FileLifecycleEvidence fileLifecycleEvidence,
            com.google.gson.JsonObject dataflowPlan,
            org.workflowsim.data.v2.StorageLifecycleEvidence storageLifecycleEvidence,
            List<DataflowComputeRequest> computeRequests) {
        this.config = config;
        this.platform = platform;
        this.makespan = makespan;
        this.inputs = Collections.unmodifiableList(new ArrayList<>(inputs));
        this.inputReports = Collections.unmodifiableList(new ArrayList<>(inputReports));
        this.jobs = Collections.unmodifiableList(new ArrayList<>(jobs));
        this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
        this.vmSummaries = Collections.unmodifiableMap(new LinkedHashMap<>(vmSummaries));
        this.actualVmHostAssignments =
                Collections.unmodifiableMap(new LinkedHashMap<Integer, Integer>(actualVmHostAssignments));
        this.successfulJobs = successfulJobs;
        this.failedJobs = failedJobs;
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.sharedStorageDagPlanTrace = sharedStorageDagPlanTrace;
        this.networkEvidence = networkEvidence;
        this.fileLifecycleEvidence = fileLifecycleEvidence;
        this.dataflowPlan = dataflowPlan == null ? null : dataflowPlan.deepCopy();
        this.storageLifecycleEvidence = storageLifecycleEvidence;
        this.workflowProfile = WorkflowProfile.fromTasks(sourceTasks);
        Map<Integer, TaskNode> graph = new TreeMap<Integer, TaskNode>();
        for (Task task : sourceTasks) {
            graph.put(task.getCloudletId(), new TaskNode(task));
        }
        this.workflowGraph = Collections.unmodifiableList(new ArrayList<TaskNode>(graph.values()));
        this.dataflowComputeRequests =
                computeRequests == null ? null : Collections.unmodifiableList(new ArrayList<>(computeRequests));
        this.metrics = SimulationMetrics.calculate(
                makespan, this.jobs, this.vmSummaries, this.events, this.tasks, sourceTasks, config, platform);
        this.energyAccounting = EnergyAccounting.fromFrozenMetrics(
                makespan, this.metrics.getVmMetrics(), this.metrics.getPerTierBusySecondsByVm(), platform);
        // BAT-C1 (2026-10-28): the two carbon models are mutually exclusive; undeclared -> null
        // (a legal default, not all zeros).
        if (platform.getVms() != null && anyCarbonIntensity(platform)) {
            if (platform.getCarbonWindows() != null
                    && !platform.getCarbonWindows().isEmpty()) {
                throw new IllegalArgumentException("Both carbon models declared (per-VM intensity AND carbonWindows) —"
                        + " the two accountings are mutually exclusive by contract (BAT-C1)");
            }
            this.carbonAccounting = CarbonAccounting.intensityModel(platform, this.energyAccounting, makespan);
        } else if (platform.getCarbonWindows() != null
                && !platform.getCarbonWindows().isEmpty()) {
            this.carbonAccounting = CarbonAccounting.budgetModel(this, platform);
        } else {
            this.carbonAccounting = null;
        }
        this.workflowOutcomes = computeWorkflowOutcomes();
    }

    /**
     * R5 dynamic arrival: compute the arrival, completion, and flow-time evidence of every
     * workflow by input index.
     *
     * <p>Task identifiers are allocated contiguously across inputs, so the task interval of an
     * input is derived from the taskCount of its input report; flow time is the latest
     * completion instant over all successful tasks of that workflow minus its configured
     * submission instant. Flow time is {@code NaN} when there is no successful task.</p>
     */
    private List<WorkflowOutcome> computeWorkflowOutcomes() {
        List<Double> arrivals = config.getWorkflowArrivalSeconds();
        List<WorkflowOutcome> outcomes = new ArrayList<>();
        int firstTaskId = 1;
        for (int i = 0; i < inputReports.size(); i++) {
            int taskCount = inputReports.get(i).getTaskCount();
            int lastTaskId = firstTaskId + taskCount - 1;
            double arrivalSecond = (arrivals != null && i < arrivals.size()) ? arrivals.get(i) : 0.0;
            double lastSuccessFinishSecond = Double.NaN;
            for (TaskOutcome task : tasks) {
                if (task.getTaskId() >= firstTaskId
                        && task.getTaskId() <= lastTaskId
                        && task.getTaskStatus() == Cloudlet.SUCCESS
                        && (Double.isNaN(lastSuccessFinishSecond) || task.getFinishTime() > lastSuccessFinishSecond)) {
                    lastSuccessFinishSecond = task.getFinishTime();
                }
            }
            outcomes.add(new WorkflowOutcome(
                    i,
                    config.getWorkflowPaths().get(i),
                    arrivalSecond,
                    firstTaskId,
                    lastTaskId,
                    taskCount,
                    lastSuccessFinishSecond,
                    Double.isNaN(lastSuccessFinishSecond) ? Double.NaN : lastSuccessFinishSecond - arrivalSecond));
            firstTaskId = lastTaskId + 1;
        }
        return Collections.unmodifiableList(outcomes);
    }

    static SimulationReport capture(
            SimulationConfig config,
            PlatformProfile platform,
            double makespan,
            List<WorkflowInputReport> inputReports,
            List<Job> completedJobs,
            List<SimulationEvent> events,
            List<Task> sourceTasks,
            SharedStorageDagPlanTrace sharedStorageDagPlanTrace,
            Map<Integer, Integer> actualVmHostAssignments,
            NetworkRunEvidence networkEvidence)
            throws IOException {
        return capture(
                config,
                platform,
                makespan,
                inputReports,
                completedJobs,
                events,
                sourceTasks,
                sharedStorageDagPlanTrace,
                actualVmHostAssignments,
                networkEvidence,
                null,
                null);
    }

    static SimulationReport capture(
            SimulationConfig config,
            PlatformProfile platform,
            double makespan,
            List<WorkflowInputReport> inputReports,
            List<Job> completedJobs,
            List<SimulationEvent> events,
            List<Task> sourceTasks,
            SharedStorageDagPlanTrace sharedStorageDagPlanTrace,
            Map<Integer, Integer> actualVmHostAssignments,
            NetworkRunEvidence networkEvidence,
            org.workflowsim.data.v2.FileLifecycleEvidence fileLifecycleEvidence,
            com.google.gson.JsonObject dataflowPlan)
            throws IOException {
        return capture(
                config,
                platform,
                makespan,
                inputReports,
                completedJobs,
                events,
                sourceTasks,
                sharedStorageDagPlanTrace,
                actualVmHostAssignments,
                networkEvidence,
                fileLifecycleEvidence,
                dataflowPlan,
                null);
    }

    static SimulationReport capture(
            SimulationConfig config,
            PlatformProfile platform,
            double makespan,
            List<WorkflowInputReport> inputReports,
            List<Job> completedJobs,
            List<SimulationEvent> events,
            List<Task> sourceTasks,
            SharedStorageDagPlanTrace sharedStorageDagPlanTrace,
            Map<Integer, Integer> actualVmHostAssignments,
            NetworkRunEvidence networkEvidence,
            org.workflowsim.data.v2.FileLifecycleEvidence fileLifecycleEvidence,
            com.google.gson.JsonObject dataflowPlan,
            org.workflowsim.data.v2.StorageLifecycleEvidence storageLifecycleEvidence)
            throws IOException {
        if (inputReports == null
                || inputReports.size() != config.getWorkflowPaths().size()) {
            throw new IllegalStateException("Completed simulation has "
                    + (inputReports == null ? 0 : inputReports.size())
                    + " parsed input report(s) for " + config.getWorkflowPaths().size()
                    + " configured input(s)");
        }
        if (actualVmHostAssignments == null
                || actualVmHostAssignments.size() != platform.getVms().size()) {
            throw new IllegalStateException("Completed simulation has incomplete VM-to-Host allocation evidence");
        }
        List<InputArtifact> inputs = new ArrayList<>();
        for (String path : config.getWorkflowPaths()) {
            inputs.add(InputArtifact.fromPath(path));
        }

        List<JobOutcome> jobs = new ArrayList<>();
        List<TaskOutcome> tasks = new ArrayList<>();
        Map<Integer, MutableVmSummary> mutableVmSummaries = new TreeMap<>();
        for (PlatformProfile.VmSpec vm : platform.getVms()) {
            mutableVmSummaries.put(vm.getId(), new MutableVmSummary(vm.getId()));
        }
        OutcomeCounts counts = accumulateJobOutcomes(completedJobs, jobs, tasks, mutableVmSummaries);
        int successes = counts.successes;
        int failures = counts.failures;
        Map<Integer, VmSummary> vmSummaries = new LinkedHashMap<>();
        for (Map.Entry<Integer, MutableVmSummary> entry : mutableVmSummaries.entrySet()) {
            vmSummaries.put(entry.getKey(), entry.getValue().freeze());
        }
        List<DataflowComputeRequest> computeRequests = buildComputeRequests(config, completedJobs);
        return new SimulationReport(
                config,
                platform,
                makespan,
                inputs,
                inputReports,
                jobs,
                tasks,
                vmSummaries,
                actualVmHostAssignments,
                successes,
                failures,
                events == null ? Collections.<SimulationEvent>emptyList() : events,
                sourceTasks,
                sharedStorageDagPlanTrace,
                networkEvidence,
                fileLifecycleEvidence,
                dataflowPlan,
                storageLifecycleEvidence,
                computeRequests);
    }

    /** Job result aggregation stage of capture(): deduplication, success/failure counting, VM summary
     * accumulation (verbatim move of the former in-method code). */
    private static OutcomeCounts accumulateJobOutcomes(
            List<Job> completedJobs,
            List<JobOutcome> jobs,
            List<TaskOutcome> tasks,
            Map<Integer, MutableVmSummary> mutableVmSummaries) {
        OutcomeCounts counts = new OutcomeCounts();
        Set<Integer> seenJobIds = new HashSet<>();
        for (Job job : completedJobs) {
            JobOutcome outcome = JobOutcome.fromJob(job);
            if (!seenJobIds.add(outcome.getJobId())) {
                throw new IllegalStateException("Completed simulation contains duplicate Job ID " + outcome.getJobId());
            }
            jobs.add(outcome);
            tasks.addAll(TaskOutcome.fromJob(job));
            if (outcome.getStatus() == Cloudlet.SUCCESS) {
                counts.successes++;
            } else if (outcome.getStatus() == Cloudlet.FAILED) {
                counts.failures++;
            }
            MutableVmSummary summary = mutableVmSummaries.get(outcome.getVmId());
            if (summary == null) {
                throw new IllegalStateException(
                        "Completed job " + outcome.getJobId() + " reports undeclared VM " + outcome.getVmId());
            }
            summary.add(outcome);
        }
        return counts;
    }

    /** Online compute request assembly stage of capture() (deterministic TreeMap order, verbatim move of
     * the former in-method code). */
    private static List<DataflowComputeRequest> buildComputeRequests(SimulationConfig config, List<Job> completedJobs) {
        if (!config.getDataflowAssignmentConfig().isEnabled()) {
            return null;
        }
        Map<Integer, DataflowComputeRequest> ordered = new TreeMap<>();
        for (Job job : completedJobs) {
            if (job.getClassType() == org.workflowsim.utils.Parameters.ClassType.COMPUTE.value) {
                if (job.getTaskList().size() != 1) {
                    throw new IllegalStateException("Online compute request must remain singleton");
                }
                DataflowComputeRequest request =
                        new DataflowComputeRequest(job, job.getTaskList().get(0));
                if (ordered.put(job.getCloudletId(), request) != null) {
                    throw new IllegalStateException("Duplicate online compute attempt");
                }
            }
        }
        return new ArrayList<>(ordered.values());
    }

    /** Private mutable accumulation structure for the capture() counts (verbatim move of the former
     * in-method code). */
    private static final class OutcomeCounts {
        private int successes;
        private int failures;
    }

    public SimulationConfig getConfig() {
        return config;
    }

    public PlatformProfile getPlatform() {
        return platform;
    }
    /**
     * Return the CloudSim simulation end clock kept for compatibility with the historical API.
     *
     * <p>The value may include the workflow-engine post-delay after the last Job returns.
     * Research code that needs the completion instant of the successful Job envelope of the
     * last logical Task must use {@link #getLogicalTaskCompletionSeconds()}.</p>
     *
     * @return simulation end time in simulated seconds
     */
    public double getMakespan() {
        return makespan;
    }
    /**
     * Return the CloudSim simulation end time of this run.
     *
     * @return the same simulated-seconds value as the historical {@link #getMakespan()}
     */
    public double getSimulationEndSeconds() {
        return makespan;
    }
    /**
     * Return the latest Job envelope end time at which every parsed logical Task was first
     * completed by a successful Job.
     *
     * @return completion in seconds when every logical Task succeeded; otherwise {@code null}
     */
    public Double getLogicalTaskCompletionSeconds() {
        return metrics.getLogicalTaskCompletionSeconds();
    }
    /** @return machine-readable status telling whether the logical Task completion time is available */
    public String getLogicalTaskCompletionStatus() {
        return metrics.getLogicalTaskCompletionStatus();
    }
    /**
     * Return the lifecycle tail between the successful completion of the last logical Task and
     * the CloudSim simulation end.
     *
     * @return non-negative simulated seconds when the workflow is complete; otherwise {@code null}
     */
    public Double getTerminalLifecycleTailSeconds() {
        return metrics.getTerminalLifecycleTailSeconds();
    }
    /** @return whether this run completed every logical Task successfully */
    public boolean isWorkflowCompletedSuccessfully() {
        return metrics.isWorkflowCompletedSuccessfully();
    }
    /**
     * Return the fingerprints of the input files actually consumed by this run.
     *
     * @return immutable list of input fingerprints in configuration order
     */
    public List<InputArtifact> getInputs() {
        return inputs;
    }
    /**
     * Return the validation report that the parser produced for each configured input.
     *
     * @return immutable list of input reports in configuration order
     */
    public List<WorkflowInputReport> getInputReports() {
        return inputReports;
    }

    public List<JobOutcome> getJobs() {
        return jobs;
    }

    public List<TaskOutcome> getTasks() {
        return tasks;
    }

    /**
     * R5 dynamic arrival: return the arrival and flow-time evidence of every workflow by input
     * index.
     *
     * @return per-workflow results in the same order as {@link #getInputReports()}
     */
    public List<WorkflowOutcome> getWorkflowOutcomes() {
        return workflowOutcomes;
    }

    public Map<Integer, VmSummary> getVmSummaries() {
        return vmSummaries;
    }
    /**
     * Actual CloudSim VM to Host placement, already compared against the platform precheck result.
     *
     * @return immutable complete VM-Host actual placement mapping
     */
    public Map<Integer, Integer> getActualVmHostAssignments() {
        return actualVmHostAssignments;
    }

    public int getTotalJobs() {
        return jobs.size();
    }

    public int getSuccessfulJobs() {
        return successfulJobs;
    }

    public int getFailedJobs() {
        return failedJobs;
    }

    public List<SimulationEvent> getEvents() {
        return events;
    }
    /**
     * Return the workflow structure summary computed from the task snapshot parsed by this run.
     *
     * @return immutable workflow profile
     */
    public WorkflowProfile getWorkflowProfile() {
        return workflowProfile;
    }
    /**
     * Return the model-layer metrics derived from the run evidence.
     *
     * @return immutable set of simulation metrics
     */
    public SimulationMetrics getMetrics() {
        return metrics;
    }

    /** BAT-E1 (2026-10-27): @return the energy accounting of each run; all zeros when there is no power
     * model. */
    public EnergyAccounting getEnergyAccounting() {
        return energyAccounting;
    }

    /** BAT-C1: @return the carbon accounting (either the intensity or the budget model); null when
     * undeclared. */
    public CarbonAccounting getCarbonAccounting() {
        return carbonAccounting;
    }

    private static boolean anyCarbonIntensity(PlatformProfile platform) {
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.getCarbonIntensityGramsPerKWh() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Optional immutable grouped-fluid capture, frozen before session cleanup.
     * COMPLETE zero-flow capture differs from OFF and TRUNCATED. This accessor
     * performs no I/O or metric derivation; use NetworkRunMetrics and the artifact writer separately.
     * @return captured network evidence, or null when OFF
     */
    public NetworkRunEvidence getNetworkEvidence() {
        return networkEvidence;
    }
    /** @return independent V2 lifecycle capture, null for OFF/V1 */
    public org.workflowsim.data.v2.FileLifecycleEvidence getFileLifecycleEvidence() {
        return fileLifecycleEvidence;
    }
    /** @return independent storage-version capture, null for OFF/V1/V2 */
    public org.workflowsim.data.v2.StorageLifecycleEvidence getStorageLifecycleEvidence() {
        return storageLifecycleEvidence;
    }
    /** @return defensive V2 resolved-plan snapshot, null for old models */
    public com.google.gson.JsonObject getDataflowPlan() {
        return dataflowPlan == null ? null : dataflowPlan.deepCopy();
    }
    /**
     * Return the trace record of the shared-storage static DAG plan.
     *
     * <p>The value may be {@code null} when the corresponding planning path was not taken; the
     * caller must first decide from the configuration and the algorithm contract whether it
     * applies.</p>
     *
     * @return planning trace record, or {@code null}
     */
    public SharedStorageDagPlanTrace getSharedStorageDagPlanTrace() {
        return sharedStorageDagPlanTrace;
    }

    /** @return raw logical task dependency snapshot sorted by task ID, without stage-in or retry
     * nodes */
    public List<TaskNode> getWorkflowGraph() {
        return workflowGraph;
    }

    /** @return actual online compute request metadata, null for preplanned/default runs */
    public List<DataflowComputeRequest> getDataflowComputeRequests() {
        return dataflowComputeRequests;
    }
    /** Immutable per-attempt compute declarations; not a forecast of CPU completion. */
    public static final class DataflowComputeRequest {
        private final int jobId, taskId, taskPes, jobPes;
        private final long lengthMi;

        private DataflowComputeRequest(Job job, Task task) {
            jobId = job.getCloudletId();
            taskId = task.getCloudletId();
            taskPes = task.getNumberOfPes();
            jobPes = job.getNumberOfPes();
            lengthMi = task.getCloudletLength();
        }

        public int getJobId() {
            return jobId;
        }

        public int getTaskId() {
            return taskId;
        }

        public int getTaskPes() {
            return taskPes;
        }

        public int getJobPes() {
            return jobPes;
        }

        public long getLengthMi() {
            return lengthMi;
        }
    }

    /** Original task node used for reporting and cross-review. */
    public static final class TaskNode {
        private final int taskId;
        private final String type;
        private final int depth;
        private final List<Integer> parentIds;
        private final List<Integer> childIds;

        private TaskNode(Task task) {
            taskId = task.getCloudletId();
            type = task.getType();
            depth = task.getDepth();
            List<Integer> parents = new ArrayList<Integer>();
            for (Task parent : task.getParentList()) {
                parents.add(parent.getCloudletId());
            }
            List<Integer> children = new ArrayList<Integer>();
            for (Task child : task.getChildList()) {
                children.add(child.getCloudletId());
            }
            Collections.sort(parents);
            Collections.sort(children);
            parentIds = Collections.unmodifiableList(parents);
            childIds = Collections.unmodifiableList(children);
        }

        public int getTaskId() {
            return taskId;
        }

        public String getType() {
            return type;
        }

        public int getDepth() {
            return depth;
        }

        public List<Integer> getParentIds() {
            return parentIds;
        }

        public List<Integer> getChildIds() {
            return childIds;
        }
    }

    /** Immutable content fingerprint of a consumed workflow file. */
    public static final class InputArtifact {

        private final String path;
        private final String sha256;
        private final long sizeBytes;

        private InputArtifact(String path, String sha256, long sizeBytes) {
            this.path = path;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
        }

        private static InputArtifact fromPath(String input) throws IOException {
            Path path = java.nio.file.Paths.get(input).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IOException("Workflow input cannot be fingerprinted: " + path);
            }
            return new InputArtifact(path.toString(), sha256(path), Files.size(path));
        }

        public String getPath() {
            return path;
        }

        public String getSha256() {
            return sha256;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }
    }

    /** Immutable execution result of one WorkflowSim Job. */
    public static final class JobOutcome {

        private final int jobId;
        private final int vmId;
        private final int status;
        private final int classType;
        private final double submissionTime;
        private final double startTime;
        private final double finishTime;
        private final double waitingTime;
        private final double executionTime;
        private final double responseTime;
        private final double cpuTime;
        private final double modeledCpuEnvelopeCost;
        private final double modeledDeclaredFileBandwidthCost;
        private final double modeledDeclaredFileBytes;
        private final double modeledProcessingCost;
        private final int taskCount;
        private final List<Integer> taskIds;
        /**
         * BAT-D2 (2026-10-29): MIPS of the DVFS frequency tier assigned at dispatch time.
         *
         * <p>{@code null} = v1 no-tier mode (the legal default in compatibility runs and in the
         * result manifest); non-null = the tier identity under whose speed this Job executes over
         * the [startTime, finishTime) interval (the tier can be recovered by matching the MIPS
         * value against the platform frequencyTiers -- tiers are unique by MIPS).
         * The field is declared last: the serialization key order stays bit-for-bit stable for
         * existing manifests, and new keys are only appended.
         * <b>transient:</b> the outer manifest Gson configures {@code serializeNulls()}, so a
         * non-transient null tier would add a {@code "tierMips":null} key to every v1 legacy Job
         * -- the evidence surface requires null to mean a missing key (v1 legacy), and
         * {@code ExperimentManifestWriter} appends that key explicitly only when the value is
         * non-null.</p>
         */
        private final transient Double tierMips;

        JobOutcome(
                int jobId,
                int vmId,
                int status,
                int classType,
                double submissionTime,
                double startTime,
                double finishTime,
                double cpuTime,
                double modeledProcessingCost,
                int taskCount,
                List<Integer> taskIds) {
            this(
                    jobId,
                    vmId,
                    status,
                    classType,
                    submissionTime,
                    startTime,
                    finishTime,
                    cpuTime,
                    modeledProcessingCost,
                    0.0,
                    0.0,
                    taskCount,
                    taskIds);
        }

        JobOutcome(
                int jobId,
                int vmId,
                int status,
                int classType,
                double submissionTime,
                double startTime,
                double finishTime,
                double cpuTime,
                double modeledCpuEnvelopeCost,
                double modeledDeclaredFileBandwidthCost,
                double modeledDeclaredFileBytes,
                int taskCount,
                List<Integer> taskIds) {
            this(
                    jobId,
                    vmId,
                    status,
                    classType,
                    submissionTime,
                    startTime,
                    finishTime,
                    cpuTime,
                    modeledCpuEnvelopeCost,
                    modeledDeclaredFileBandwidthCost,
                    modeledDeclaredFileBytes,
                    taskCount,
                    taskIds,
                    null);
        }

        JobOutcome(
                int jobId,
                int vmId,
                int status,
                int classType,
                double submissionTime,
                double startTime,
                double finishTime,
                double cpuTime,
                double modeledCpuEnvelopeCost,
                double modeledDeclaredFileBandwidthCost,
                double modeledDeclaredFileBytes,
                int taskCount,
                List<Integer> taskIds,
                Double tierMips) {
            this.jobId = jobId;
            this.vmId = vmId;
            this.status = status;
            this.classType = classType;
            this.submissionTime = submissionTime;
            this.startTime = startTime;
            this.finishTime = finishTime;
            // Boundary guard: timestamps of failed or exceptional jobs may be incomplete, so negative
            // values are clamped to 0
            this.waitingTime = Math.max(0.0, startTime - submissionTime);
            this.executionTime = Math.max(0.0, finishTime - startTime);
            this.responseTime = Math.max(0.0, finishTime - submissionTime);
            this.cpuTime = cpuTime;
            this.modeledCpuEnvelopeCost = modeledCpuEnvelopeCost;
            this.modeledDeclaredFileBandwidthCost = modeledDeclaredFileBandwidthCost;
            this.modeledDeclaredFileBytes = modeledDeclaredFileBytes;
            this.modeledProcessingCost = modeledCpuEnvelopeCost + modeledDeclaredFileBandwidthCost;
            this.taskCount = taskCount;
            this.taskIds = Collections.unmodifiableList(new ArrayList<>(taskIds));
            // BAT-D2: a non-positive tier is not a legal default -- the evidence chain rejects it
            // instead of silently setting null (no tier means null).
            if (tierMips != null
                    && !(tierMips.doubleValue() > 0.0 && tierMips.isInfinite() == false && tierMips.isNaN() == false)) {
                throw new IllegalArgumentException("Invalid tier mips on Job " + jobId + ": " + tierMips);
            }
            this.tierMips = tierMips;
        }

        private static JobOutcome fromJob(Job job) {
            List<Integer> taskIds = new ArrayList<>();
            for (org.workflowsim.Task task : job.getTaskList()) {
                taskIds.add(task.getCloudletId());
            }
            return new JobOutcome(
                    job.getCloudletId(),
                    job.getVmId(),
                    job.getCloudletStatus(),
                    job.getClassType(),
                    job.getSubmissionTime(),
                    job.getExecStartTime(),
                    job.getFinishTime(),
                    job.getActualCPUTime(),
                    job.getModeledCpuEnvelopeCost(),
                    job.getModeledDeclaredFileBandwidthCost(),
                    job.getModeledDeclaredFileBytes(),
                    job.getTaskList().size(),
                    taskIds,
                    job.getTierMips());
        }

        public int getJobId() {
            return jobId;
        }

        public int getVmId() {
            return vmId;
        }

        public int getStatus() {
            return status;
        }

        public int getClassType() {
            return classType;
        }

        public double getSubmissionTime() {
            return submissionTime;
        }

        public double getStartTime() {
            return startTime;
        }

        public double getFinishTime() {
            return finishTime;
        }
        /**
         * Return the VM queue waiting time (arrival at the VM to start of execution, that is
         * startTime - submissionTime).
         * <p><strong>Note:</strong> every scheduling algorithm in WorkflowSim dispatches jobs only
         * to idle VMs, so this value is almost always 0. For the real scheduling wait (ready to
         * start) see {@code SimulationMetrics#getMeanComputeTotalWaitingTimeSeconds()}.</p>
         */
        public double getWaitingTime() {
            return waitingTime;
        }

        public double getExecutionTime() {
            return executionTime;
        }

        public double getResponseTime() {
            return responseTime;
        }
        /**
         * Job envelope execution time as reported by CloudSim. With stage-in enabled the value may
         * include the integer MI representation of the corresponding delay; it is not a purely
         * measured CPU time observation.
         *
         * @return Job envelope execution time in simulated seconds
         */
        public double getCpuTime() {
            return cpuTime;
        }
        /**
         * CPU abstract cost component of the Job envelope.
         *
         * @return CPU envelope cost, possibly including CloudSim stage-in MI injection
         */
        public double getModeledCpuEnvelopeCost() {
            return modeledCpuEnvelopeCost;
        }
        /**
         * Bandwidth abstract cost component, continuously priced per decimal MB over all declared
         * file bytes of the Job.
         *
         * @return declared-file bandwidth cost approximation
         */
        public double getModeledDeclaredFileBandwidthCost() {
            return modeledDeclaredFileBandwidthCost;
        }
        /**
         * Continuous byte equivalent that enters the declared-file bandwidth cost.
         *
         * @return sum of all declared file sizes in the Job file table
         */
        public double getModeledDeclaredFileBytes() {
            return modeledDeclaredFileBytes;
        }
        /**
         * CloudSim processing cost of the same Job envelope. The value is neither a replay of a
         * provider tariff nor a separately priced network transfer cost.
         *
         * @return modeled processing cost of the Job
         */
        public double getModeledProcessingCost() {
            return modeledProcessingCost;
        }

        public int getTaskCount() {
            return taskCount;
        }

        public List<Integer> getTaskIds() {
            return taskIds;
        }

        /**
         * BAT-D2 (2026-10-29): return the MIPS of the DVFS frequency tier assigned at dispatch time.
         *
         * @return MIPS of the assigned tier; {@code null} = v1 no-tier mode (the legal default in
         *         the result manifest, serialized as a missing key rather than an explicit null)
         */
        public Double getTierMips() {
            return tierMips;
        }
    }

    /**
     * Immutable task-level observation. A task may appear in several retry Jobs; any successful
     * Job that contains it makes the task count as successfully completed. Failed Jobs keep their
     * task-level state so that failure and reclustering records remain observable.
     * Logical Task timing is the model-derived computation window recorded at submission. It is
     * marked exact only when that window of a single-task Job equals the CloudSim Job envelope
     * after completion; modeled stage-in keeps the two ranges distinct.
     */
    public static final class TaskOutcome {

        private final int taskId;
        private final int jobId;
        private final int vmId;
        private final int jobStatus;
        private final int taskStatus;
        private final int depth;
        private final long lengthMi;
        private final long effectiveExecutionLengthMi;
        private final double startTime;
        private final double finishTime;
        private final boolean exactJobTiming;

        TaskOutcome(
                int taskId,
                int jobId,
                int vmId,
                int jobStatus,
                int taskStatus,
                int depth,
                long lengthMi,
                double startTime,
                double finishTime) {
            this(taskId, jobId, vmId, jobStatus, taskStatus, depth, lengthMi, startTime, finishTime, true);
        }

        TaskOutcome(
                int taskId,
                int jobId,
                int vmId,
                int jobStatus,
                int taskStatus,
                int depth,
                long lengthMi,
                double startTime,
                double finishTime,
                boolean exactJobTiming) {
            this(
                    taskId,
                    jobId,
                    vmId,
                    jobStatus,
                    taskStatus,
                    depth,
                    lengthMi,
                    lengthMi,
                    startTime,
                    finishTime,
                    exactJobTiming);
        }

        TaskOutcome(
                int taskId,
                int jobId,
                int vmId,
                int jobStatus,
                int taskStatus,
                int depth,
                long lengthMi,
                long effectiveExecutionLengthMi,
                double startTime,
                double finishTime,
                boolean exactJobTiming) {
            this.taskId = taskId;
            this.jobId = jobId;
            this.vmId = vmId;
            this.jobStatus = jobStatus;
            this.taskStatus = taskStatus;
            this.depth = depth;
            this.lengthMi = lengthMi;
            this.effectiveExecutionLengthMi = effectiveExecutionLengthMi;
            this.startTime = startTime;
            this.finishTime = finishTime;
            this.exactJobTiming = exactJobTiming;
        }

        static List<TaskOutcome> fromJob(Job job) {
            List<TaskOutcome> results = new ArrayList<>();
            for (org.workflowsim.Task task : job.getTaskList()) {
                boolean exactJobTiming = job.getTaskList().size() == 1
                        && Double.compare(task.getExecStartTime(), job.getExecStartTime()) == 0
                        && Double.compare(task.getTaskFinishTime(), job.getFinishTime()) == 0;
                results.add(new TaskOutcome(
                        task.getCloudletId(),
                        job.getCloudletId(),
                        job.getVmId(),
                        job.getCloudletStatus(),
                        job.getCloudletStatus() == Cloudlet.SUCCESS ? Cloudlet.SUCCESS : task.getCloudletStatus(),
                        task.getDepth(),
                        task.getCloudletLength(),
                        task.getEffectiveExecutionLengthMi(),
                        task.getExecStartTime(),
                        task.getTaskFinishTime(),
                        exactJobTiming));
            }
            return results;
        }

        public int getTaskId() {
            return taskId;
        }

        public int getJobId() {
            return jobId;
        }

        public int getVmId() {
            return vmId;
        }

        public int getJobStatus() {
            return jobStatus;
        }

        public int getTaskStatus() {
            return taskStatus;
        }

        public int getDepth() {
            return depth;
        }
        /** @return original declared per-PE work in MI */
        public long getLengthMi() {
            return lengthMi;
        }
        /** @return current attempt's effective per-PE compute MI, excluding stage-in */
        public long getEffectiveExecutionLengthMi() {
            return effectiveExecutionLengthMi;
        }

        public double getStartTime() {
            return startTime;
        }

        public double getFinishTime() {
            return finishTime;
        }
        /**
         * {@code true} only when the single-task computation window equals the CloudSim Job envelope.
         *
         * @return whether this task has exact Job timing
         */
        public boolean hasExactJobTiming() {
            return exactJobTiming;
        }
    }

    /** R5 dynamic arrival: arrival instant, task interval, and flow-time evidence of one workflow
     * input. */
    public static final class WorkflowOutcome {

        private final int index;
        private final String path;
        private final double arrivalSecond;
        private final int firstTaskId;
        private final int lastTaskId;
        private final int taskCount;
        private final double lastSuccessFinishSecond;
        private final double flowTimeSeconds;

        WorkflowOutcome(
                int index,
                String path,
                double arrivalSecond,
                int firstTaskId,
                int lastTaskId,
                int taskCount,
                double lastSuccessFinishSecond,
                double flowTimeSeconds) {
            this.index = index;
            this.path = path;
            this.arrivalSecond = arrivalSecond;
            this.firstTaskId = firstTaskId;
            this.lastTaskId = lastTaskId;
            this.taskCount = taskCount;
            this.lastSuccessFinishSecond = lastSuccessFinishSecond;
            this.flowTimeSeconds = flowTimeSeconds;
        }

        /** @return index of the input in this submission (0-based) */
        public int getIndex() {
            return index;
        }
        /** @return input file path */
        public String getPath() {
            return path;
        }
        /** @return configured submission instant (simulated seconds) */
        public double getArrivalSecond() {
            return arrivalSecond;
        }
        /** @return first task identifier of this input */
        public int getFirstTaskId() {
            return firstTaskId;
        }
        /** @return last task identifier of this input */
        public int getLastTaskId() {
            return lastTaskId;
        }
        /** @return task count of this input */
        public int getTaskCount() {
            return taskCount;
        }
        /** @return latest completion instant over all successful tasks; {@code NaN} when none succeeded */
        public double getLastSuccessFinishSecond() {
            return lastSuccessFinishSecond;
        }
        /** @return flow time = latest successful completion instant - submission instant; {@code NaN}
         * when no task succeeded */
        public double getFlowTimeSeconds() {
            return flowTimeSeconds;
        }
    }

    /** Per-VM aggregate over completed Jobs; not a Host utilization model. */
    public static final class VmSummary {

        private final int vmId;
        private final int jobs;
        private final int successfulJobs;
        private final int failedJobs;
        private final double totalCpuTime;
        private final double lastFinishTime;

        VmSummary(int vmId, int jobs, int successfulJobs, int failedJobs, double totalCpuTime, double lastFinishTime) {
            this.vmId = vmId;
            this.jobs = jobs;
            this.successfulJobs = successfulJobs;
            this.failedJobs = failedJobs;
            this.totalCpuTime = totalCpuTime;
            this.lastFinishTime = lastFinishTime;
        }

        public int getVmId() {
            return vmId;
        }

        public int getJobs() {
            return jobs;
        }

        public int getSuccessfulJobs() {
            return successfulJobs;
        }

        public int getFailedJobs() {
            return failedJobs;
        }

        public double getTotalCpuTime() {
            return totalCpuTime;
        }

        public double getLastFinishTime() {
            return lastFinishTime;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof VmSummary)) {
                return false;
            }
            VmSummary that = (VmSummary) other;
            return vmId == that.vmId
                    && jobs == that.jobs
                    && successfulJobs == that.successfulJobs
                    && failedJobs == that.failedJobs
                    && Double.compare(totalCpuTime, that.totalCpuTime) == 0
                    && Double.compare(lastFinishTime, that.lastFinishTime) == 0;
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(vmId);
            result = 31 * result + jobs;
            result = 31 * result + successfulJobs;
            result = 31 * result + failedJobs;
            long cpuBits = Double.doubleToLongBits(totalCpuTime);
            result = 31 * result + (int) (cpuBits ^ (cpuBits >>> 32));
            long finishBits = Double.doubleToLongBits(lastFinishTime);
            result = 31 * result + (int) (finishBits ^ (finishBits >>> 32));
            return result;
        }
    }

    private static final class MutableVmSummary {

        private final int vmId;
        private int jobs;
        private int successes;
        private int failures;
        private double cpuTime;
        private double lastFinish;

        private MutableVmSummary(int vmId) {
            this.vmId = vmId;
        }

        private void add(JobOutcome job) {
            jobs++;
            cpuTime += job.getCpuTime();
            lastFinish = Math.max(lastFinish, job.getFinishTime());
            if (job.getStatus() == Cloudlet.SUCCESS) {
                successes++;
            } else if (job.getStatus() == Cloudlet.FAILED) {
                failures++;
            }
        }

        private VmSummary freeze() {
            return new VmSummary(vmId, jobs, successes, failures, cpuTime, lastFinish);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not provide SHA-256", exception);
        }
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder output = new StringBuilder();
        for (byte value : digest.digest()) {
            output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return output.toString();
    }
}
