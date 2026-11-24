package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;

class AlgorithmCatalogCaliberTest {

    @Test
    void classifiesReadyBatchLabelsAsTheOnlineCostCaliber() {
        for (SchedulingAlgorithm algorithm : new SchedulingAlgorithm[] {
            SchedulingAlgorithm.READY_BATCH_MINMIN,
            SchedulingAlgorithm.READY_BATCH_MAXMIN,
            SchedulingAlgorithm.READY_BATCH_MCT,
            SchedulingAlgorithm.READY_BATCH_ROUNDROBIN
        }) {
            assertEquals(
                    AlgorithmCatalog.CostComparisonCaliber.ONLINE_READY_BATCH,
                    AlgorithmCatalog.costComparisonCaliber(PlanningAlgorithm.INVALID, algorithm),
                    "ready-batch label " + algorithm + " must stay in the online caliber");
        }
    }

    @Test
    void classifiesPlannerDecidedCellsAsTheStaticPlanningCostCaliber() {
        for (PlanningAlgorithm planner : new PlanningAlgorithm[] {
            PlanningAlgorithm.RANDOM,
            PlanningAlgorithm.STATIC_OLB,
            PlanningAlgorithm.STATIC_MET,
            PlanningAlgorithm.STATIC_MCT,
            PlanningAlgorithm.STATIC_MINMIN,
            PlanningAlgorithm.STATIC_MAXMIN,
            PlanningAlgorithm.STATIC_SUFFERAGE,
            PlanningAlgorithm.STATIC_ROUND_ROBIN,
            PlanningAlgorithm.SHARED_STORAGE_HEFT,
            PlanningAlgorithm.SHARED_STORAGE_CPOP,
            PlanningAlgorithm.SHARED_STORAGE_DLS,
            PlanningAlgorithm.SHARED_STORAGE_ETF,
            PlanningAlgorithm.SHARED_STORAGE_PEFT,
            PlanningAlgorithm.PSO,
            PlanningAlgorithm.LOCAL_HEFT,
            PlanningAlgorithm.LOCAL_CPOP,
            PlanningAlgorithm.LOCAL_PEFT
        }) {
            assertEquals(
                    AlgorithmCatalog.CostComparisonCaliber.STATIC_PLANNING,
                    AlgorithmCatalog.costComparisonCaliber(planner, SchedulingAlgorithm.STATIC),
                    "planner label " + planner + " must stay in the static planning caliber");
        }
    }

    @Test
    void keepsTheStaticPlanningCaliberIndependentOfTheSchedulingLabel() {

        assertEquals(
                AlgorithmCatalog.CostComparisonCaliber.STATIC_PLANNING,
                AlgorithmCatalog.costComparisonCaliber(PlanningAlgorithm.STATIC_MINMIN, SchedulingAlgorithm.FCFS));
        assertEquals(
                AlgorithmCatalog.CostComparisonCaliber.STATIC_PLANNING,
                AlgorithmCatalog.costComparisonCaliber(
                        PlanningAlgorithm.SHARED_STORAGE_HEFT, SchedulingAlgorithm.FCFS));
    }

    @Test
    void classifiesRemainingMaintainedCombinationsAsNoCostComparison() {
        for (SchedulingAlgorithm algorithm : new SchedulingAlgorithm[] {
            SchedulingAlgorithm.FCFS, SchedulingAlgorithm.DATA, SchedulingAlgorithm.CARBON_WINDOW_DEFER
        }) {
            assertEquals(
                    AlgorithmCatalog.CostComparisonCaliber.NONE,
                    AlgorithmCatalog.costComparisonCaliber(PlanningAlgorithm.INVALID, algorithm),
                    "label " + algorithm + " decides mappings without a cost comparison");
        }
    }

    @Test
    void requiresBothLabels() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmCatalog.costComparisonCaliber(null, SchedulingAlgorithm.FCFS));
        assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmCatalog.costComparisonCaliber(PlanningAlgorithm.INVALID, null));
    }
}
