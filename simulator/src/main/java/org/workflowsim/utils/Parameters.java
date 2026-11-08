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
package org.workflowsim.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.platform.PlatformProfile;

/**
 * Historical global parameter repository of WorkflowSim.
 *
 * <p>This class survives as the static-state entry point for compatibility with the existing
 * WorkflowSim API. New experiment code should prefer an explicit session boundary built from
 * {@link SimulationConfig} and {@link SimulationSession}, so that configuration, random
 * streams, and replica state do not leak inside one JVM.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class Parameters {

    /** Online (local) scheduling algorithm. */
    public enum SchedulingAlgorithm {

        // Online ready-batch variants that fit the WorkflowSim event lifecycle.
        READY_BATCH_MINMIN,
        READY_BATCH_MAXMIN,
        READY_BATCH_MCT,
        READY_BATCH_ROUNDROBIN,
        // The original implementations were renamed; the values below are kept only for
        // binary and configuration compatibility.
        /**
         * @deprecated The actual behavior is SPT-fastest-idle, not classic Min-Min.
         *             Use READY_BATCH_MINMIN instead. AUD-F10: WorkflowScheduler binds this
         *             label only after Parameters.enableLegacySchedulingAliases()
         */
        @Deprecated
        MINMIN,
        /**
         * @deprecated The actual behavior is LJF-fastest-idle, not classic Max-Min.
         *             Use READY_BATCH_MAXMIN instead. AUD-F10: WorkflowScheduler binds this
         *             label only after Parameters.enableLegacySchedulingAliases()
         */
        @Deprecated
        MAXMIN,
        /**
         * @deprecated The actual behavior is greedy fastest VM, not classic MCT.
         *             Use READY_BATCH_MCT instead. AUD-F10: WorkflowScheduler binds this
         *             label only after Parameters.enableLegacySchedulingAliases()
         */
        @Deprecated
        MCT,
        /**
         * @deprecated vmIndex is dead code; the actual behavior is First-Fit-Idle.
         *             Use READY_BATCH_ROUNDROBIN instead. AUD-F10: WorkflowScheduler binds
         *             this label only after Parameters.enableLegacySchedulingAliases()
         */
        @Deprecated
        ROUNDROBIN,
        // The values below keep their existing scheduling semantics.
        DATA,
        STATIC,
        FCFS,
        /**
         * R4 RL track: policy-driven online scheduling. Each ready-batch update requests a
         * Job-to-VM action from the {@code RlPolicy} registered in {@code RlPolicyRegistry}. It
         * must run through {@code RlEnvironment.runEpisode} (the planning layer is INVALID);
         * running it directly through {@code SimulationRunner} fails explicitly because no
         * policy is registered.
         */
        RL_POLICY,
        /**
         * C1 (2026-11-02): carbon-aware deferred online scheduling. A ready Job is held back
         * until the next carbon window with higher green power as declared by the platform
         * (the online version of CWM time shifting). Each Job is deferred at most once; the
         * scheduler advances the clock by scheduling a wake-up event for the deferral period,
         * and a missing wake-up instant fails explicitly (it does not hang silently). A
         * {@code carbonWindows} budget-model declaration is required; without windows it
         * degrades to same-order FCFS dispatch.
         */
        CARBON_WINDOW_DEFER,
        /** CWS Rank(Min) + persistent Round-Robin over static DAG ready Jobs. */
        CWS_RANKMIN_RR,
        /** Reshi-C: direct-child-count priority with explicit task-node ranking. */
        RESHI_C,
        /** Reshi-M: historical-average-runtime priority with explicit task-node ranking. */
        RESHI_M,
        /** EBPSM multi-workflow budget and cache-aware scheduling. */
        EBPSM,
        /** STATIC dispatch with planner-selected per-task DVFS tiers. */
        DVFS_SLACK,
        /** CRCH synchronized-checkpoint recovery scheduling. */
        CRCH,
        INVALID
    }

    /** Offline (global) planning algorithm. */
    public enum PlanningAlgorithm {
        INVALID,
        RANDOM,
        /**
         * Offline baseline algorithms for independent task sets. They reject workflows with
         * dependency edges; DAG workflows should use a dedicated DAG planner.
         */
        STATIC_OLB,
        STATIC_MET,
        STATIC_MCT,
        STATIC_MINMIN,
        STATIC_MAXMIN,
        STATIC_SUFFERAGE,
        STATIC_ROUND_ROBIN,
        /**
         * Static DAG mapping algorithms whose estimators are deliberately aligned with the
         * current "shared storage, no clustering" WorkflowSim execution model.
         */
        SHARED_STORAGE_HEFT,
        SHARED_STORAGE_CPOP,
        SHARED_STORAGE_DLS,
        SHARED_STORAGE_ETF,
        SHARED_STORAGE_PEFT,
        /** CaWoSched fixed-plan time shifting with slack scoring and explicit timed dispatch. */
        CAWOSCHED_SLACK,
        /** CaWoSched fixed-plan time shifting with pressure scoring and explicit timed dispatch. */
        CAWOSCHED_PRESSURE,
        /** NACO 2023 hybrid GA/HEFT with DNC evaluation and named random streams. */
        NACO_GA_HEFT,
        /** GreenHEFT energy-first static HEFT planner using declared VM power profiles. */
        GREEN_HEFT,
        /** MOHEFT Pareto makespan-energy beam-search planner with balanced execution choice. */
        MO_HEFT,
        /** IC-PCP one-phase partial-critical-path deadline/cost planner. */
        IC_PCP,
        /** Network-Aware HEFT with shared-bottleneck flow stretching. */
        NETWORK_HEFT,
        /** HEFT allocation with iterative DVFS slack allocation. */
        DVFS_SLACK,
        /**
         * PSO (particle swarm optimization) mapping planner -- a paper reproduction (Pandey
         * et al., AINA 2010; reference open-source implementation meysamhit/workflowsim-pso).
         * Fitness is the sequential load model 0.8 * cost + 0.2 * makespan (dependency edges
         * ignored, faithfully retained from the reference implementation). At runtime it must
         * be paired with {@code SchedulingAlgorithm.STATIC} for execution mapping.
         */
        PSO,
        /**
         * Communication-aware HEFT static DAG planner (Topcuoglu, Hariri &amp; Wu, IEEE TPDS
         * 2002) aligned with the LOCAL file-system execution model: inter-task data transfer
         * mirrors the runtime historical rules bit for bit (SOURCE-to-VM bandwidth, min(bw)
         * between VMs, replica evolution); transfer is modeled as pre-execution network delay
         * that may overlap the VM busy period while the VM is occupied by compute only (the
         * paper's AST semantics). Requires the LOCAL file system, NONE clustering, no
         * failure/overhead, the {@code preExecutionTransferDelayV1} data-movement model, and
         * {@code SchedulingAlgorithm.STATIC} dispatch.
         */
        LOCAL_HEFT,
        /**
         * Communication-aware CPOP static DAG planner (from the same Topcuoglu, Hariri &amp;
         * Wu, IEEE TPDS 2002 work as HEFT): priority = upward rank + downward rank; the tasks
         * on the critical path are bound to the VM that minimizes the total critical-path
         * compute seconds, and the remaining tasks take the insertion-based minimum EFT.
         * Communication modeling, execution semantics, and preconditions are exactly the same
         * as {@link #LOCAL_HEFT}.
         */
        LOCAL_CPOP,
        /**
         * Communication-aware PEFT static DAG planner (Arabnejad &amp; Barbosa, IEEE TPDS
         * 2014): priority = descending average of the optimistic cost table OCT over all VMs,
         * and the VM is chosen to minimize {@code EFT + OCT(t, vm)}. The OCT recurrence uses
         * the real per-VM-pair LOCAL communication cost (c &gt; 0, as opposed to the link-free
         * degraded form of SHARED_STORAGE_PEFT). Communication modeling, execution semantics,
         * and preconditions are exactly the same as {@link #LOCAL_HEFT}.
         */
        LOCAL_PEFT
    }

    /** Workflow file type. */
    public enum FileType {
        NONE(0),
        INPUT(1),
        OUTPUT(2);
        public final int value;

        private FileType(int fType) {
            this.value = fType;
        }
    }

    /** Task execution stage type. */
    public enum ClassType {
        STAGE_IN(1),
        COMPUTE(2),
        STAGE_OUT(3),
        CLEAN_UP(4);
        public final int value;

        private ClassType(int cType) {
            this.value = cType;
        }
    }

    /**
     * Cost model.
     *
     * <p>{@code DATACENTER} uses datacenter-level cost, {@code VM} uses virtual-machine-level
     * cost.</p>
     */
    public enum CostModel {
        DATACENTER(1),
        VM(2);
        public final int value;

        private CostModel(int model) {
            this.value = model;
        }
    }

    /** Logical name of the source host (submission host). */
    public static String SOURCE = "source";

    public static final int BASE = 0;

    /** Current scheduling mode. */
    private static SchedulingAlgorithm schedulingAlgorithm;

    /**
     * BAT-3 (2026-10-26): third-party scheduling algorithm registered for this session;
     * null means the registered-algorithm track is not enabled.
     */
    private static org.workflowsim.experiment.registry.SchedulingAlgorithmProvider
            registeredSchedulingAlgorithmProvider;

    /**
     * AUD-F10: whether this session explicitly declared that the deprecated scheduling
     * labels run with their historical label-to-policy bindings.
     */
    private static boolean legacySchedulingAliasesEnabled;

    /** Current planning mode. */
    private static PlanningAlgorithm planningAlgorithm;

    /** Current job reduction mode. */
    private static String reduceMethod;
    /** Number of available virtual machines. */
    private static int vmNum;
    /** Physical path of a single DAX or JSON workflow input. */
    private static String daxPath;

    /** Physical paths of multiple DAX or JSON workflow inputs. */
    private static List<String> daxPaths;
    /**
     * External runtime file path.
     *
     * <p>The historical format is {@code ID1 1.0 ID2 2.0 ...}. The file is not needed when the
     * DAX already declares task runtimes.</p>
     */
    private static String runtimePath;
    /**
     * External data-size file path.
     *
     * <p>The historical format is {@code DATA1 1000 DATA2 2000 ...}. The file is not needed when
     * the DAX already declares file sizes.</p>
     */
    private static String datasizePath;
    /** Toolkit version number. */
    private static final String VERSION = "1.1.0";
    /** Historical version note. */
    private static final String NOTE = " supports planning algorithm at Nov 9, 2013";
    /** Legacy overhead parameters of the current session. */
    private static OverheadParameters oParams;
    /** Clustering parameters of the current session. */
    private static ClusteringParameters cParams;
    /** Workflow deadline; 0 means no deadline was requested. */
    private static long deadline;

    /** Logical bandwidth matrix between virtual machines. */
    private static double[][] bandwidths;

    /**
     * Maximum DAG depth, initialized by the parse/configuration path and consumed by
     * {@code FailureGenerator}.
     */
    private static int maxDepth;

    /** Historical placeholder value used when a string parameter is not set. */
    private static final String INVALID = "Invalid";

    /** Scaling factor applied after input runtimes are converted to model task lengths. */
    private static double runtime_scale = 1.0;

    /**
     * Reference throughput used to convert workflow runtimes expressed in seconds into CloudSim
     * MI.
     */
    private static double runtimeReferenceMips = 1000.0;

    /**
     * Root seed of all WorkflowSim-owned random components.
     *
     * <p>The default value makes an unchanged configuration reproducible, but research
     * experiments should still set the seed explicitly and record it in the results manifest.</p>
     */
    private static long randomSeed = 0L;

    /** Uses the CloudSim-consistent datacenter-level cost model by default. */
    private static CostModel costModel = CostModel.DATACENTER;

    /**
     * BAT-D2 (2026-10-29): DVFS frequency-level assignment policy in effect for the session.
     *
     * <p>The same session-managed legacy Parameters carrier as the scheduling algorithm enums
     * (the BAT-3 precedent): loaded by {@link SimulationSession#open} from
     * {@link SimulationConfig} and cleared back to the default by {@link #reset()}.</p>
     */
    private static SimulationConfig.FrequencyPolicy frequencyPolicy = SimulationConfig.FrequencyPolicy.DEFAULT;

    /**
     * R5 dynamic arrival: submission instant (in simulated seconds) of each workflow input,
     * one-to-one with the input paths.
     *
     * <p>{@code null} or all zeros means every input is submitted at t=0 (the historical
     * behavior).</p>
     */
    private static List<Double> workflowArrivalSeconds;
    /**
     * C1 (2026-11-02): green budget windows declared for this run (the source of truth of the
     * platform profile, injected by {@code SimulationRunner} within the session). An empty list
     * means no budget model was declared.
     */
    private static List<PlatformProfile.CarbonWindow> carbonWindows = Collections.emptyList();

    /**
     * Initializes the historical global parameters for a single-input workflow.
     *
     * <p>New code should use {@link SimulationSession} instead. This method still resets the
     * WorkflowSim-owned random stream assignment, so it must not be called in the middle of a
     * session.</p>
     *
     * @param vm number of virtual machines
     * @param dax DAX or JSON workflow path
     * @param runtime optional external runtime file path
     * @param datasize optional external data-size file path
     * @param op overhead parameters
     * @param cp clustering parameters
     * @param scheduler scheduling mode
     * @param planner planning mode
     * @param rMethod job reduction mode
     * @param dl workflow deadline
     */
    public static void init(
            int vm,
            String dax,
            String runtime,
            String datasize,
            OverheadParameters op,
            ClusteringParameters cp,
            SchedulingAlgorithm scheduler,
            PlanningAlgorithm planner,
            String rMethod,
            long dl) {

        cParams = cp;
        vmNum = vm;
        daxPath = dax;
        daxPaths = null;
        runtimePath = runtime;
        datasizePath = datasize;

        oParams = op;
        schedulingAlgorithm = scheduler;
        planningAlgorithm = planner;
        reduceMethod = rMethod;
        deadline = dl;
        maxDepth = 0;
        SimulationRandom.reset(randomSeed);
    }

    /**
     * Initializes the historical global parameters for a multi-input workflow.
     *
     * @param vm number of virtual machines
     * @param dax list of DAX or JSON workflow paths
     * @param runtime optional external runtime file path
     * @param datasize optional external data-size file path
     * @param op overhead parameters
     * @param cp clustering parameters
     * @param scheduler scheduling mode
     * @param planner planning mode
     * @param rMethod job reduction mode
     * @param dl workflow deadline
     */
    public static void init(
            int vm,
            List<String> dax,
            String runtime,
            String datasize,
            OverheadParameters op,
            ClusteringParameters cp,
            SchedulingAlgorithm scheduler,
            PlanningAlgorithm planner,
            String rMethod,
            long dl) {

        cParams = cp;
        vmNum = vm;
        daxPath = null;
        daxPaths = dax == null ? null : new ArrayList<>(dax);
        runtimePath = runtime;
        datasizePath = datasize;

        oParams = op;
        schedulingAlgorithm = scheduler;
        planningAlgorithm = planner;
        reduceMethod = rMethod;
        deadline = dl;
        maxDepth = 0;
        SimulationRandom.reset(randomSeed);
    }

    /** @return legacy overhead parameters of the current session */
    public static OverheadParameters getOverheadParams() {
        return oParams;
    }

    /** @return current job reduction mode; returns the historical placeholder {@code Invalid} when unset */
    public static String getReduceMethod() {
        if (reduceMethod != null) {
            return reduceMethod;
        } else {
            return INVALID;
        }
    }

    /** @return a single DAX or JSON workflow path; {@code null} for a multi-input configuration */
    public static String getDaxPath() {
        return daxPath;
    }

    /** @return optional external runtime file path */
    public static String getRuntimePath() {
        return runtimePath;
    }

    /** @return optional external data-size file path */
    public static String getDatasizePath() {
        return datasizePath;
    }

    /** @return number of currently available virtual machines */
    public static int getVmNum() {
        return vmNum;
    }

    /** @return current cost model */
    public static CostModel getCostModel() {
        return costModel;
    }

    /**
     * Sets the number of currently available virtual machines.
     *
     * @param num number of virtual machines
     */
    public static void setVmNum(int num) {
        vmNum = num;
    }

    /** @return current clustering parameters */
    public static ClusteringParameters getClusteringParameters() {
        return cParams;
    }

    /** @return current scheduling algorithm */
    public static SchedulingAlgorithm getSchedulingAlgorithm() {
        return schedulingAlgorithm;
    }

    /** BAT-3 (2026-10-26): sets the session-scoped third-party registered scheduling algorithm (cleared by reset()) */
    public static void setRegisteredSchedulingAlgorithmProvider(
            org.workflowsim.experiment.registry.SchedulingAlgorithmProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("Registered scheduling provider cannot be null");
        }
        registeredSchedulingAlgorithmProvider = provider;
    }

    /** @return session-scoped third-party registered scheduling algorithm; null means not enabled */
    public static org.workflowsim.experiment.registry.SchedulingAlgorithmProvider
            getRegisteredSchedulingAlgorithmProvider() {
        return registeredSchedulingAlgorithmProvider;
    }

    /**
     * AUD-F10: declares that this session runs the historical examples that still name the
     * deprecated scheduling labels.
     *
     * <p>The declaration is session state cleared by {@link #reset()}. It only unlocks the
     * historical label-to-policy bindings in WorkflowScheduler; it changes neither the
     * maintained labels nor the SimulationRunner rejection of the deprecated labels.</p>
     */
    public static void enableLegacySchedulingAliases() {
        legacySchedulingAliasesEnabled = true;
    }

    /** @return AUD-F10: whether the deprecated scheduling labels are explicitly declared legacy */
    public static boolean isLegacySchedulingAliasesEnabled() {
        return legacySchedulingAliasesEnabled;
    }

    /** @return current planning algorithm */
    public static PlanningAlgorithm getPlanningAlgorithm() {
        return planningAlgorithm;
    }
    /** @return toolkit version number */
    public static String getVersion() {
        return VERSION;
    }

    public static void printVersion() {
        Log.printLine("WorkflowSim Version: " + VERSION);
        Log.printLine("Change Note: " + NOTE);
    }
    /** @return current workflow deadline; 0 means not requested */
    public static long getDeadline() {
        return deadline;
    }

    /** @return currently recorded maximum DAG depth */
    public static int getMaxDepth() {
        return maxDepth;
    }

    /**
     * Sets the currently recorded maximum DAG depth.
     *
     * @param depth maximum depth
     */
    public static void setMaxDepth(int depth) {
        maxDepth = depth;
    }

    /**
     * Sets the runtime scaling factor.
     *
     * @param scale finite and positive scaling factor
     */
    public static void setRuntimeScale(double scale) {
        if (scale <= 0.0 || Double.isInfinite(scale) || Double.isNaN(scale)) {
            throw new IllegalArgumentException("Runtime scale must be finite and positive");
        }
        runtime_scale = scale;
    }

    /**
     * Sets the cost model.
     *
     * @param model datacenter-level or virtual-machine-level cost model
     */
    public static void setCostModel(CostModel model) {
        if (model == null) {
            throw new IllegalArgumentException("Cost model cannot be null");
        }
        costModel = model;
    }

    /**
     * BAT-D2 (2026-10-29): sets the DVFS frequency-level assignment policy in effect for the
     * session (cleared back to the default by reset()).
     *
     * @param policy policy enum, must not be {@code null}
     */
    public static void setFrequencyPolicy(SimulationConfig.FrequencyPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Frequency policy cannot be null");
        }
        frequencyPolicy = policy;
    }

    /** BAT-D2: @return session DVFS frequency-level assignment policy; default {@code DEFAULT} (v1 degraded) */
    public static SimulationConfig.FrequencyPolicy getFrequencyPolicy() {
        return frequencyPolicy;
    }

    /** @return current runtime scaling factor */
    public static double getRuntimeScale() {
        return runtime_scale;
    }

    /**
     * Sets the reference VM throughput the parser uses to convert runtimes expressed in seconds
     * into MI.
     *
     * @param mips finite and positive reference throughput, in MI/s
     */
    public static void setRuntimeReferenceMips(double mips) {
        if (mips <= 0.0 || Double.isInfinite(mips) || Double.isNaN(mips)) {
            throw new IllegalArgumentException("Runtime reference MIPS must be finite and positive");
        }
        runtimeReferenceMips = mips;
    }

    /** @return reference throughput used for the seconds-to-MI conversion, in MI/s */
    public static double getRuntimeReferenceMips() {
        return runtimeReferenceMips;
    }

    /**
     * Sets the root seed used by WorkflowSim-owned random models.
     *
     * <p>It should be called before building overhead or failure distributions; every call starts
     * a new named random stream context.</p>
     *
     * @param seed root seed of one experiment
     */
    public static void setRandomSeed(long seed) {
        randomSeed = seed;
        SimulationRandom.reset(seed);
    }

    /** @return root seed configured for the current experiment */
    public static long getRandomSeed() {
        return randomSeed;
    }

    /**
     * Restores all WorkflowSim-owned global parameters to their default values.
     *
     * <p>This method does not reset the CloudSim kernel; use {@link SimulationSession} when a
     * full serial run boundary is required.</p>
     */
    public static void reset() {
        SOURCE = "source";
        schedulingAlgorithm = SchedulingAlgorithm.INVALID;
        registeredSchedulingAlgorithmProvider = null;
        legacySchedulingAliasesEnabled = false;
        planningAlgorithm = PlanningAlgorithm.INVALID;
        reduceMethod = null;
        vmNum = 0;
        daxPath = null;
        daxPaths = null;
        runtimePath = null;
        datasizePath = null;
        oParams = null;
        cParams = null;
        deadline = 0L;
        bandwidths = null;
        maxDepth = 0;
        runtime_scale = 1.0;
        runtimeReferenceMips = 1000.0;
        randomSeed = 0L;
        costModel = CostModel.DATACENTER;
        frequencyPolicy = SimulationConfig.FrequencyPolicy.DEFAULT;
        workflowArrivalSeconds = null;
        carbonWindows = Collections.emptyList();
        ReshiRegistry.clear();
        EbpsmRegistry.clear();
        DvfsSlackRegistry.clear();
        CrchRegistry.clear();
        SimulationRandom.reset(randomSeed);
    }

    /**
     * C1 (2026-11-02): sets the green budget windows for this run (session scope).
     *
     * @param windows platform-declared window list; {@code null} is treated as undeclared
     */
    public static void setCarbonWindows(List<PlatformProfile.CarbonWindow> windows) {
        carbonWindows = windows == null
                ? Collections.<PlatformProfile.CarbonWindow>emptyList()
                : Collections.unmodifiableList(new ArrayList<PlatformProfile.CarbonWindow>(windows));
    }

    /** @return green budget windows of this run; an empty list when undeclared (never returns {@code null}) */
    public static List<PlatformProfile.CarbonWindow> getCarbonWindows() {
        return carbonWindows;
    }

    /** @return unmodifiable multi-input path list; {@code null} for a single-input configuration */
    public static List<String> getDAXPaths() {
        return daxPaths == null ? null : Collections.unmodifiableList(daxPaths);
    }

    /**
     * Sets the submission instant (in simulated seconds) of each workflow input, one-to-one with
     * the input paths.
     *
     * @param arrivalSeconds submission instant of each input; {@code null} means all at t=0
     */
    public static void setWorkflowArrivalSeconds(List<Double> arrivalSeconds) {
        workflowArrivalSeconds = arrivalSeconds == null ? null : new ArrayList<>(arrivalSeconds);
    }

    /** @return submission instants of each input in simulated seconds; {@code null} when unconfigured */
    public static List<Double> getWorkflowArrivalSeconds() {
        return workflowArrivalSeconds == null ? null : Collections.unmodifiableList(workflowArrivalSeconds);
    }
}
