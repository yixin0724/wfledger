package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class AlgorithmSupportMatrixTest {

    @Test
    void everySchedulerLabelIsExplicitlyClassifiedAsMaintainedOrUnavailable() {
        EnumSet<SchedulingAlgorithm> supported = EnumSet.of(
                SchedulingAlgorithm.FCFS,
                SchedulingAlgorithm.READY_BATCH_MINMIN,
                SchedulingAlgorithm.READY_BATCH_MAXMIN,
                SchedulingAlgorithm.READY_BATCH_MCT,
                SchedulingAlgorithm.READY_BATCH_ROUNDROBIN,
                SchedulingAlgorithm.DATA,
                SchedulingAlgorithm.STATIC,
                SchedulingAlgorithm.RL_POLICY,
                SchedulingAlgorithm.CARBON_WINDOW_DEFER,
                SchedulingAlgorithm.CWS_RANKMIN_RR,
                SchedulingAlgorithm.RESHI_C,
                SchedulingAlgorithm.RESHI_M,
                SchedulingAlgorithm.EBPSM,
                SchedulingAlgorithm.DVFS_SLACK,
                SchedulingAlgorithm.CRCH);
        EnumSet<SchedulingAlgorithm> unavailable = EnumSet.of(
                SchedulingAlgorithm.MINMIN,
                SchedulingAlgorithm.MAXMIN,
                SchedulingAlgorithm.MCT,
                SchedulingAlgorithm.ROUNDROBIN,
                SchedulingAlgorithm.INVALID);

        assertExactPartition(SchedulingAlgorithm.class, supported, unavailable);
        for (SchedulingAlgorithm algorithm : supported) {
            assertTrue(AlgorithmCatalog.isSupportedBySimulationRunner(algorithm), algorithm.name());
        }
        for (SchedulingAlgorithm algorithm : unavailable) {
            assertFalse(AlgorithmCatalog.isSupportedBySimulationRunner(algorithm), algorithm.name());
        }
    }

    @Test
    void everyPlannerLabelIsExplicitlyClassifiedAsMaintainedOrLegacy() {
        EnumSet<PlanningAlgorithm> supported = EnumSet.of(
                PlanningAlgorithm.INVALID,
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
                PlanningAlgorithm.CAWOSCHED_SLACK,
                PlanningAlgorithm.CAWOSCHED_PRESSURE,
                PlanningAlgorithm.NACO_GA_HEFT,
                PlanningAlgorithm.GREEN_HEFT,
                PlanningAlgorithm.MO_HEFT,
                PlanningAlgorithm.IC_PCP,
                PlanningAlgorithm.NETWORK_HEFT,
                PlanningAlgorithm.DVFS_SLACK,
                PlanningAlgorithm.DHEFT,
                PlanningAlgorithm.PSO,
                PlanningAlgorithm.LOCAL_HEFT,
                PlanningAlgorithm.LOCAL_CPOP,
                PlanningAlgorithm.LOCAL_PEFT);

        EnumSet<PlanningAlgorithm> legacy = EnumSet.noneOf(PlanningAlgorithm.class);

        assertExactPartition(PlanningAlgorithm.class, supported, legacy);
        for (PlanningAlgorithm algorithm : supported) {
            assertTrue(AlgorithmCatalog.isSupportedBySimulationRunner(algorithm), algorithm.name());
        }
        for (PlanningAlgorithm algorithm : legacy) {
            assertFalse(AlgorithmCatalog.isSupportedBySimulationRunner(algorithm), algorithm.name());
        }
    }

    @Test
    void everyCatalogEntryCarriesTheSameRunnerSupportClassification() {
        for (SchedulingAlgorithm algorithm : SchedulingAlgorithm.values()) {
            if (algorithm == SchedulingAlgorithm.INVALID) {
                continue;
            }
            SimulationConfig config = schedulingConfig(algorithm);
            Map<String, Object> scheduler = nested(AlgorithmCatalog.forConfiguration(config), "scheduler");
            String verification = String.valueOf(scheduler.get("verification"));
            if (AlgorithmCatalog.isSupportedBySimulationRunner(algorithm)) {
                assertFalse(verification.contains("NOT_SUPPORTED"), algorithm.name());
            } else {
                assertTrue(
                        verification.contains("NOT_SUPPORTED") || "NOT_APPLICABLE".equals(verification),
                        algorithm.name());
            }
        }
        for (PlanningAlgorithm algorithm : PlanningAlgorithm.values()) {
            SimulationConfig config = planningConfig(algorithm);
            Map<String, Object> planner = nested(AlgorithmCatalog.forConfiguration(config), "planner");
            String verification = String.valueOf(planner.get("verification"));
            if (AlgorithmCatalog.isSupportedBySimulationRunner(algorithm)) {
                assertFalse(verification.contains("NOT_SUPPORTED"), algorithm.name());
            } else {
                assertTrue(verification.contains("NOT_SUPPORTED"), algorithm.name());
            }
        }
    }

    private static SimulationConfig schedulingConfig(SchedulingAlgorithm algorithm) {
        if (algorithm == SchedulingAlgorithm.STATIC) {
            return SimulationConfig.builder("workflow.dax", 1)
                    .planningAlgorithm(PlanningAlgorithm.RANDOM)
                    .schedulingAlgorithm(algorithm)
                    .build();
        }
        if (algorithm == SchedulingAlgorithm.DVFS_SLACK) {
            return SimulationConfig.builder("workflow.dax", 1)
                    .planningAlgorithm(PlanningAlgorithm.DVFS_SLACK)
                    .schedulingAlgorithm(algorithm)
                    .deadline(100L)
                    .build();
        }
        return SimulationConfig.builder("workflow.dax", 1)
                .schedulingAlgorithm(algorithm)
                .fileSystem(
                        algorithm == SchedulingAlgorithm.DATA
                                ? ReplicaCatalog.FileSystem.LOCAL
                                : ReplicaCatalog.FileSystem.SHARED)
                .build();
    }

    private static SimulationConfig planningConfig(PlanningAlgorithm algorithm) {
        if (algorithm == PlanningAlgorithm.INVALID) {
            return SimulationConfig.builder("workflow.dax", 1).build();
        }
        boolean localComm = algorithm == PlanningAlgorithm.LOCAL_HEFT
                || algorithm == PlanningAlgorithm.LOCAL_CPOP
                || algorithm == PlanningAlgorithm.LOCAL_PEFT
                || algorithm == PlanningAlgorithm.NACO_GA_HEFT
                || algorithm == PlanningAlgorithm.DHEFT;
        SimulationConfig.Builder builder = SimulationConfig.builder("workflow.dax", 1)
                .planningAlgorithm(algorithm)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC);
        if (algorithm == PlanningAlgorithm.IC_PCP || algorithm == PlanningAlgorithm.DVFS_SLACK) {
            builder.deadline(100L);
        }
        if (localComm) {

            builder.fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                    .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1());
        } else {
            builder.fileSystem(ReplicaCatalog.FileSystem.SHARED);
        }
        return builder.build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void assertExactPartition(Class<? extends Enum> enumType, EnumSet supported, EnumSet unavailable) {
        EnumSet combined = EnumSet.copyOf(supported);
        combined.addAll(unavailable);
        assertEquals(EnumSet.allOf(enumType), combined);
        EnumSet overlap = EnumSet.copyOf(supported);
        overlap.retainAll(unavailable);
        assertTrue(overlap.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> values, String key) {
        return (Map<String, Object>) values.get(key);
    }
}
