package org.workflowsim.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.utils.Parameters.CostModel;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;

public final class SimulationConfig {

    public enum FrequencyPolicy {
        DEFAULT,

        MIN_POWER,

        EDP_HEURISTIC
    }

    public static final int DEFAULT_EVENT_RECORDER_CAP = 20000;

    private final List<String> workflowPaths;
    private final List<Double> workflowArrivalSeconds;
    private final int vmCount;
    private final OverheadModelConfig overheadModel;
    private final ClusteringParameters clusteringParameters;
    private final SchedulingAlgorithm schedulingAlgorithm;
    private final PlanningAlgorithm planningAlgorithm;
    private final String reduceMethod;
    private final long deadline;
    private final ReplicaCatalog.FileSystem fileSystem;
    private final long randomSeed;
    private final double runtimeScale;
    private final double runtimeReferenceMips;
    private final double cloudSimMinEventIntervalSeconds;
    private final CostModel costModel;
    private final FailureModelConfig failureModel;
    private final DataMovementModel dataMovementModel;
    private final NetworkEvidenceConfig networkEvidenceConfig;
    private final transient org.workflowsim.data.v2.DataflowAssignmentConfig dataflowAssignmentConfig;
    private final TaskCostMatrix taskCostMatrix;
    private final transient org.workflowsim.experiment.registry.SchedulingAlgorithmProvider
            registeredSchedulingAlgorithmProvider;
    private final FrequencyPolicy frequencyPolicy;
    private final org.workflowsim.utils.ReshiConfig reshiConfig;
    private final org.workflowsim.utils.EbpsmConfig ebpsmConfig;
    private final org.workflowsim.utils.CrchConfig crchConfig;
    private final int eventRecorderCap;
    private final boolean eventRecordingEnabled;

    private SimulationConfig(Builder builder) {
        this.workflowPaths = Collections.unmodifiableList(new ArrayList<>(builder.workflowPaths));
        this.workflowArrivalSeconds = Collections.unmodifiableList(new ArrayList<>(builder.workflowArrivalSeconds));
        this.vmCount = builder.vmCount;
        this.overheadModel = builder.overheadModel;
        this.clusteringParameters = builder.clusteringParameters;
        this.schedulingAlgorithm = builder.schedulingAlgorithm;
        this.planningAlgorithm = builder.planningAlgorithm;
        this.reduceMethod = builder.reduceMethod;
        this.deadline = builder.deadline;
        this.fileSystem = builder.fileSystem;
        this.randomSeed = builder.randomSeed;
        this.runtimeScale = builder.runtimeScale;
        this.runtimeReferenceMips = builder.runtimeReferenceMips;
        this.cloudSimMinEventIntervalSeconds = builder.cloudSimMinEventIntervalSeconds;
        this.costModel = builder.costModel;
        this.failureModel = builder.failureModel;
        this.dataMovementModel = builder.dataMovementModel;
        this.networkEvidenceConfig = builder.networkEvidenceConfig;
        this.dataflowAssignmentConfig = builder.dataflowAssignmentConfig;
        this.taskCostMatrix = builder.taskCostMatrix;
        this.registeredSchedulingAlgorithmProvider = builder.registeredSchedulingAlgorithmProvider;
        this.frequencyPolicy = builder.frequencyPolicy;
        this.reshiConfig = builder.reshiConfig;
        this.ebpsmConfig = builder.ebpsmConfig;
        this.crchConfig = builder.crchConfig;
        this.eventRecorderCap = builder.eventRecorderCap;
        this.eventRecordingEnabled = builder.eventRecordingEnabled;
    }

    public static Builder builder(String workflowPath, int vmCount) {
        return new Builder(Collections.singletonList(workflowPath), vmCount);
    }

    public static Builder builder(List<String> workflowPaths, int vmCount) {
        return new Builder(workflowPaths, vmCount);
    }

    public List<String> getWorkflowPaths() {
        return workflowPaths;
    }

    public List<Double> getWorkflowArrivalSeconds() {
        return workflowArrivalSeconds;
    }

    public int getVmCount() {
        return vmCount;
    }

    public OverheadModelConfig getOverheadModel() {
        return overheadModel;
    }

    public ClusteringParameters getClusteringParameters() {
        return clusteringParameters;
    }

    public SchedulingAlgorithm getSchedulingAlgorithm() {
        return schedulingAlgorithm;
    }

    public org.workflowsim.experiment.registry.SchedulingAlgorithmProvider getRegisteredSchedulingAlgorithmProvider() {
        return registeredSchedulingAlgorithmProvider;
    }

    public PlanningAlgorithm getPlanningAlgorithm() {
        return planningAlgorithm;
    }

    public String getReduceMethod() {
        return reduceMethod;
    }

    public long getDeadline() {
        return deadline;
    }

    public ReplicaCatalog.FileSystem getFileSystem() {
        return fileSystem;
    }

    public long getRandomSeed() {
        return randomSeed;
    }

    public double getRuntimeScale() {
        return runtimeScale;
    }

    public double getRuntimeReferenceMips() {
        return runtimeReferenceMips;
    }

    public double getCloudSimMinEventIntervalSeconds() {
        return cloudSimMinEventIntervalSeconds;
    }

    public CostModel getCostModel() {
        return costModel;
    }

    public FailureModelConfig getFailureModel() {
        return failureModel;
    }

    public DataMovementModel getDataMovementModel() {
        return dataMovementModel;
    }

    public NetworkEvidenceConfig getNetworkEvidenceConfig() {
        return networkEvidenceConfig;
    }

    public org.workflowsim.data.v2.DataflowAssignmentConfig getDataflowAssignmentConfig() {
        return dataflowAssignmentConfig;
    }

    public TaskCostMatrix getTaskCostMatrix() {
        return taskCostMatrix;
    }

    public FrequencyPolicy getFrequencyPolicy() {
        return frequencyPolicy;
    }

    public org.workflowsim.utils.ReshiConfig getReshiConfig() {
        return reshiConfig;
    }

    public org.workflowsim.utils.EbpsmConfig getEbpsmConfig() {
        return ebpsmConfig;
    }

    public org.workflowsim.utils.CrchConfig getCrchConfig() {
        return crchConfig;
    }

    public int getEventRecorderCap() {
        return eventRecorderCap;
    }

    public boolean isEventRecordingEnabled() {
        return eventRecordingEnabled;
    }

    public Builder toBuilder() {
        return new Builder(workflowPaths, vmCount)
                .workflowArrivalSeconds(workflowArrivalSeconds)
                .overheadModel(overheadModel)
                .clusteringParameters(clusteringParameters)
                .schedulingAlgorithm(schedulingAlgorithm)
                .planningAlgorithm(planningAlgorithm)
                .reduceMethod(reduceMethod)
                .deadline(deadline)
                .fileSystem(fileSystem)
                .randomSeed(randomSeed)
                .runtimeScale(runtimeScale)
                .runtimeReferenceMips(runtimeReferenceMips)
                .cloudSimMinEventIntervalSeconds(cloudSimMinEventIntervalSeconds)
                .costModel(costModel)
                .failureModel(failureModel)
                .dataMovementModel(dataMovementModel)
                .networkEvidence(networkEvidenceConfig)
                .dataflowAssignment(dataflowAssignmentConfig)
                .taskCostMatrix(taskCostMatrix)
                .eventRecorderCap(eventRecorderCap)
                .eventRecordingEnabled(eventRecordingEnabled)
                .frequencyPolicy(frequencyPolicy);
    }

    public SimulationConfig withRandomSeed(long value) {
        return toBuilder().randomSeed(value).build();
    }

    public static final class Builder {

        private final List<String> workflowPaths;
        private List<Double> workflowArrivalSeconds;
        private final int vmCount;
        private OverheadModelConfig overheadModel = OverheadModelConfig.none();
        private ClusteringParameters clusteringParameters =
                new ClusteringParameters(0, 0, ClusteringParameters.ClusteringMethod.NONE, null);
        private SchedulingAlgorithm schedulingAlgorithm = SchedulingAlgorithm.FCFS;

        private org.workflowsim.experiment.registry.SchedulingAlgorithmProvider registeredSchedulingAlgorithmProvider;

        private PlanningAlgorithm planningAlgorithm = PlanningAlgorithm.INVALID;
        private String reduceMethod;
        private long deadline;
        private ReplicaCatalog.FileSystem fileSystem = ReplicaCatalog.FileSystem.SHARED;
        private long randomSeed;
        private double runtimeScale = 1.0;
        private double runtimeReferenceMips = 1000.0;
        private double cloudSimMinEventIntervalSeconds = 0.1;
        private CostModel costModel = CostModel.DATACENTER;
        private FailureModelConfig failureModel = FailureModelConfig.disabled();
        private DataMovementModel dataMovementModel = DataMovementModel.legacyWorkflowsimV1();
        private NetworkEvidenceConfig networkEvidenceConfig = NetworkEvidenceConfig.off();
        private org.workflowsim.data.v2.DataflowAssignmentConfig dataflowAssignmentConfig =
                org.workflowsim.data.v2.DataflowAssignmentConfig.off();

        public Builder dataflowAssignment(org.workflowsim.data.v2.DataflowAssignmentConfig value) {
            dataflowAssignmentConfig = value;
            return this;
        }

        private TaskCostMatrix taskCostMatrix;

        private FrequencyPolicy frequencyPolicy = FrequencyPolicy.DEFAULT;

        private org.workflowsim.utils.ReshiConfig reshiConfig;
        private org.workflowsim.utils.EbpsmConfig ebpsmConfig;
        private org.workflowsim.utils.CrchConfig crchConfig;

        public Builder crchConfig(org.workflowsim.utils.CrchConfig value) {
            this.crchConfig = value;
            return this;
        }

        public Builder ebpsmConfig(org.workflowsim.utils.EbpsmConfig value) {
            this.ebpsmConfig = value;
            return this;
        }

        public Builder reshiConfig(org.workflowsim.utils.ReshiConfig value) {
            this.reshiConfig = value;
            return this;
        }

        private int eventRecorderCap = DEFAULT_EVENT_RECORDER_CAP;
        private boolean eventRecordingEnabled = true;

        private Builder(List<String> workflowPaths, int vmCount) {
            if (workflowPaths == null || workflowPaths.isEmpty()) {
                throw new IllegalArgumentException("At least one workflow input is required");
            }
            this.workflowPaths = new ArrayList<>(workflowPaths);

            this.workflowArrivalSeconds = new ArrayList<>(Collections.nCopies(workflowPaths.size(), 0.0));
            this.vmCount = vmCount;
        }

        @Deprecated
        public Builder overheadParameters(OverheadParameters value) {
            this.overheadModel = OverheadModelConfig.fromLegacy(value);
            return this;
        }

        public Builder overheadModel(OverheadModelConfig value) {
            this.overheadModel = value;
            return this;
        }

        public Builder frequencyPolicy(FrequencyPolicy value) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "Frequency policy cannot be null; leave it unset for the DEFAULT default");
            }
            this.frequencyPolicy = value;
            return this;
        }

        public Builder eventRecorderCap(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("Event recorder cap must be strictly positive: " + value);
            }
            this.eventRecorderCap = value;
            return this;
        }

        public Builder eventRecordingEnabled(boolean value) {
            this.eventRecordingEnabled = value;
            return this;
        }

        public Builder clusteringParameters(ClusteringParameters value) {
            this.clusteringParameters = value;
            return this;
        }

        public Builder schedulingAlgorithm(SchedulingAlgorithm value) {
            this.schedulingAlgorithm = value;
            return this;
        }

        public Builder registeredSchedulingAlgorithm(
                org.workflowsim.experiment.registry.SchedulingAlgorithmProvider provider) {
            this.registeredSchedulingAlgorithmProvider = provider;
            return this;
        }

        public Builder planningAlgorithm(PlanningAlgorithm value) {
            this.planningAlgorithm = value;
            return this;
        }

        public Builder reduceMethod(String value) {
            this.reduceMethod = value;
            return this;
        }

        public Builder deadline(long value) {
            this.deadline = value;
            return this;
        }

        public Builder fileSystem(ReplicaCatalog.FileSystem value) {
            this.fileSystem = value;
            return this;
        }

        public Builder randomSeed(long value) {
            this.randomSeed = value;
            return this;
        }

        public Builder runtimeScale(double value) {
            this.runtimeScale = value;
            return this;
        }

        public Builder runtimeReferenceMips(double value) {
            this.runtimeReferenceMips = value;
            return this;
        }

        public Builder cloudSimMinEventIntervalSeconds(double value) {
            this.cloudSimMinEventIntervalSeconds = value;
            return this;
        }

        public Builder costModel(CostModel value) {
            this.costModel = value;
            return this;
        }

        public Builder failureModel(FailureModelConfig value) {
            this.failureModel = value;
            return this;
        }

        public Builder dataMovementModel(DataMovementModel value) {
            this.dataMovementModel = value;
            return this;
        }

        public Builder networkEvidence(NetworkEvidenceConfig value) {
            this.networkEvidenceConfig = value;
            return this;
        }

        public Builder taskCostMatrix(TaskCostMatrix value) {
            this.taskCostMatrix = value;
            return this;
        }

        public Builder workflowArrivalSeconds(List<Double> value) {
            if (value == null) {
                throw new IllegalArgumentException("Workflow arrival seconds cannot be null; "
                        + "use an explicit list of zeros for simultaneous submission");
            }
            this.workflowArrivalSeconds = new ArrayList<>(value);
            return this;
        }

        public SimulationConfig build() {
            validateWorkflowPaths();
            validateRequiredModels();
            validateArrivalSeconds();
            validateScalarBounds();
            validateSchedulerCombinations();
            validateSharedStoragePlanners();
            validateTaskCostMatrix();
            validateLocalPlanners();
            validatePreExecutionPairing();
            validateFatTreePremises();
            validateCoherentCombinations();
            return new SimulationConfig(this);
        }

        private void validateWorkflowPaths() {
            for (String path : workflowPaths) {
                if (path == null || path.trim().isEmpty()) {
                    throw new IllegalArgumentException("Workflow input paths cannot be empty");
                }
            }
        }

        private void validateRequiredModels() {

            if (overheadModel == null
                    || clusteringParameters == null
                    || schedulingAlgorithm == null
                    || planningAlgorithm == null
                    || fileSystem == null
                    || costModel == null
                    || failureModel == null
                    || dataMovementModel == null
                    || networkEvidenceConfig == null
                    || dataflowAssignmentConfig == null) {
                throw new IllegalArgumentException("Simulation configuration contains a required null value");
            }
            org.workflowsim.data.NetworkEvidenceConfigCodec.requireCompatible(dataMovementModel, networkEvidenceConfig);
        }

        private void validateArrivalSeconds() {
            if (workflowArrivalSeconds == null || workflowArrivalSeconds.size() != workflowPaths.size()) {
                throw new IllegalArgumentException(
                        "Workflow arrival seconds must cover every " + "workflow input path");
            }
            for (Double arrivalSecond : workflowArrivalSeconds) {
                if (arrivalSecond == null || !Double.isFinite(arrivalSecond.doubleValue()) || arrivalSecond < 0.0) {
                    throw new IllegalArgumentException("Workflow arrival seconds must be finite " + "and non-negative");
                }
            }
            boolean hasNonZeroArrival = false;
            for (Double arrivalSecond : workflowArrivalSeconds) {
                if (arrivalSecond > 0.0) {
                    hasNonZeroArrival = true;
                    break;
                }
            }

            if (hasNonZeroArrival
                    && clusteringParameters.getClusteringMethod() != ClusteringParameters.ClusteringMethod.NONE) {
                throw new IllegalArgumentException("Non-zero workflow arrival seconds require "
                        + "clustering method NONE: clustered jobs no longer carry the original "
                        + "task ids used for arrival gating");
            }
        }

        private void validateScalarBounds() {
            if (vmCount <= 0) {
                throw new IllegalArgumentException("VM count must be positive");
            }
            if (deadline < 0L) {
                throw new IllegalArgumentException(
                        "Deadline must be zero (not requested) or a positive simulated second");
            }
            if (!isPositiveFinite(runtimeScale)
                    || !isPositiveFinite(runtimeReferenceMips)
                    || !isPositiveFinite(cloudSimMinEventIntervalSeconds)) {
                throw new IllegalArgumentException("Runtime scale, reference MIPS, and CloudSim minimum "
                        + "event interval must be finite and positive");
            }
        }

        private void validateSchedulerCombinations() {
            if (planningAlgorithm != PlanningAlgorithm.INVALID
                    && schedulingAlgorithm != SchedulingAlgorithm.STATIC
                    && schedulingAlgorithm != SchedulingAlgorithm.DVFS_SLACK) {
                throw new IllegalArgumentException("A planning algorithm assigns VM mappings and requires "
                        + "SchedulingAlgorithm.STATIC or DVFS_SLACK dispatch");
            }
            if (planningAlgorithm == PlanningAlgorithm.INVALID
                    && (schedulingAlgorithm == SchedulingAlgorithm.STATIC
                            || schedulingAlgorithm == SchedulingAlgorithm.DVFS_SLACK)
                    && !dataflowAssignmentConfig.isEnabled()) {
                throw new IllegalArgumentException(
                        schedulingAlgorithm + " requires a planning algorithm " + "that assigns VM mappings");
            }

            if (schedulingAlgorithm == SchedulingAlgorithm.DATA && fileSystem == ReplicaCatalog.FileSystem.SHARED) {
                throw new IllegalArgumentException("SchedulingAlgorithm.DATA requires ReplicaCatalog.FileSystem.LOCAL; "
                        + "under SHARED storage its replica sites are datacenter names that never match "
                        + "VM ids, so it would silently degenerate to first-fit-idle");
            }
            if (schedulingAlgorithm == SchedulingAlgorithm.INVALID && registeredSchedulingAlgorithmProvider == null) {
                throw new IllegalArgumentException("SchedulingAlgorithm.INVALID is not a runnable configuration; "
                        + "select an explicit online scheduler or STATIC with a planning algorithm");
            }
        }

        private void validateSharedStoragePlanners() {
            if (planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_HEFT
                    || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_CPOP
                    || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_DLS
                    || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_ETF
                    || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_PEFT
                    || planningAlgorithm == PlanningAlgorithm.CAWOSCHED_SLACK
                    || planningAlgorithm == PlanningAlgorithm.CAWOSCHED_PRESSURE
                    || planningAlgorithm == PlanningAlgorithm.GREEN_HEFT
                    || planningAlgorithm == PlanningAlgorithm.MO_HEFT
                    || planningAlgorithm == PlanningAlgorithm.IC_PCP
                    || planningAlgorithm == PlanningAlgorithm.DVFS_SLACK) {
                if (fileSystem != ReplicaCatalog.FileSystem.SHARED) {
                    throw new IllegalArgumentException(planningAlgorithm + " requires shared storage");
                }
                if (clusteringParameters.getClusteringMethod() != ClusteringParameters.ClusteringMethod.NONE) {
                    throw new IllegalArgumentException(planningAlgorithm + " requires clustering method NONE");
                }
                if (failureModel.isEnabled()) {
                    throw new IllegalArgumentException(
                            planningAlgorithm + " requires the failure model to be disabled");
                }
                if (!isNoOverhead(overheadModel)) {
                    throw new IllegalArgumentException(planningAlgorithm + " requires OverheadModelConfig.none()");
                }
                if (!dataMovementModel.isLegacyWorkflowsimV1()) {
                    throw new IllegalArgumentException(planningAlgorithm + " currently requires "
                            + "DataMovementModel.legacyWorkflowsimV1() so planning and execution "
                            + "transfer estimates remain aligned");
                }
            }
            if ((planningAlgorithm == PlanningAlgorithm.IC_PCP || planningAlgorithm == PlanningAlgorithm.DVFS_SLACK)
                    && deadline <= 0L) {
                throw new IllegalArgumentException(planningAlgorithm + " requires a positive workflow deadline");
            }
        }

        private void validateTaskCostMatrix() {
            if (taskCostMatrix != null) {
                if (schedulingAlgorithm != SchedulingAlgorithm.STATIC) {
                    throw new IllegalArgumentException("A task cost matrix is applied only by the STATIC "
                            + "dispatch path; online schedulers would silently ignore it");
                }
                if (clusteringParameters.getClusteringMethod() != ClusteringParameters.ClusteringMethod.NONE) {
                    throw new IllegalArgumentException("A task cost matrix requires clustering method NONE "
                            + "so every Job maps to exactly one logical Task");
                }

                if (planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_HEFT
                        || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_CPOP
                        || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_DLS
                        || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_ETF
                        || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_PEFT
                        || planningAlgorithm == PlanningAlgorithm.CAWOSCHED_SLACK
                        || planningAlgorithm == PlanningAlgorithm.CAWOSCHED_PRESSURE
                        || planningAlgorithm == PlanningAlgorithm.NACO_GA_HEFT
                        || planningAlgorithm == PlanningAlgorithm.DHEFT
                        || planningAlgorithm == PlanningAlgorithm.GREEN_HEFT
                        || planningAlgorithm == PlanningAlgorithm.MO_HEFT
                        || planningAlgorithm == PlanningAlgorithm.IC_PCP
                        || planningAlgorithm == PlanningAlgorithm.DVFS_SLACK) {
                    throw new IllegalArgumentException("A task cost matrix cannot be combined with "
                            + planningAlgorithm + ": this planning track estimates "
                            + "execution times from raw task lengths and does not consume the matrix, "
                            + "while the STATIC dispatch path would fold it in at runtime "
                            + "(silent planning/runtime divergence)");
                }
            }
        }

        private void validateLocalPlanners() {

            if (planningAlgorithm == PlanningAlgorithm.LOCAL_HEFT
                    || planningAlgorithm == PlanningAlgorithm.LOCAL_CPOP
                    || planningAlgorithm == PlanningAlgorithm.LOCAL_PEFT
                    || planningAlgorithm == PlanningAlgorithm.NACO_GA_HEFT
                    || planningAlgorithm == PlanningAlgorithm.DHEFT) {
                String label = planningAlgorithm.name();
                if (fileSystem != ReplicaCatalog.FileSystem.LOCAL) {
                    throw new IllegalArgumentException(label + " requires ReplicaCatalog.FileSystem.LOCAL; "
                            + "inter-task communication is only modeled under the LOCAL file system");
                }
                if (clusteringParameters.getClusteringMethod() != ClusteringParameters.ClusteringMethod.NONE) {
                    throw new IllegalArgumentException(label + " requires clustering method NONE");
                }
                if (failureModel.isEnabled()) {
                    throw new IllegalArgumentException(label + " requires the failure model to be disabled");
                }
                if (!isNoOverhead(overheadModel)) {
                    throw new IllegalArgumentException(label + " requires OverheadModelConfig.none()");
                }
                if (!dataMovementModel.isPreExecutionTransferDelayV1()
                        && !dataMovementModel.isPreExecutionTransferDelayWithContentionV1()
                        && !dataMovementModel.isFatTreeContentionV1()) {
                    throw new IllegalArgumentException(label + " requires "
                            + "DataMovementModel.preExecutionTransferDelayV1() (no-contention planning estimate), "
                            + "preExecutionTransferDelayWithContentionV1() (runtime endpoint link "
                            + "contention, documented planner/execution divergence under "
                            + "concurrency), or fatTreeContentionV1() (runtime fat-tree path link "
                            + "contention, same divergence policy)");
                }
            }
        }

        private void validatePreExecutionPairing() {

            if ((dataMovementModel.isPreExecutionTransferDelayV1()
                            || dataMovementModel.isPreExecutionTransferDelayWithContentionV1()
                            || dataMovementModel.isFatTreeContentionV1())
                    && planningAlgorithm == PlanningAlgorithm.INVALID) {
                throw new IllegalArgumentException(dataMovementModel.getKind() + " requires a "
                        + "planning algorithm that assigns static VM mappings (e.g. LOCAL_HEFT, "
                        + "LOCAL_CPOP, LOCAL_PEFT, RANDOM); the INVALID planning layer leaves the "
                        + "destination VM unknown at release time");
            }
        }

        private void validateFatTreePremises() {

            if (dataMovementModel.isFatTreeContentionV1()) {
                if (fileSystem != ReplicaCatalog.FileSystem.LOCAL) {
                    throw new IllegalArgumentException(dataMovementModel.getKind()
                            + " requires ReplicaCatalog.FileSystem.LOCAL; inter-VM communication "
                            + "along fat-tree paths is only modeled under the LOCAL file system");
                }
                if (clusteringParameters.getClusteringMethod() != ClusteringParameters.ClusteringMethod.NONE) {
                    throw new IllegalArgumentException(
                            dataMovementModel.getKind() + " requires clustering method NONE");
                }
                if (failureModel.isEnabled()) {
                    throw new IllegalArgumentException(
                            dataMovementModel.getKind() + " requires the failure model to be disabled");
                }
                if (!isNoOverhead(overheadModel)) {
                    throw new IllegalArgumentException(
                            dataMovementModel.getKind() + " requires OverheadModelConfig.none()");
                }
            }
        }

        private void validateCoherentCombinations() {
            if (dataflowAssignmentConfig.isEnabled()
                    && (!dataMovementModel.usesCoherentDataflowRuntime()
                            || planningAlgorithm != PlanningAlgorithm.INVALID
                            || schedulingAlgorithm != SchedulingAlgorithm.STATIC)) {
                throw new IllegalArgumentException(
                        "Online dataflow binding requires coherent V2/V3, INVALID preplanning and STATIC bound-VM"
                                + "dispatch");
            }
            if (dataMovementModel.usesCoherentDataflowRuntime()) {
                if (planningAlgorithm
                                != (dataflowAssignmentConfig.isEnabled()
                                        ? PlanningAlgorithm.INVALID
                                        : PlanningAlgorithm.RANDOM)
                        || schedulingAlgorithm != SchedulingAlgorithm.STATIC
                        || (dataMovementModel.isCoherentFileDataflowV2()
                                && fileSystem != ReplicaCatalog.FileSystem.LOCAL)
                        || clusteringParameters.getClusteringMethod() != ClusteringParameters.ClusteringMethod.NONE
                        || !isNoOverhead(overheadModel)) {
                    throw new IllegalArgumentException(
                            "Coherent V2 initially requires RANDOM static mapping, STATIC dispatch, LOCAL, NONE"
                                    + "clustering and no overhead; existing LOCAL planners are not V2 estimators");
                }
            }
        }

        private static boolean isPositiveFinite(double value) {
            return value > 0.0 && !Double.isInfinite(value) && !Double.isNaN(value);
        }

        private static boolean isNoOverhead(OverheadModelConfig value) {
            return value.getWorkflowEngineDelayInterval() == 0
                    && value.getBandwidth() == 0.0
                    && value.getWorkflowEngineDelays().isEmpty()
                    && value.getQueueDelays().isEmpty()
                    && value.getPostDelays().isEmpty()
                    && value.getClusteringDelays().isEmpty();
        }
    }
}
