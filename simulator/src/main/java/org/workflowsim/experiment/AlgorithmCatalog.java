package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;

/**
 * Machine-readable semantic contract of the selectable algorithms.
 *
 * <p>The catalog records the decision scope and the regression verification status of the
 * current implementation; it does not constitute a claim of real-platform calibration or of
 * general algorithmic superiority.</p>
 */
public final class AlgorithmCatalog {

    private AlgorithmCatalog() {}

    /**
     * Tests whether a scheduler label has a maintained execution contract in the standard
     * {@link SimulationRunner} entry point.
     *
     * <p>This predicate is deliberately independent of the catalog's ability to describe
     * historical labels. Centralizing the classification here avoids a silent divergence
     * between the runner and the manifest contract when a new enum value is added.</p>
     *
     * @param algorithm scheduler algorithm label to test
     * @return {@code true} when the label can produce research evidence through the standard
     *         runner
     * @throws IllegalArgumentException when {@code algorithm} is {@code null}
     */
    public static boolean isSupportedBySimulationRunner(SchedulingAlgorithm algorithm) {
        if (algorithm == null) {
            throw new IllegalArgumentException("Scheduling algorithm is required");
        }
        switch (algorithm) {
            case FCFS:
            case READY_BATCH_MINMIN:
            case READY_BATCH_MAXMIN:
            case READY_BATCH_MCT:
            case READY_BATCH_ROUNDROBIN:
            case DATA:
            case STATIC:
            case RL_POLICY:
            case CARBON_WINDOW_DEFER:
            case CWS_RANKMIN_RR:
                return true;
            case MINMIN:
            case MAXMIN:
            case MCT:
            case ROUNDROBIN:
            case INVALID:
            default:
                return false;
        }
    }

    /**
     * Tests whether a planner label has a maintained execution contract in the standard
     * {@link SimulationRunner} entry point.
     *
     * <p>An online scheduling configuration deliberately has no planning phase, so
     * {@code INVALID} is a supported state here.</p>
     *
     * @param algorithm planner algorithm label to test
     * @return {@code true} when the label can be used through the standard runner
     * @throws IllegalArgumentException when {@code algorithm} is {@code null}
     */
    public static boolean isSupportedBySimulationRunner(PlanningAlgorithm algorithm) {
        if (algorithm == null) {
            throw new IllegalArgumentException("Planning algorithm is required");
        }
        switch (algorithm) {
            case INVALID:
            case RANDOM:
            case STATIC_OLB:
            case STATIC_MET:
            case STATIC_MCT:
            case STATIC_MINMIN:
            case STATIC_MAXMIN:
            case STATIC_SUFFERAGE:
            case STATIC_ROUND_ROBIN:
                return true;
            default:
                return isSupportedResearchPlanningContract(algorithm);
        }
    }

    /**
     * 2026-10-26 complexity extraction from isSupportedBySimulationRunner(PlanningAlgorithm); statements verbatim;
     * order unchanged. Scope: research-catalog planner labels (shared-storage, PSO, LOCAL families).
     */
    private static boolean isSupportedResearchPlanningContract(PlanningAlgorithm algorithm) {
        switch (algorithm) {
            case SHARED_STORAGE_HEFT:
            case SHARED_STORAGE_CPOP:
            case SHARED_STORAGE_DLS:
            case SHARED_STORAGE_ETF:
            case SHARED_STORAGE_PEFT:
            case CAWOSCHED_SLACK:
            case CAWOSCHED_PRESSURE:
            case NACO_GA_HEFT:
            case PSO:
            case LOCAL_HEFT:
            case LOCAL_CPOP:
            case LOCAL_PEFT:
                return true;
            default:
                return false;
        }
    }

    /**
     * Cost-comparison caliber of a (planning, scheduling) label pair (AUD-F09).
     *
     * <p>The two calibers answer the same "which VM runs this task next" question with two
     * different cost quantities, so a comparison that places both calibers side by side in
     * one comparison group compares two different cost semantics under the same-named
     * algorithms. The catalog classifies the caliber; it changes neither caliber's own
     * comparison semantics.</p>
     */
    public enum CostComparisonCaliber {

        /** Mapping decisions compare the bare {@code length/MIPS} ECT of ready tasks. */
        ONLINE_READY_BATCH,

        /** Mapping decisions compare TaskExecutionModel matrix seconds. */
        STATIC_PLANNING,

        /** Mapping decisions make no mapping-cost comparison at all. */
        NONE
    }

    /**
     * Classifies which cost quantity a (planning, scheduling) label pair uses for its
     * mapping decisions (AUD-F09).
     *
     * <p>A planner-decided cell compares TaskExecutionModel matrix seconds regardless of
     * its scheduling label, an online ready-batch cell compares the bare {@code length/MIPS}
     * ECT, and every other maintained combination makes no mapping-cost comparison.</p>
     *
     * @param planning planner label of the cell
     * @param scheduling scheduler label of the cell
     * @return the cost-comparison caliber the cell's mapping decisions use
     * @throws IllegalArgumentException when either label is {@code null}
     */
    public static CostComparisonCaliber costComparisonCaliber(
            PlanningAlgorithm planning, SchedulingAlgorithm scheduling) {
        if (planning == null) {
            throw new IllegalArgumentException("Planning algorithm is required");
        }
        if (scheduling == null) {
            throw new IllegalArgumentException("Scheduling algorithm is required");
        }
        // A planner-decided cell compares TaskExecutionModel matrix seconds; through the
        // legacy Parameters carrier such a cell can still declare a non-STATIC scheduling
        // label, so the caliber must follow the planning label, not the scheduling label.
        if (planning != PlanningAlgorithm.INVALID) {
            return CostComparisonCaliber.STATIC_PLANNING;
        }
        switch (scheduling) {
            case READY_BATCH_MINMIN:
            case READY_BATCH_MAXMIN:
            case READY_BATCH_MCT:
            case READY_BATCH_ROUNDROBIN:
                return CostComparisonCaliber.ONLINE_READY_BATCH;
            default:
                return CostComparisonCaliber.NONE;
        }
    }

    /**
     * Returns the scheduler and planner contract written into every run manifest.
     *
     * @param config immutable configuration of this run
     * @return machine-readable algorithm contract organized in a fixed field order
     * @throws IllegalArgumentException when {@code config} is {@code null}
     */
    public static Map<String, Object> forConfiguration(SimulationConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Simulation configuration is required");
        }
        Map<String, Object> contract = new LinkedHashMap<String, Object>();
        Map<String, Object> dispatch = scheduler(config.getSchedulingAlgorithm());
        if (config.getDataflowAssignmentConfig().isEnabled()) {
            dispatch.put("decisionLayer", "BOUND_DATAFLOW_CPU_DISPATCH");
            dispatch.put("decisionRule", "Dispatch data-ready Jobs to their previously committed VM only when idle");
            dispatch.put(
                    "limitations",
                    limitations("Control-ready destination assignment is a separate explicit phase; STATIC cannot remap"
                            + "compute inputs already transferred."));
            Map<String, Object> assignment = new LinkedHashMap<>();
            assignment.put(
                    "mode", config.getDataflowAssignmentConfig().getMode().name());
            assignment.put("policy", config.getDataflowAssignmentConfig().getPolicy());
            assignment.put("decisionLayer", "CONTROL_READY_VM_BINDING_BEFORE_INPUT_TRANSFER");
            assignment.put(
                    "limitations",
                    limitations("Nominal remaining-input and CPU-reservation estimates are myopic, not contention-exact"
                            + "completion forecasts; NOOP retries reuse the logical Task binding."));
            contract.put("dataflowAssignment", assignment);
        }
        contract.put("scheduler", dispatch);
        contract.put("planner", planner(config.getPlanningAlgorithm()));
        contract.put(
                "scopeStatement",
                "Algorithm labels describe this WorkflowSim implementation "
                        + "and its declared abstract-model scope; standard SimulationRunner evidence uses "
                        + "SPACE_SHARED VMs and no task clustering, and does not establish real-platform calibration.");
        contract.put("reproductionStandard", "CORE_DECISION_SEMANTICS_UNDER_DECLARED_MODEL");
        return contract;
    }

    private static Map<String, Object> scheduler(SchedulingAlgorithm algorithm) {
        Map<String, Object> values = base(algorithm.name());
        switch (algorithm) {
            case CARBON_WINDOW_DEFER:
                values.put("decisionLayer", "ONLINE_READY_JOB_WITH_DECLARED_WINDOW_DEFERRAL");
                values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
                values.put(
                        "decisionRule",
                        "Defer the whole ready batch to the next declared carbon window with strictly"
                                + " higher green power (each Job at most once), then dispatch FCFS to the"
                                + " smallest idle compatible VM");
                values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
                values.put(
                        "limitations",
                        limitations("Deferral is driven by the declared window table, not by a live fleet-load"
                                + " forecast; it trades completion time for lower excess and the engine"
                                + " makes no superiority claim."));
                break;
            case FCFS:
                values.put("decisionLayer", "ONLINE_READY_JOB");
                values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
                values.put("decisionRule", "First-ready-job, first-idle-VM dispatch");
                values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
                values.put("limitations", limitations("Orders ready Jobs, not raw Tasks or an offline DAG schedule."));
                break;
            case READY_BATCH_ROUNDROBIN:
                values.put("decisionLayer", "ONLINE_READY_JOB");
                values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
                values.put("decisionRule", "Retained round-robin cursor over idle VMs for each ready batch");
                values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
                values.put(
                        "limitations",
                        limitations("The batch is the runtime-ready Job set, not a static independent-task batch."));
                break;
            case CWS_RANKMIN_RR:
                values.put("decisionLayer", "ONLINE_READY_JOB_DAG_RANK");
                values.put("inputDomain", "STATIC_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
                values.put(
                        "decisionRule", "CWS descendant-PROCESS rank descending, input bytes ascending, persistent RR");
                values.put("verification", "PAPER_FIGURE_AND_COMPARATOR_REGRESSION_COVERED");
                values.put("limitations", limitations("Static-DAG/idle-VM adaptation only; not the dynamic CWS API"));
                break;
            case READY_BATCH_MCT:
                readyBatchMctScheduler(values);
                break;
            case READY_BATCH_MINMIN:
                values.put("decisionLayer", "ONLINE_READY_JOB");
                values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
                values.put("decisionRule", "Ready-batch iterative minimum completion-time selection");
                values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
                values.put("limitations", limitations("Not the offline independent-task Min-Min heuristic."));
                break;
            case READY_BATCH_MAXMIN:
                values.put("decisionLayer", "ONLINE_READY_JOB");
                values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
                values.put("decisionRule", "Ready-batch iterative maximum of each Job's minimum completion time");
                values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
                values.put("limitations", limitations("Not the offline independent-task Max-Min heuristic."));
                break;
            case DATA:
                dataScheduler(values);
                break;
            case STATIC:
                values.put("decisionLayer", "STATIC_MAPPING_DISPATCH");
                values.put("inputDomain", "MAPPED_JOBS_ONLY");
                values.put("decisionRule", "Dispatches a Job to the VM assigned by the planning layer");
                values.put("verification", "REGRESSION_COVERED_WITH_STATIC_MCT");
                values.put(
                        "limitations",
                        limitations("A SimulationConfig requires a non-INVALID planner; model-generated stage-in Jobs"
                                + "use the legacy fallback mapping."));
                break;
            case RL_POLICY:
                rlPolicyScheduler(values);
                break;
            case MINMIN:
                legacyScheduler(values, "SPT-fastest-idle ready-job variant", "READY_BATCH_MINMIN");
                break;
            case MAXMIN:
                legacyScheduler(values, "LJF-fastest-idle ready-job variant", "READY_BATCH_MAXMIN");
                break;
            case MCT:
                legacyScheduler(values, "Greedy fastest-idle-VM ready-job variant", "READY_BATCH_MCT");
                break;
            case ROUNDROBIN:
                legacyScheduler(values, "First-fit-idle ready-job variant", "READY_BATCH_ROUNDROBIN");
                break;
            case INVALID:
            default:
                values.put("decisionLayer", "NONE");
                values.put("inputDomain", "NOT_A_RUNNABLE_RESEARCH_CONFIGURATION");
                values.put("decisionRule", "No explicit scheduler selected");
                values.put("verification", "NOT_APPLICABLE");
                values.put("limitations", limitations("Use an explicit non-INVALID scheduler."));
                break;
        }
        return values;
    }

    /** Catalog switch branch (READY_BATCH_MCT).
     * Verbatim relocation: put order fixes the JSON key order and must not change.
     */
    private static void readyBatchMctScheduler(Map<String, Object> values) {
        values.put("decisionLayer", "ONLINE_READY_JOB");
        values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
        values.put("decisionRule", "Ready-batch minimum estimated completion time under current idle-VM state");
        values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
        values.put(
                "limitations",
                limitations(
                        "Not the offline independent-task MCT heuristic; "
                                + "it does not reserve future VM availability.",
                        "For a fixed positive Job length and the same idle compatible candidates, "
                                + "minimizing length/MIPS is equivalent to choosing maximum MIPS."));
    }

    /** Catalog switch branch (DATA).
     * Verbatim relocation: put order fixes the JSON key order and must not change.
     */
    private static void dataScheduler(Map<String, Object> values) {
        values.put("decisionLayer", "ONLINE_READY_JOB");
        values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
        values.put(
                "decisionRule",
                "Selects a compatible VM with the fewest required nonlocal input bytes; "
                        + "equal totals use lower VM ID");
        values.put("verification", "DATA_LOCALITY_BASELINE_REGRESSION_COVERED");
        values.put(
                "limitations",
                limitations(
                        "Input byte counts are not transfer times; this scheduler does not "
                                + "consult DataMovementModel endpoint latency or transfer rates.",
                        "No calibrated network, replica, or bandwidth-contention model is implied."));
    }

    /** Catalog switch branch (RL_POLICY).
     * Verbatim relocation: put order fixes the JSON key order and must not change.
     */
    private static void rlPolicyScheduler(Map<String, Object> values) {
        values.put("decisionLayer", "ONLINE_READY_JOB");
        values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
        values.put(
                "decisionRule",
                "Registered RlPolicy selects a VM index per ready Job "
                        + "(RlEnvironment episode; reward = negative makespan)");
        values.put("verification", "RL_EPISODE_ENVIRONMENT_REGRESSION_COVERED");
        values.put(
                "limitations",
                limitations(
                        "The policy is an external decision function; the platform "
                                + "guarantees deterministic episodes and action validation only.",
                        "Actions on busy/incompatible/reserved VMs skip the Job for that update; no built-in "
                                + "learning algorithm is provided.",
                        "Must run through RlEnvironment.runEpisode; SimulationRunner alone fails without a "
                                + "registered policy."));
    }

    private static void legacyScheduler(Map<String, Object> values, String rule, String replacement) {
        values.put("decisionLayer", "ONLINE_READY_JOB");
        values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
        values.put("decisionRule", rule);
        values.put("verification", "LEGACY_COMPATIBILITY_ONLY_NOT_SUPPORTED_BY_SIMULATION_RUNNER");
        values.put(
                "limitations",
                limitations("The standard SimulationRunner rejects this label because it is not "
                        + "the classical offline heuristic; use " + replacement
                        + " for the maintained online variant."));
    }

    private static Map<String, Object> planner(PlanningAlgorithm algorithm) {
        Map<String, Object> values = base(algorithm.name());
        switch (algorithm) {
            case RANDOM:
                randomPlanner(values);
                break;
            case SHARED_STORAGE_HEFT:
                sharedStorageHeftPlanner(values);
                break;
            case SHARED_STORAGE_CPOP:
                sharedStorageCpopPlanner(values);
                break;
            case SHARED_STORAGE_DLS:
                sharedStorageDlsPlanner(values);
                break;
            case SHARED_STORAGE_ETF:
                sharedStorageEtfPlanner(values);
                break;
            case SHARED_STORAGE_PEFT:
                sharedStoragePeftPlanner(values);
                break;
            case CAWOSCHED_SLACK:
                caWoSchedPlanner(values, "SLACK");
                break;
            case CAWOSCHED_PRESSURE:
                caWoSchedPlanner(values, "PRESSURE");
                break;
            case NACO_GA_HEFT:
                nacoGaHeftPlanner(values);
                break;
            case PSO:
                psoPlanner(values);
                break;
            case LOCAL_HEFT:
                localHeftPlanner(values);
                break;
            case LOCAL_CPOP:
                localCpopPlanner(values);
                break;
            case LOCAL_PEFT:
                localPeftPlanner(values);
                break;
            default:
                independentOrNonePlannerContract(algorithm, values);
                break;
        }
        return values;
    }

    private static void nacoGaHeftPlanner(Map<String, Object> values) {
        values.put("decisionLayer", "STATIC_DAG_HYBRID_GA_HEFT");
        values.put("inputDomain", "LOCAL_DAG_WITH_DNC_STORAGE_NETWORK_COMPUTE");
        values.put("decisionRule", "Elite HEFT seed, GPX crossover, mutation, tournament and elite replacement");
        values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
        values.put("verification", "DNC_AND_GA_REGRESSION_COVERED");
        values.put(
                "limitations",
                limitations(
                        "NACO DNC adapter is not the full paper experimental pipeline or real-platform validation"));
    }

    private static void caWoSchedPlanner(Map<String, Object> values, String score) {
        values.put("decisionLayer", "STATIC_DAG_TIMED_POST_PLANNING");
        values.put("inputDomain", "SHARED_DAG_WITH_COMPLETE_CARBON_WINDOWS");
        values.put("decisionRule", "SHARED HEFT mapping/order followed by CaWoSched " + score + " timed starts");
        values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
        values.put("verification", "TIMED_PLAN_AND_DEFERRAL_REGRESSION_COVERED");
        values.put(
                "limitations",
                limitations("Compute-only SHARED adaptation; no communication-enhanced runtime or ILP claim"));
    }

    /**
     * 2026-10-26 complexity extraction from planner(); statements verbatim; order unchanged.
     * Scope: STATIC_* independent-task planner branches and the shared INVALID/default contract.
     */
    private static void independentOrNonePlannerContract(PlanningAlgorithm algorithm, Map<String, Object> values) {
        switch (algorithm) {
            case STATIC_OLB:
                independentPlanner(values, "Opportunistic Load Balancing based on least predicted VM availability");
                break;
            case STATIC_MET:
                independentPlanner(values, "Minimum Execution Time mapping ignoring predicted VM availability");
                break;
            case STATIC_MCT:
                independentPlanner(values, "Minimum Completion Time mapping with predicted VM availability");
                break;
            case STATIC_MINMIN:
                independentPlanner(values, "Classical iterative global minimum of per-task minimum completion times");
                break;
            case STATIC_MAXMIN:
                independentPlanner(values, "Classical iterative global maximum of per-task minimum completion times");
                break;
            case STATIC_SUFFERAGE:
                independentPlanner(values, "Iterative maximum loss between best and second-best completion times");
                break;
            case STATIC_ROUND_ROBIN:
                independentPlanner(values, "Deterministic ascending-VM-ID cyclic mapping");
                break;
            case INVALID:
            default:
                values.put("decisionLayer", "NONE");
                values.put("inputDomain", "NO_STATIC_VM_MAPPING");
                values.put("decisionRule", "No planning layer");
                values.put("verification", "NOT_APPLICABLE");
                values.put(
                        "limitations",
                        limitations("Use an online scheduler or select a planner with STATIC dispatch."));
                break;
        }
    }

    /** Catalog switch branch (RANDOM).
     * Verbatim relocation: put order fixes the JSON key order and must not change.
     */
    private static void randomPlanner(Map<String, Object> values) {
        values.put("decisionLayer", "STATIC_DAG_VM_MAPPING");
        values.put("inputDomain", "VALID_DAG");
        values.put("decisionRule", "Seeded random Task-to-compatible-VM mapping before clustering");
        values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
        values.put("verification", "DETERMINISTIC_MAPPING_REGRESSION_COVERED");
        values.put(
                "limitations",
                limitations("Runtime dependencies remain engine-enforced; this is not an offline timing schedule."));
    }

    /** Catalog switch branch (SHARED_STORAGE_HEFT).
     * Verbatim relocation: put order fixes the JSON key order and must not change.
     */
    private static void sharedStorageHeftPlanner(Map<String, Object> values) {
        values.put("decisionLayer", "STATIC_DAG_VM_MAPPING_AND_PER_VM_ORDER");
        values.put("inputDomain", "VALID_DAG_SHARED_STORAGE_NO_CLUSTERING");
        values.put(
                "decisionRule",
                "HEFT upward rank with insertion-based earliest finish time; "
                        + "per-task shared-storage input delay is added for every VM");
        values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
        values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
        values.put("reproductionScope", "CORE_HEFT_DECISION_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL");
        values.put(
                "limitations",
                limitations(
                        "Requires SHARED storage, NONE clustering, disabled overhead/failure, SPACE_SHARED VMs, and"
                                + "legacyWorkflowsimV1; task cost matrices are rejected. Placements must satisfy"
                                + "task PE requirements.",
                        "It is aligned to the current abstract shared-storage model, not calibrated to a real"
                                + "storage or network system."));
    }

    /** Catalog switch branch (SHARED_STORAGE_CPOP).
     * Verbatim relocation: put order fixes the JSON key order and must not change.
     */
    private static void sharedStorageCpopPlanner(Map<String, Object> values) {
        values.put("decisionLayer", "STATIC_DAG_VM_MAPPING_AND_PER_VM_ORDER");
        values.put("inputDomain", "VALID_DAG_SHARED_STORAGE_NO_CLUSTERING");
        values.put(
                "decisionRule",
                "CPOP upward-plus-downward rank priority; an identified critical path is assigned to the"
                        + "minimum-cost processor");
        values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
        values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
        values.put("reproductionScope", "CORE_CPOP_DECISION_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL");
        values.put(
                "limitations",
                limitations(
                        "Requires SHARED storage, NONE clustering, disabled overhead/failure, SPACE_SHARED VMs, and"
                                + "legacyWorkflowsimV1; task cost matrices are rejected. Placements must satisfy"
                                + "task PE requirements.",
                        "It is aligned to the current abstract shared-storage model, not calibrated to a real"
                                + "storage or network system."));
    }

    /** Catalog switch branch (SHARED_STORAGE_DLS).
     * Verbatim relocation: put order fixes the JSON key order and must not change.
     */
    private static void sharedStorageDlsPlanner(Map<String, Object> values) {
        values.put("decisionLayer", "STATIC_DAG_VM_MAPPING_AND_PER_VM_ORDER");
        values.put("inputDomain", "VALID_DAG_SHARED_STORAGE_NO_CLUSTERING");
        values.put(
                "decisionRule",
                "Dynamic-level selection over dependency-ready Task-VM candidates: "
                        + "upward-rank static level minus insertion-based earliest start time; "
                        + "equal dynamic levels use lower Task ID, then lower VM ID");
        values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
        values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
        values.put("reproductionScope", "CORE_DLS_DYNAMIC_LEVEL_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL");
        values.put(
                "limitations",
                limitations(
                        "Requires SHARED storage, NONE clustering, disabled overhead/failure, SPACE_SHARED VMs, and"
                                + "legacyWorkflowsimV1; task cost matrices are rejected. Placements must satisfy"
                                + "task PE requirements.",
                        "It adapts DLS to the current shared-storage model and does not reproduce irregular"
                                + "interconnect topology, routing, or communication contention.",
                        "It is not calibrated to a real storage or network system."));
    }

    /** Catalog switch branch (SHARED_STORAGE_ETF).
     * Verbatim relocation: put order fixes the JSON key order and must not change.
     */
    private static void sharedStorageEtfPlanner(Map<String, Object> values) {
        values.put("decisionLayer", "STATIC_DAG_VM_MAPPING_AND_PER_VM_ORDER");
        values.put("inputDomain", "VALID_DAG_SHARED_STORAGE_NO_CLUSTERING");
        values.put(
                "decisionRule",
                "Earliest Task First selection over dependency-ready Task-VM candidates: "
                        + "choose the minimum insertion-based earliest start time; equal starts use higher "
                        + "upward-rank static b-level, then lower Task ID and VM ID");
        values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
        values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
        values.put("reproductionScope", "CORE_ETF_EARLIEST_START_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL");
        values.put(
                "limitations",
                limitations(
                        "Requires SHARED storage, NONE clustering, disabled overhead/failure, SPACE_SHARED VMs, and"
                                + "legacyWorkflowsimV1; task cost matrices are rejected. Placements must satisfy"
                                + "task PE requirements.",
                        "It adapts ETF to the current shared-storage model and does not reproduce the original"
                                + "communication-delay or homogeneous-processor assumptions.",
                        "It is not calibrated to a real storage or network system."));
    }

    /** Catalog switch branch (SHARED_STORAGE_PEFT).
     * Verbatim relocation: put order fixes the JSON key order and must not change.
     */
    private static void sharedStoragePeftPlanner(Map<String, Object> values) {
        values.put("decisionLayer", "STATIC_DAG_VM_MAPPING_AND_PER_VM_ORDER");
        values.put("inputDomain", "VALID_DAG_SHARED_STORAGE_NO_CLUSTERING");
        values.put(
                "decisionRule",
                "Predict Earliest Finish Time: dependency-ready Task with "
                        + "maximum compatible-VM average optimistic cost rank, followed by the VM "
                        + "that minimizes insertion-based earliest finish plus optimistic successor cost");
        values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
        values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
        values.put(
                "reproductionScope",
                "CORE_PEFT_OCT_LOOKAHEAD_SEMANTICS_" + "ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL");
        values.put(
                "limitations",
                limitations(
                        "Requires SHARED storage, NONE clustering, disabled overhead/failure, SPACE_SHARED VMs, and"
                                + "legacyWorkflowsimV1; task cost matrices are rejected. Placements must satisfy"
                                + "task PE requirements.",
                        "The controlled model has no interprocessor communication term, topology, routing, or link"
                                + "contention; OCT is therefore a successor look-ahead rather than a network"
                                + "prediction.",
                        "It is not calibrated to a real storage or network system."));
    }

    /** Catalog switch branch (PSO).
     * Verbatim relocation: put order fixes the JSON key order and must not change.
     */
    private static void psoPlanner(Map<String, Object> values) {
        values.put("decisionLayer", "STATIC_DAG_VM_MAPPING");
        values.put("inputDomain", "VALID_DAG");
        values.put(
                "decisionRule",
                "Particle swarm optimization over PE-compatible Task-to-VM mappings: "
                        + "fitness = 0.8 * sequential-load execution cost (price = mips/1000) "
                        + "+ 0.2 * per-VM load makespan; effective compute seconds use the supplied "
                        + "task/VM matrix rounded to MI, or raw per-PE length/MIPS without a matrix; "
                        + "population 30, 100 iterations, w=0.7, c1=c2=1.5, seeded named random stream");
        values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
        values.put("verification", "PAPER_REPRODUCTION_REGRESSION_COVERED");
        values.put(
                "reproductionScope",
                "CORE_PSO_DECISION_SEMANTICS_OF_PANDEY_AINA_2010_" + "PER_OPENSOURCE_WORKFLOWSIM_PSO_REFERENCE");
        values.put(
                "limitations",
                limitations(
                        "Fitness uses the reference implementation's sequential VM-load model and "
                                + "ignores DAG dependency edges; planning-side makespan estimates may differ "
                                + "from runtime makespan under engine-enforced dependencies.",
                        "The cost model is the abstract price = mips/1000 heuristic, not calibrated cloud pricing.",
                        "Only without a task/VM matrix is cost mathematically mapping-invariant "
                                + "(sum of raw per-PE task lengths / 1000). With a matrix, "
                                + "effective execution time and the cost term may depend on the assigned VM.",
                        "Incompatible particle positions are projected to the nearest compatible VM-list index; "
                                + "equal distances use lower VM ID. No additional random draws are introduced.",
                        "Random stream comes from the campaign root seed, not the reference's hardcoded seed 42."));
    }

    /** Catalog switch branch (LOCAL_HEFT).
     * Verbatim relocation: put order fixes the JSON key order and must not change.
     */
    private static void localHeftPlanner(Map<String, Object> values) {
        values.put("decisionLayer", "STATIC_DAG_VM_MAPPING_AND_PER_VM_ORDER");
        values.put("inputDomain", "VALID_DAG_LOCAL_FILE_SYSTEM_NO_CLUSTERING");
        values.put(
                "decisionRule",
                "HEFT compatible-VM upward rank with insertion-based earliest finish time; "
                        + "inter-task transfer is modeled per VM pair as bytes/(1e6 × min(bw_src, bw_dst)). "
                        + "Per-parent transfer estimates start at parent finish, external inputs at dependency "
                        + "readiness with destination bandwidth. Timestamped replicas prevent future-locality "
                        + "discounts; transfer holds may overlap VM busy time and reservations contain compute only");
        values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
        values.put("verification", "PAPER_REPRODUCTION_REGRESSION_COVERED");
        values.put(
                "reproductionScope",
                "CORE_HEFT_DECISION_SEMANTICS_OF_TOPCUOGLU_TPDS_2002_"
                        + "ADAPTED_TO_CONTROLLED_LOCAL_FILE_SYSTEM_MODEL");
        values.put(
                "limitations",
                limitations(
                        "Requires LOCAL file system, NONE clustering, disabled overhead/failure, "
                                + "a preExecution-family data movement model, SPACE_SHARED VMs, and PE-compatible"
                                + "placements.",
                        "Compute seconds use TaskExecutionModel: rounded task/VM matrix costs when present, "
                                + "otherwise raw per-PE task length/MIPS.",
                        "Replica availability is timestamped: inputs become visible at the complete input hold end, "
                                + "outputs at planned compute finish. Candidate estimates only use replicas "
                                + "available at dependency readiness; external inputs, including roots, transfer from"
                                + "that time.",
                        "This is a partial-plan estimate, not a full runtime event replay. Same-time event ordering, "
                                + "replicas produced by later-planned tasks, short compute completions and contention "
                                + "can cause planning/runtime differences.",
                        "Planning estimates use contention-free VM-pair bandwidth. Runtime may use endpoint "
                                + "or Fat-tree max-min contention; transfers start at Job readiness in those variants.",
                        "Bootstrap includes the 110-MI stage-in Job and a kernel release interval. "
                                + "The platform-level stage-in replica is not a preloaded destination-VM copy; "
                                + "root external inputs still incur their own transfer hold.",
                        "Tasks shorter than the minimum event interval plus completion safety margin can drift "
                                + "from their planned completion under the runtime completion-event rule."));
    }

    /** Catalog switch branch (LOCAL_CPOP).
     * Verbatim relocation: put order fixes the JSON key order and must not change.
     */
    private static void localCpopPlanner(Map<String, Object> values) {
        values.put("decisionLayer", "STATIC_DAG_VM_MAPPING_AND_PER_VM_ORDER");
        values.put("inputDomain", "VALID_DAG_LOCAL_FILE_SYSTEM_NO_CLUSTERING");
        values.put(
                "decisionRule",
                "CPOP priority ru+rd; rd(t)=max_parent(rd(parent)+meanCompute(parent)+meanCommunication(parent,t)); "
                        + "critical-path edges satisfy the upward-rank recurrence and constant critical priority; "
                        + "critical tasks use one minimum-total-compute VM compatible with the entire path; "
                        + "other tasks use insertion EFT over their compatible VMs");
        values.put("implementationRevision", "CPOP_PREDECESSOR_RANK_TIGHT_PATH_V2");
        values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
        values.put("verification", "PAPER_REPRODUCTION_REGRESSION_COVERED");
        values.put(
                "reproductionScope",
                "CORE_CPOP_DECISION_SEMANTICS_OF_TOPCUOGLU_TPDS_2002_"
                        + "ADAPTED_TO_CONTROLLED_LOCAL_FILE_SYSTEM_MODEL");
        values.put(
                "limitations",
                limitations(
                        "Requires LOCAL file system, NONE clustering, disabled overhead/failure, "
                                + "a preExecution-family data movement model, SPACE_SHARED VMs, and PE-compatible"
                                + "placements.",
                        "Compute seconds use TaskExecutionModel: rounded task/VM matrix costs when present, "
                                + "otherwise raw per-PE task length/MIPS.",
                        "Replica availability is timestamped: inputs become visible at the complete input hold end, "
                                + "outputs at planned compute finish. Candidate estimates only use replicas "
                                + "available at dependency readiness; external inputs, including roots, transfer from"
                                + "that time.",
                        "This is a partial-plan estimate, not a full runtime event replay. Same-time event ordering, "
                                + "replicas produced by later-planned tasks, short compute completions and contention "
                                + "can cause planning/runtime differences.",
                        "Planning estimates use contention-free VM-pair bandwidth. Runtime may use endpoint "
                                + "or Fat-tree max-min contention; transfers start at Job readiness in those variants.",
                        "Bootstrap includes the 110-MI stage-in Job and a kernel release interval. "
                                + "The platform-level stage-in replica is not a preloaded destination-VM copy; "
                                + "root external inputs still incur their own transfer hold.",
                        "Positive transfer holds use the minimum event interval; integer-MI rounding and runtime"
                                + "completion ordering remain explicit model boundaries."));
    }

    /** Catalog switch branch (LOCAL_PEFT).
     * Verbatim relocation: put order fixes the JSON key order and must not change.
     */
    private static void localPeftPlanner(Map<String, Object> values) {
        values.put("decisionLayer", "STATIC_DAG_VM_MAPPING_AND_PER_VM_ORDER");
        values.put("inputDomain", "VALID_DAG_LOCAL_FILE_SYSTEM_NO_CLUSTERING");
        values.put(
                "decisionRule",
                "PEFT optimistic cost table "
                        + "OCT(t,p)=max_child min_p'[OCT(child,p')+w(child,p')+c(t,child,p,p')], "
                        + "with OCT(exit,p)=0; same-VM communication is zero, otherwise the LOCAL "
                        + "model uses bytes/(1e6 × min(bw_p,bw_p')). Select the dependency-ready task "
                        + "with highest compatible-VM mean OCT (ties use lower task ID), then the "
                        + "compatible VM minimizing insertion EFT+OCT (ties use lower VM ID).");
        values.put("implementationRevision", "PEFT_SUCCESSOR_COST_OCT_EXIT_ZERO_V2");
        values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
        values.put("verification", "PAPER_REPRODUCTION_REGRESSION_COVERED");
        values.put(
                "reproductionScope",
                "CORE_PEFT_DECISION_SEMANTICS_OF_ARABNEJAD_BARBOSA_TPDS_2014_"
                        + "ADAPTED_TO_CONTROLLED_LOCAL_FILE_SYSTEM_MODEL");
        values.put(
                "limitations",
                limitations(
                        "Requires LOCAL file system, NONE clustering, disabled overhead/failure, "
                                + "a preExecution-family data movement model, SPACE_SHARED VMs, and PE-compatible"
                                + "placements.",
                        "Compute seconds use TaskExecutionModel: rounded task/VM matrix costs when present, "
                                + "otherwise raw per-PE task length/MIPS.",
                        "Replica availability is timestamped: inputs become visible at the complete input hold end, "
                                + "outputs at planned compute finish. Candidate estimates only use replicas "
                                + "available at dependency readiness; external inputs, including roots, transfer from"
                                + "that time.",
                        "This is a partial-plan estimate, not a full runtime event replay. Same-time event ordering, "
                                + "replicas produced by later-planned tasks, short compute completions and contention "
                                + "can cause planning/runtime differences.",
                        "OCT is a static pre-scheduling quantity computed with contention-free VM-pair bandwidth "
                                + "and no replica-locality discount; runtime stage-in transfers may exploit evolved "
                                + "replicas and endpoint/Fat-tree contention variants change transfer timing.",
                        "Bootstrap includes the 110-MI stage-in Job and a kernel release interval. "
                                + "The platform-level stage-in replica is not a preloaded destination-VM copy; "
                                + "root external inputs still incur their own transfer hold.",
                        "Positive transfer holds use the minimum event interval; integer-MI rounding and runtime"
                                + "completion ordering remain explicit model boundaries.",
                        "Mean-OCT priority is not necessarily topological under heterogeneous compute costs; "
                                + "a dependency-ready list schedules every parent before its children, "
                                + "including cases where a child has higher mean OCT.",
                        "Successor-cost Eq. (7), exit zero, OEFT and ready-list semantics are verified "
                                + "against the author's open article chapter (Porto handle 10216/92290, "
                                + "printed pages 71-74). The article fixture has PEFT/HEFT makespans 122/133 "
                                + "before bootstrap; the older HEFT-origin fixture is separate.",
                        "Earlier LOCAL_PEFT revisions used current-task cost and an exit mean, not original PEFT. "
                                + "The paper uses average communication under uniform links; heterogeneous "
                                + "endpoint bandwidth and replica effects remain declared LOCAL model adaptations."));
    }

    private static void independentPlanner(Map<String, Object> values, String rule) {
        values.put("decisionLayer", "STATIC_INDEPENDENT_TASK_VM_MAPPING");
        values.put("inputDomain", "INDEPENDENT_TASKS_ONLY");
        values.put("decisionRule", rule);
        values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
        values.put("verification", "DETERMINISTIC_REGRESSION_COVERED");
        values.put(
                "limitations",
                limitations(
                        "Fails fast when any parent or child dependency exists.",
                        "Candidate VMs must satisfy the Task's PE requirement. Time-aware strategies use "
                                + "effective integer-MI compute seconds from TaskExecutionModel; a present matrix "
                                + "is authoritative and missing coordinates do not fall back to raw lengths.",
                        "STATIC_ROUND_ROBIN is intentionally cost-oblivious. These planners produce a VM mapping, "
                                + "not a complete offline execution trace or a communication model."));
    }

    private static Map<String, Object> base(String id) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("id", id);
        return values;
    }

    private static List<String> limitations(String... values) {
        return new ArrayList<String>(Arrays.asList(values));
    }
}
