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

/**
 * Immutable configuration of one serial WorkflowSim experiment.
 *
 * <p>WorkflowSim and CloudSim retain historical static state, so this object must be applied
 * through {@link SimulationSession}; it does not mean the same JVM supports concurrent
 * simulation. The configuration explicitly records the inputs, resource scale, algorithms,
 * random seed, time conversion, failure, and data movement model for run-manifest
 * traceability.</p>
 */
public final class SimulationConfig {

    /**
     * BAT-D2 (2026-10-29): configuration-level DVFS frequency-tier assignment policy
     * (docs/advanced/DVFS_DIMENSION_DESIGN.md section 2.2).
     *
     * <p>{@code DEFAULT} (the default value when nothing is configured, the v1 degenerate
     * case): every compute Job runs at the <b>first tier</b> of the VM frequency table (the
     * platform declaration order is the default tier) -- a platform with no frequency tier at
     * all behaves bit-identically to v1; {@code MIN_POWER}: every compute Job runs at the
     * <b>last tier</b> (lowest frequency, upper bound on power saving / lower bound on speed);
     * {@code EDP_HEURISTIC}: greedily minimize EDP over the candidate tiers at dispatch time
     * (v1 estimates each Job independently, and the globally optimal EDP is an NP-hard
     * cross-Job problem left to a registered DVFS-aware algorithm track).</p>
     */
    public enum FrequencyPolicy {
        /** Every Job runs at the first tier of the frequency table (= the platform default tier). */
        DEFAULT,
        /** Every Job runs at the last tier of the frequency table (lowest MIPS). */
        MIN_POWER,
        /** Greedily pick the minimum-EDP tier at each Job dispatch (v1 estimates each Job independently). */
        EDP_HEURISTIC
    }

    /**
     * AUD-F07 follow-up (2026-11-04, post-completion review): default retention cap of the run's
     * event recorder. Kept numerically equal to SimulationEventRecorder.DEFAULT_MAX_EVENTS by the
     * drift lock of SimulationConfigEventRecorderCapTest; this class deliberately does not
     * import that class -- utils must not grow a new outgoing edge to the experiment package.
     */
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
    private final int eventRecorderCap;

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
        this.eventRecorderCap = builder.eventRecorderCap;
    }

    /**
     * Create a builder from a single workflow input.
     *
     * @param workflowPath workflow input file path
     * @param vmCount number of available virtual machines
     * @return a new configuration builder
     */
    public static Builder builder(String workflowPath, int vmCount) {
        return new Builder(Collections.singletonList(workflowPath), vmCount);
    }

    public static Builder builder(List<String> workflowPaths, int vmCount) {
        return new Builder(workflowPaths, vmCount);
    }

    /** @return an unmodifiable list of workflow input file paths */
    public List<String> getWorkflowPaths() {
        return workflowPaths;
    }

    /**
     * @return the submission instant of each workflow input (simulated seconds), in one-to-one
     *         correspondence with {@link #getWorkflowPaths()}; when all are 0.0 the behavior is
     *         bit-identical to single-instant submission
     */
    public List<Double> getWorkflowArrivalSeconds() {
        return workflowArrivalSeconds;
    }

    /** @return number of available virtual machines */
    public int getVmCount() {
        return vmCount;
    }

    /** @return reproducible scheduling and execution overhead model */
    public OverheadModelConfig getOverheadModel() {
        return overheadModel;
    }

    /** @return task clustering parameters */
    public ClusteringParameters getClusteringParameters() {
        return clusteringParameters;
    }

    /** @return online scheduling or static dispatch algorithm */
    public SchedulingAlgorithm getSchedulingAlgorithm() {
        return schedulingAlgorithm;
    }

    /** BAT-3 (2026-10-26): @return provider of the registered algorithm track; null means not enabled. */
    public org.workflowsim.experiment.registry.SchedulingAlgorithmProvider getRegisteredSchedulingAlgorithmProvider() {
        return registeredSchedulingAlgorithmProvider;
    }

    /** @return offline planning algorithm; {@code INVALID} when not enabled */
    public PlanningAlgorithm getPlanningAlgorithm() {
        return planningAlgorithm;
    }

    /** @return job reduction method; {@code null} when not specified */
    public String getReduceMethod() {
        return reduceMethod;
    }

    /** @return workflow deadline; 0 means no deadline was requested */
    public long getDeadline() {
        return deadline;
    }

    /** @return shared or local file system model used by the replica catalog */
    public ReplicaCatalog.FileSystem getFileSystem() {
        return fileSystem;
    }

    /** @return root random seed of this experiment */
    public long getRandomSeed() {
        return randomSeed;
    }

    /** @return scaling factor applied to convert input runtimes to model lengths */
    public double getRuntimeScale() {
        return runtimeScale;
    }

    /** @return reference MIPS used to convert input runtimes expressed in seconds to MI */
    public double getRuntimeReferenceMips() {
        return runtimeReferenceMips;
    }

    /** @return CloudSim minimum event interval explicitly set for this run, in simulated seconds */
    public double getCloudSimMinEventIntervalSeconds() {
        return cloudSimMinEventIntervalSeconds;
    }

    /** @return datacenter-level or VM-level cost model */
    public CostModel getCostModel() {
        return costModel;
    }

    /** @return failure model configuration */
    public FailureModelConfig getFailureModel() {
        return failureModel;
    }

    /** @return explicit file transfer time model adopted by this run */
    public DataMovementModel getDataMovementModel() {
        return dataMovementModel;
    }

    /** @return immutable run-local network evidence option; OFF by default */
    public NetworkEvidenceConfig getNetworkEvidenceConfig() {
        return networkEvidenceConfig;
    }
    /** @return explicit control-ready destination-binding policy, OFF by default */
    public org.workflowsim.data.v2.DataflowAssignmentConfig getDataflowAssignmentConfig() {
        return dataflowAssignmentConfig;
    }

    /** @return optional task-by-VM heterogeneous execution cost matrix; {@code null} when not configured */
    public TaskCostMatrix getTaskCostMatrix() {
        return taskCostMatrix;
    }

    /**
     * BAT-D2 (2026-10-29): @return the DVFS frequency-tier assignment policy of this run;
     * defaults to {@code DEFAULT} (a v1-compatible run on a platform without frequency tiers
     * never changes behavior silently)
     */
    public FrequencyPolicy getFrequencyPolicy() {
        return frequencyPolicy;
    }

    /**
     * AUD-F07 follow-up (2026-11-04, post-completion review): @return the retention cap of the
     * run's event recorder. A run that reaches the cap fails fast with an actionable exception
     * instead of keeping an unbounded resident event list.
     */
    public int getEventRecorderCap() {
        return eventRecorderCap;
    }

    /**
     * Return a builder initialized with every current configuration value.
     *
     * <p>Experiment campaigns use it to explicitly change the seed of each repetition without
     * relying on mutable global {@link Parameters} state.</p>
     *
     * @return a builder pre-filled with every current configuration value
     */
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
                .frequencyPolicy(frequencyPolicy);
    }

    /**
     * Return an equivalent configuration that differs only in the root random seed.
     *
     * <p>The root seed alone does not guarantee common random numbers; the latter also requires
     * that the random models allocate randomness by stable event keys.</p>
     *
     * @param value new root random seed
     * @return an immutable configuration differing only in the seed
     */
    public SimulationConfig withRandomSeed(long value) {
        return toBuilder().randomSeed(value).build();
    }

    /**
     * Configuration builder, producing a no-overhead deterministic run configuration by
     * default.
     *
     * <p>Building checks the algorithm combination, the inputs, the numeric ranges, and the
     * model premises of the shared-storage DAG planners. When a premise is not met it fails
     * immediately instead of silently changing the experiment semantics.</p>
     */
    public static final class Builder {

        private final List<String> workflowPaths;
        private List<Double> workflowArrivalSeconds;
        private final int vmCount;
        private OverheadModelConfig overheadModel = OverheadModelConfig.none();
        private ClusteringParameters clusteringParameters =
                new ClusteringParameters(0, 0, ClusteringParameters.ClusteringMethod.NONE, null);
        private SchedulingAlgorithm schedulingAlgorithm = SchedulingAlgorithm.FCFS;

        /**
         * BAT-3 (2026-10-26): third-party SPI scheduling track validated by the algorithm
         * registry; null means not enabled.
         */
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

        /** @param value explicit online destination-binding option, independent of network recording @return this
         * builder */
        public Builder dataflowAssignment(org.workflowsim.data.v2.DataflowAssignmentConfig value) {
            dataflowAssignmentConfig = value;
            return this;
        }

        private TaskCostMatrix taskCostMatrix;

        /**
         * BAT-D2 (2026-10-29): DVFS frequency-tier assignment policy; defaults to DEFAULT (the
         * v1 degenerate tier).
         */
        private FrequencyPolicy frequencyPolicy = FrequencyPolicy.DEFAULT;

        /**
         * AUD-F07 follow-up (2026-11-04, post-completion review): retention cap of the run's
         * event recorder; defaults to {@code DEFAULT_EVENT_RECORDER_CAP}.
         */
        private int eventRecorderCap = DEFAULT_EVENT_RECORDER_CAP;

        private Builder(List<String> workflowPaths, int vmCount) {
            if (workflowPaths == null || workflowPaths.isEmpty()) {
                throw new IllegalArgumentException("At least one workflow input is required");
            }
            this.workflowPaths = new ArrayList<>(workflowPaths);
            // R5 default: all workflows are submitted at t=0 -- bit-identical to the historical
            // single-instant submission.
            this.workflowArrivalSeconds = new ArrayList<>(Collections.nCopies(workflowPaths.size(), 0.0));
            this.vmCount = vmCount;
        }

        /**
         * Build the model from legacy mutable overhead parameters.
         *
         * @deprecated use {@link #overheadModel(OverheadModelConfig)}. This compatibility bridge
         *             only extracts the distribution family and its parameters and resamples
         *             them in-session from the root seed of the current run.
         * @param value legacy overhead parameters
         * @return this builder
         */
        @Deprecated
        public Builder overheadParameters(OverheadParameters value) {
            this.overheadModel = OverheadModelConfig.fromLegacy(value);
            return this;
        }

        /** @param value overhead model @return this builder */
        public Builder overheadModel(OverheadModelConfig value) {
            this.overheadModel = value;
            return this;
        }

        /**
         * BAT-D2 (2026-10-29): set the DVFS frequency-tier assignment policy.
         *
         * @param value policy enum, must not be {@code null} (leaving it unset means the default
         *              {@code DEFAULT})
         * @return this builder
         * @throws IllegalArgumentException when {@code value} is {@code null}
         */
        public Builder frequencyPolicy(FrequencyPolicy value) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "Frequency policy cannot be null; leave it unset for the DEFAULT default");
            }
            this.frequencyPolicy = value;
            return this;
        }

        /**
         * AUD-F07 follow-up (2026-11-04, post-completion review): set the retention cap of the
         * run's event recorder. Before this outlet the cap was a Java-only constructor argument
         * (SimulationEventRecorder(int)), so a config-driven run that legitimately needed more
         * than the default 20000 events failed with no adjustment path.
         *
         * @param value retention cap, strictly positive (leaving it unset means the default
         *              {@code DEFAULT_EVENT_RECORDER_CAP})
         * @return this builder
         * @throws IllegalArgumentException when {@code value} is not strictly positive
         */
        public Builder eventRecorderCap(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("Event recorder cap must be strictly positive: " + value);
            }
            this.eventRecorderCap = value;
            return this;
        }

        /** @param value clustering parameters @return this builder */
        public Builder clusteringParameters(ClusteringParameters value) {
            this.clusteringParameters = value;
            return this;
        }

        /** @param value online scheduling or static dispatch algorithm @return this builder */
        public Builder schedulingAlgorithm(SchedulingAlgorithm value) {
            this.schedulingAlgorithm = value;
            return this;
        }

        /**
         * BAT-3 (2026-10-26): enable the third-party registered scheduling track. A registered
         * algorithm occupies the pure online scheduling layer: the schedulingAlgorithm and
         * planningAlgorithm enums must stay INVALID (dispatch is carried by the provider's
         * policy instance), and AlgorithmRegistry validates the combination premise fail-fast.
         */
        public Builder registeredSchedulingAlgorithm(
                org.workflowsim.experiment.registry.SchedulingAlgorithmProvider provider) {
            this.registeredSchedulingAlgorithmProvider = provider;
            return this;
        }

        /** @param value offline planning algorithm @return this builder */
        public Builder planningAlgorithm(PlanningAlgorithm value) {
            this.planningAlgorithm = value;
            return this;
        }

        /** @param value job reduction method @return this builder */
        public Builder reduceMethod(String value) {
            this.reduceMethod = value;
            return this;
        }

        /** @param value deadline; 0 means not requested @return this builder */
        public Builder deadline(long value) {
            this.deadline = value;
            return this;
        }

        /** @param value file system model @return this builder */
        public Builder fileSystem(ReplicaCatalog.FileSystem value) {
            this.fileSystem = value;
            return this;
        }

        /** @param value root random seed @return this builder */
        public Builder randomSeed(long value) {
            this.randomSeed = value;
            return this;
        }

        /** @param value runtime scaling factor @return this builder */
        public Builder runtimeScale(double value) {
            this.runtimeScale = value;
            return this;
        }

        /** @param value reference throughput in MI/s @return this builder */
        public Builder runtimeReferenceMips(double value) {
            this.runtimeReferenceMips = value;
            return this;
        }

        /** @param value CloudSim minimum event interval in simulated seconds @return this builder */
        public Builder cloudSimMinEventIntervalSeconds(double value) {
            this.cloudSimMinEventIntervalSeconds = value;
            return this;
        }

        /** @param value cost model @return this builder */
        public Builder costModel(CostModel value) {
            this.costModel = value;
            return this;
        }

        /** @param value failure model @return this builder */
        public Builder failureModel(FailureModelConfig value) {
            this.failureModel = value;
            return this;
        }

        /** @param value file data movement model @return this builder */
        public Builder dataMovementModel(DataMovementModel value) {
            this.dataMovementModel = value;
            return this;
        }

        /**
         * Select bounded observation of an existing fluid model, without changing physics.
         * @param value required immutable option; use off() to disable
         * @return this builder
         */
        public Builder networkEvidence(NetworkEvidenceConfig value) {
            this.networkEvidenceConfig = value;
            return this;
        }

        /**
         * Set the optional task-by-VM heterogeneous execution cost matrix
         * (paper-reproduction support).
         *
         * @param value task-by-VM execution cost matrix; null means not configured (the
         *              MI/mips scaling is kept)
         * @return this builder
         */
        public Builder taskCostMatrix(TaskCostMatrix value) {
            this.taskCostMatrix = value;
            return this;
        }

        /**
         * R5: set the submission instant of each workflow input (dynamic arrival).
         *
         * @param value submission instant list in one-to-one correspondence with the input
         *              paths (simulated seconds)
         * @return this builder
         */
        public Builder workflowArrivalSeconds(List<Double> value) {
            if (value == null) {
                throw new IllegalArgumentException("Workflow arrival seconds cannot be null; "
                        + "use an explicit list of zeros for simultaneous submission");
            }
            this.workflowArrivalSeconds = new ArrayList<>(value);
            return this;
        }

        /**
         * Validate every model premise and create an immutable configuration.
         *
         * @return a configuration usable with {@link SimulationSession}
         * @throws IllegalArgumentException when the inputs, numeric values, or algorithm
         *                                  combination are invalid
         */
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

        /** Workflow input path non-empty check (moved verbatim from build()). */
        private void validateWorkflowPaths() {
            for (String path : workflowPaths) {
                if (path == null || path.trim().isEmpty()) {
                    throw new IllegalArgumentException("Workflow input paths cannot be empty");
                }
            }
        }

        /** Required-model null check and network-evidence compatibility check (moved verbatim). */
        private void validateRequiredModels() {
            // Validate required models before any cross-model condition dereferences them.
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

        /**
         * Arrival-time convention, and the R5 mutual exclusion of a non-zero arrival with
         * non-NONE clustering (moved verbatim).
         */
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
            // R5: arrival gating attributes a workflow by its original task ids; clustering
            // generates new Job IDs that cannot be attributed reliably, so non-zero submission
            // instants are mutually exclusive with non-NONE clustering (explicit rejection
            // instead of silent failure).
            if (hasNonZeroArrival
                    && clusteringParameters.getClusteringMethod() != ClusteringParameters.ClusteringMethod.NONE) {
                throw new IllegalArgumentException("Non-zero workflow arrival seconds require "
                        + "clustering method NONE: clustered jobs no longer carry the original "
                        + "task ids used for arrival gating");
            }
        }

        /** Numeric scalar bound check (moved verbatim). */
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

        /**
         * Scheduling/planning algorithm combination check (moved verbatim), including the
         * PLAT-7 DATA/SHARED rejection.
         */
        private void validateSchedulerCombinations() {
            if (planningAlgorithm != PlanningAlgorithm.INVALID && schedulingAlgorithm != SchedulingAlgorithm.STATIC) {
                throw new IllegalArgumentException("A planning algorithm assigns VM mappings and requires "
                        + "SchedulingAlgorithm.STATIC dispatch");
            }
            if (planningAlgorithm == PlanningAlgorithm.INVALID
                    && schedulingAlgorithm == SchedulingAlgorithm.STATIC
                    && !dataflowAssignmentConfig.isEnabled()) {
                throw new IllegalArgumentException(
                        "SchedulingAlgorithm.STATIC requires a planning algorithm " + "that assigns VM mappings");
            }
            // PLAT-7: the DATA algorithm matches replica sites by VM ID for its locality verdict,
            // while replicas under SHARED mode are registered by datacenter name; the two never
            // match -- DATA would silently degenerate to first-fit-idle and produce misleading
            // "data locality" experimental results, so the configuration layer rejects it outright.
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

        /** Shared-storage planner premise check (moved verbatim). */
        private void validateSharedStoragePlanners() {
            if (planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_HEFT
                    || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_CPOP
                    || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_DLS
                    || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_ETF
                    || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_PEFT
                    || planningAlgorithm == PlanningAlgorithm.CAWOSCHED_SLACK
                    || planningAlgorithm == PlanningAlgorithm.CAWOSCHED_PRESSURE) {
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
            // The task-by-VM cost matrix is folded in by the STATIC dispatch path
            // (MI = seconds * vm.mips); an online scheduler does not fold it in, so the matrix
            // would be silently ignored; folding also requires a one-to-one correspondence
            // between jobs and logical tasks (NONE clustering).
        }

        /**
         * Task-by-VM cost matrix premise check (moved verbatim), including the R8 audit fix
         * P1-1.
         */
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
                // R8 audit fix (algorithm track P1-1): the planning side of
                // SharedStorageDagPlanner derives execution times from raw DAX lengths only and
                // does not consume the cost matrix projection, while the runtime dispatch path
                // folds MI through the matrix -- the silent semantic divergence between the two
                // sides would produce a wrong planning order without any error. The
                // configuration layer rejects this combination outright.
                if (planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_HEFT
                        || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_CPOP
                        || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_DLS
                        || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_ETF
                        || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_PEFT
                        || planningAlgorithm == PlanningAlgorithm.CAWOSCHED_SLACK
                        || planningAlgorithm == PlanningAlgorithm.CAWOSCHED_PRESSURE) {
                    throw new IllegalArgumentException("A task cost matrix cannot be combined with "
                            + planningAlgorithm + ": the shared-storage planning track estimates "
                            + "execution times from raw task lengths and does not consume the matrix, "
                            + "while the STATIC dispatch path would fold it in at runtime "
                            + "(silent planning/runtime divergence)");
                }
            }
        }

        /** LOCAL list-scheduler planner premise check (moved verbatim). */
        private void validateLocalPlanners() {
            // LOCAL list planning uses parallel transfer of parent groups and a contention-free
            // estimate with replicas carrying availability instants; it is not a full replay of
            // runtime events. These model premises are checked separately in the configuration
            // layer and in the planner. The runtime of the contention variant is additionally
            // constrained by endpoint/link capacity and by a fixed per-VM order, so planning
            // instants cannot be claimed to be bit-identical to an arbitrary preExecution track.
            if (planningAlgorithm == PlanningAlgorithm.LOCAL_HEFT
                    || planningAlgorithm == PlanningAlgorithm.LOCAL_CPOP
                    || planningAlgorithm == PlanningAlgorithm.LOCAL_PEFT) {
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

        /** Pre-execution transfer and planner pairing check (moved verbatim). */
        private void validatePreExecutionPairing() {
            // Pre-execution transfer delay (including the link-contention models) estimates and
            // registers replicas against the destination VM when a task becomes ready, which
            // requires the planner to complete a static VM mapping during planning; the INVALID
            // planning layer has no destination VM at release time.
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

        /** Fat-tree topology contention premise check (moved verbatim). */
        private void validateFatTreePremises() {
            // Fat-tree link contention model: VM-to-VM transfers occupy shared links along
            // deterministic routes; the premises are the LOCAL file system (so VM-to-VM
            // communication is modeled), a static mapping (the path is known at ready time),
            // NONE clustering, and no failure / no overhead (the same premise set as the
            // LOCAL_HEFT track). The existence of the platform-side topology declaration is
            // checked by the standard runner (PlatformProfile.networkTopology).
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

        /** Online data binding and coherent runtime combination check (moved verbatim). */
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
