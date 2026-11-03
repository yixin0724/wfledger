package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.registry.AlgorithmRegistry;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * BAT-R8 (2026-10-27, eighth review round) -- seam x seam matrix batch S2: the contract
 * clauses of the registration interface (reject leg) have never been tested under seam
 * combinations. R7 proved that combinations of seams are the real unaudited zone.
 *
 * <p>Expected-value provenance: the contract clauses of AlgorithmRegistry.validate
 * themselves -- every rejection message must name the constraint (not silence, not
 * implementation output). Drive validate() directly (which locks the clauses more precisely
 * than going through the full runner); the six dimensions are supplied explicitly by
 * ValidationInput.</p>
 */
class RegisteredAxesRejectIntegrationTest {

    private static final String LABEL = "ENERGY_LPT_FIRST";

    @Test
    void failureModelIsRejectedByTheProviderContract() {
        IllegalArgumentException rejection = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRegistry.validate(
                        LABEL,
                        new AlgorithmRegistry.ValidationInput(
                                ReplicaCatalog.FileSystem.LOCAL,
                                DataMovementModel.legacyWorkflowsimV1(),
                                false,
                                enabledFailureModel(),
                                PlanningAlgorithm.INVALID,
                                true)),
                "ENERGY_LPT_FIRST declares allowsFailureModel=false; an enabled failure model must be rejected");
        assertTrue(
                rejection.getMessage().contains("failure model"),
                "rejection must cite the failure-model clause: " + rejection.getMessage());
    }

    @Test
    void taskCostMatrixIsRejectedByTheProviderContract() {
        IllegalArgumentException rejection = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRegistry.validate(
                        LABEL,
                        new AlgorithmRegistry.ValidationInput(
                                ReplicaCatalog.FileSystem.LOCAL,
                                DataMovementModel.legacyWorkflowsimV1(),
                                true,
                                null,
                                PlanningAlgorithm.INVALID,
                                true)),
                "ENERGY_LPT_FIRST declares acceptsTaskCostMatrix=false; a declared matrix must be rejected");
        assertTrue(
                rejection.getMessage().contains("task-cost matrix"),
                "rejection must cite the cost-matrix clause: " + rejection.getMessage());
    }

    @Test
    void everyClauseGreenTogetherStillValidates() {
        // The converse of the counter-example: all six dimensions align with the contract
        // (legacy model, no matrix, no failure, INVALID, with power) -- the same chain must
        // be allowed through, which proves the reject leg is not a blanket rejection.
        assertDoesNotThrow(() -> AlgorithmRegistry.validate(
                LABEL,
                new AlgorithmRegistry.ValidationInput(
                        ReplicaCatalog.FileSystem.LOCAL,
                        DataMovementModel.legacyWorkflowsimV1(),
                        false,
                        null,
                        PlanningAlgorithm.INVALID,
                        true)));
    }

    private static FailureModelConfig enabledFailureModel() {
        return new FailureModelConfig.Builder()
                .generatorMode(org.workflowsim.failure.FailureParameters.FTCFailure.FAILURE_ALL)
                .build();
    }
}
