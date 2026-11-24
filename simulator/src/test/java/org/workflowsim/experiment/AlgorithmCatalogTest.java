package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class AlgorithmCatalogTest {

    @Test
    void independentTaskPlannerContractIsExplicitAndMachineReadable() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.STATIC_MINMIN)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();

        Map<String, Object> contract = AlgorithmCatalog.forConfiguration(config);
        Map<String, Object> scheduler = nested(contract, "scheduler");
        Map<String, Object> planner = nested(contract, "planner");

        assertEquals("STATIC_MAPPING_DISPATCH", scheduler.get("decisionLayer"));
        assertEquals("STATIC_MINMIN", planner.get("id"));
        assertEquals("STATIC_INDEPENDENT_TASK_VM_MAPPING", planner.get("decisionLayer"));
        assertEquals("INDEPENDENT_TASKS_ONLY", planner.get("inputDomain"));
        assertEquals("STATIC", planner.get("requiredScheduler"));
        assertEquals("DETERMINISTIC_REGRESSION_COVERED", planner.get("verification"));
        assertEquals("CORE_DECISION_SEMANTICS_UNDER_DECLARED_MODEL", contract.get("reproductionStandard"));
    }

    @Test
    void sharedStorageHeftDeclaresItsControlledExecutionDomain() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();

        Map<String, Object> planner = nested(AlgorithmCatalog.forConfiguration(config), "planner");

        assertEquals("VALID_DAG_SHARED_STORAGE_NO_CLUSTERING", planner.get("inputDomain"));
        assertEquals("CONTROLLED_MODEL_REGRESSION_COVERED", planner.get("verification"));
        assertEquals(
                "CORE_HEFT_DECISION_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL",
                planner.get("reproductionScope"));
    }

    @Test
    void sharedStorageCpopDeclaresItsCriticalProcessorRule() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_CPOP)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();

        Map<String, Object> planner = nested(AlgorithmCatalog.forConfiguration(config), "planner");

        assertEquals("VALID_DAG_SHARED_STORAGE_NO_CLUSTERING", planner.get("inputDomain"));
        assertTrue(planner.get("decisionRule").toString().contains("critical path"));
        assertEquals("CONTROLLED_MODEL_REGRESSION_COVERED", planner.get("verification"));
        assertEquals(
                "CORE_CPOP_DECISION_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL",
                planner.get("reproductionScope"));
    }

    @Test
    void sharedStorageDlsDeclaresDynamicLevelAndItsNetworkBoundary() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_DLS)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();

        Map<String, Object> planner = nested(AlgorithmCatalog.forConfiguration(config), "planner");

        assertEquals("VALID_DAG_SHARED_STORAGE_NO_CLUSTERING", planner.get("inputDomain"));
        assertTrue(planner.get("decisionRule").toString().contains("Dynamic-level"));
        assertEquals("CONTROLLED_MODEL_REGRESSION_COVERED", planner.get("verification"));
        assertEquals(
                "CORE_DLS_DYNAMIC_LEVEL_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL",
                planner.get("reproductionScope"));
        assertTrue(planner.get("limitations").toString().contains("interconnect topology"));
    }

    @Test
    void sharedStorageEtfDeclaresEarliestStartAndItsAdaptationBoundary() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_ETF)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();

        Map<String, Object> planner = nested(AlgorithmCatalog.forConfiguration(config), "planner");

        assertEquals("VALID_DAG_SHARED_STORAGE_NO_CLUSTERING", planner.get("inputDomain"));
        assertTrue(planner.get("decisionRule").toString().contains("Earliest Task First"));
        assertEquals("CONTROLLED_MODEL_REGRESSION_COVERED", planner.get("verification"));
        assertEquals(
                "CORE_ETF_EARLIEST_START_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL",
                planner.get("reproductionScope"));
        assertTrue(planner.get("limitations").toString().contains("homogeneous-processor"));
    }

    @Test
    void sharedStoragePeftDeclaresOptimisticCostLookAheadAndItsNetworkBoundary() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_PEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();

        Map<String, Object> planner = nested(AlgorithmCatalog.forConfiguration(config), "planner");

        assertEquals("VALID_DAG_SHARED_STORAGE_NO_CLUSTERING", planner.get("inputDomain"));
        assertTrue(planner.get("decisionRule").toString().contains("Predict Earliest Finish Time"));
        assertEquals("CONTROLLED_MODEL_REGRESSION_COVERED", planner.get("verification"));
        assertEquals(
                "CORE_PEFT_OCT_LOOKAHEAD_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL",
                planner.get("reproductionScope"));
        assertTrue(planner.get("limitations").toString().contains("no interprocessor communication term"));
    }

    @Test
    void dataSchedulerDoesNotClaimEndpointRateOrLatencyAwareness() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.DATA)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .build();

        Map<String, Object> scheduler = nested(AlgorithmCatalog.forConfiguration(config), "scheduler");

        assertEquals("DATA_LOCALITY_BASELINE_REGRESSION_COVERED", scheduler.get("verification"));
        assertTrue(scheduler
                .get("limitations")
                .toString()
                .contains("does not consult DataMovementModel endpoint latency or transfer rates"));
    }

    @Test
    void maintainedOnlineSchedulersUseCoreRatherThanReferenceStudyVerificationLabels() {
        for (Parameters.SchedulingAlgorithm algorithm : Arrays.asList(
                Parameters.SchedulingAlgorithm.FCFS,
                Parameters.SchedulingAlgorithm.READY_BATCH_ROUNDROBIN,
                Parameters.SchedulingAlgorithm.READY_BATCH_MCT,
                Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN,
                Parameters.SchedulingAlgorithm.READY_BATCH_MAXMIN)) {
            SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                    .schedulingAlgorithm(algorithm)
                    .build();
            Map<String, Object> scheduler = nested(AlgorithmCatalog.forConfiguration(config), "scheduler");
            assertEquals("CONTROLLED_MODEL_REGRESSION_COVERED", scheduler.get("verification"));
        }
    }

    /**
     * FGCS-R3 (2026-11-24): every algorithm whose decision semantics changed in the FGCS-R3 corrections declares
     * a new machine-readable implementationRevision, so manifests written before the change cannot impersonate the
     * corrected algorithm. Tags follow the existing CPOP/PEFT pattern (rule summary plus revision suffix).
     */
    @Test
    void fgcsR3CorrectedSchedulersDeclareRevisedImplementationTags() {
        Object[][] expected = {
            {Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN, "READY_BATCH_MINMIN_CHARGED_SHARED_READ_ENVELOPE_V2"},
            {Parameters.SchedulingAlgorithm.READY_BATCH_MAXMIN, "READY_BATCH_MAXMIN_CHARGED_SHARED_READ_ENVELOPE_V2"},
            {Parameters.SchedulingAlgorithm.READY_BATCH_MCT, "READY_BATCH_MCT_CHARGED_SHARED_READ_ENVELOPE_V2"},
            {Parameters.SchedulingAlgorithm.EBPSM, "EBPSM_WORKFLOW_BUDGET_ALGORITHM3_UPDATE_V3"},
            {Parameters.SchedulingAlgorithm.CRCH, "CRCH_COMPUTE_WINDOW_CHECKPOINT_PE_FILTER_V2"}
        };
        for (Object[] row : expected) {
            SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                    .schedulingAlgorithm((Parameters.SchedulingAlgorithm) row[0])
                    .build();
            Map<String, Object> scheduler = nested(AlgorithmCatalog.forConfiguration(config), "scheduler");
            assertEquals(row[1], scheduler.get("implementationRevision"), String.valueOf(row[0]));
        }
    }

    @Test
    void fgcsR3CorrectedPlannersDeclareRevisedImplementationTags() {
        Object[][] expected = {
            {Parameters.PlanningAlgorithm.IC_PCP, "ICPCP_LATEST_ARRIVAL_CRITICAL_PARENT_LFT_PATH_SHARED_EDGES_V2"},
            {Parameters.PlanningAlgorithm.MO_HEFT, "MOHEFT_NSGA2_BEAM_REFILL_SHARED_ENVELOPE_V2"},
            {Parameters.PlanningAlgorithm.GREEN_HEFT, "GREENHEFT_SHARED_ENVELOPE_STAGE_IN_RELEASE_V2"},
            {Parameters.PlanningAlgorithm.SHARED_STORAGE_DLS, "DLS_SIH_LEE_MEDIAN_STATIC_LEVEL_DELTA_V2"},
            {Parameters.PlanningAlgorithm.DVFS_SLACK, "DVFS_SLACK_WHOLE_MI_TIER_RESCALING_V2"},
            {Parameters.PlanningAlgorithm.LOCAL_HEFT, "HEFT_MEAN_RATE_COMMUNICATION_V2"},
            {Parameters.PlanningAlgorithm.LOCAL_CPOP, "CPOP_PREDECESSOR_RANK_TIGHT_PATH_V3"},
            {Parameters.PlanningAlgorithm.LOCAL_PEFT, "PEFT_SUCCESSOR_COST_OCT_EXIT_ZERO_V3"}
        };
        for (Object[] row : expected) {
            SimulationConfig.Builder builder = SimulationConfig.builder("workflow.dax", 2)
                    .planningAlgorithm((Parameters.PlanningAlgorithm) row[0])
                    .schedulingAlgorithm(
                            row[0] == Parameters.PlanningAlgorithm.DVFS_SLACK
                                    ? Parameters.SchedulingAlgorithm.DVFS_SLACK
                                    : Parameters.SchedulingAlgorithm.STATIC);
            if (row[0] == Parameters.PlanningAlgorithm.IC_PCP || row[0] == Parameters.PlanningAlgorithm.DVFS_SLACK) {
                builder.deadline(100L);
            }
            if (row[0].toString().startsWith("LOCAL_")) {
                builder.fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(org.workflowsim.data.DataMovementModel.preExecutionTransferDelayV1());
            }
            Map<String, Object> planner = nested(AlgorithmCatalog.forConfiguration(builder.build()), "planner");
            assertEquals(row[1], planner.get("implementationRevision"), String.valueOf(row[0]));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> values, String key) {
        return (Map<String, Object>) values.get(key);
    }
}
