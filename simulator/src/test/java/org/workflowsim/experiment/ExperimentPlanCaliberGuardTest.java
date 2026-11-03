package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;

/**
 * Pins the cross-caliber fail-fast of the campaign construction layer (AUD-F09).
 *
 * <p>One comparison group must not place the online ready-batch cost caliber (bare
 * length/MIPS ECT) beside the static planning cost caliber (TaskExecutionModel matrix
 * seconds): a same-named algorithm carries two different cost semantics across the two
 * calibers, so a side-by-side comparison is rejected at plan construction. Neither
 * caliber's own comparison semantics change, and cells outside both cost calibers stay
 * unconstrained.</p>
 */
class ExperimentPlanCaliberGuardTest {

    @Test
    void buildRejectsAMixedCostCaliberComparisonGroup() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> plan(
                        cell("online-minmin", true, PlanningAlgorithm.INVALID, SchedulingAlgorithm.READY_BATCH_MINMIN),
                        cell("static-minmin", false, PlanningAlgorithm.STATIC_MINMIN, SchedulingAlgorithm.STATIC)));
        assertTrue(
                failure.getMessage().contains("cost caliber"),
                "the rejection must name the cost caliber, got: " + failure.getMessage());
        assertTrue(
                failure.getMessage().contains("READY_BATCH_MINMIN"),
                "the rejection must name the online cell's label, got: " + failure.getMessage());
        assertTrue(
                failure.getMessage().contains("STATIC_MINMIN"),
                "the rejection must name the static planning cell's label, got: " + failure.getMessage());
    }

    @Test
    void buildAcceptsAPureReadyBatchComparisonGroup() {
        assertDoesNotThrow(() -> plan(
                cell("ready-batch-minmin", true, PlanningAlgorithm.INVALID, SchedulingAlgorithm.READY_BATCH_MINMIN),
                cell("ready-batch-maxmin", false, PlanningAlgorithm.INVALID, SchedulingAlgorithm.READY_BATCH_MAXMIN)));
    }

    @Test
    void buildAcceptsAPureStaticPlanningComparisonGroup() {
        assertDoesNotThrow(() -> plan(
                cell("static-minmin", true, PlanningAlgorithm.STATIC_MINMIN, SchedulingAlgorithm.STATIC),
                cell("shared-storage-heft", false, PlanningAlgorithm.SHARED_STORAGE_HEFT, SchedulingAlgorithm.STATIC)));
    }

    @Test
    void buildKeepsNoCostCaliberCellsUnconstrained() {
        // FCFS decides mappings by submission order and makes no cost comparison, so it
        // shares a group with either cost caliber without being a caliber mix.
        assertDoesNotThrow(() -> plan(
                cell("fcfs", true, PlanningAlgorithm.INVALID, SchedulingAlgorithm.FCFS),
                cell("ready-batch-mct", false, PlanningAlgorithm.INVALID, SchedulingAlgorithm.READY_BATCH_MCT)));
        assertDoesNotThrow(() -> plan(
                cell("fcfs", true, PlanningAlgorithm.INVALID, SchedulingAlgorithm.FCFS),
                cell("static-minmin", false, PlanningAlgorithm.STATIC_MINMIN, SchedulingAlgorithm.STATIC)));
    }

    @Test
    void buildAllowsDifferentCostCalibersInDifferentComparisonGroups() {
        assertDoesNotThrow(() -> ExperimentPlan.builder("caliber-per-group")
                .addCell(groupedCell(
                        "online",
                        "online-group",
                        true,
                        PlanningAlgorithm.INVALID,
                        SchedulingAlgorithm.READY_BATCH_MINMIN))
                .addCell(groupedCell(
                        "static", "static-group", true, PlanningAlgorithm.STATIC_MINMIN, SchedulingAlgorithm.STATIC))
                .build());
    }

    private static ExperimentPlan plan(ExperimentPlan.Cell first, ExperimentPlan.Cell second) {
        return ExperimentPlan.builder("caliber-guard")
                .addCell(first)
                .addCell(second)
                .build();
    }

    private static ExperimentPlan.Cell cell(
            String id, boolean baseline, PlanningAlgorithm planner, SchedulingAlgorithm scheduler) {
        return groupedCell(id, "same-input", baseline, planner, scheduler);
    }

    private static ExperimentPlan.Cell groupedCell(
            String id, String group, boolean baseline, PlanningAlgorithm planner, SchedulingAlgorithm scheduler) {
        SimulationConfig config = SimulationConfig.builder(workflow(), 2)
                .planningAlgorithm(planner)
                .schedulingAlgorithm(scheduler)
                .build();
        return new ExperimentPlan.Cell(
                id,
                group,
                id,
                baseline,
                config,
                PlatformProfiles.homogeneousLocal("caliber-guard-" + id, 2),
                SeedPlan.deterministic(20260902L),
                null);
    }

    private static String workflow() {
        URL url = ExperimentPlanCaliberGuardTest.class.getResource("/dax/reproducibility-workflow.dax");
        if (url == null) {
            throw new IllegalStateException("Missing test resource /dax/reproducibility-workflow.dax");
        }
        return Paths.get(url.getPath()).toString();
    }
}
