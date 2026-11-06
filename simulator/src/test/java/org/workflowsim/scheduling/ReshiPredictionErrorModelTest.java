package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;
import org.workflowsim.utils.ReshiPredictionErrorModel;
import org.workflowsim.utils.SimulationRandom;

/** Prediction-error distribution, clamp, and named-stream reproducibility checks. */
class ReshiPredictionErrorModelTest {

    @Test
    void normalErrorIsReproducibleAndZeroErrorIsIdentity() {
        ReshiPredictionErrorModel model =
                new ReshiPredictionErrorModel(ReshiPredictionErrorModel.DistributionFamily.NORMAL);
        SimulationRandom.reset(7L);
        Random first = SimulationRandom.newJavaRandom("prediction.reshi.test");
        SimulationRandom.reset(7L);
        Random second = SimulationRandom.newJavaRandom("prediction.reshi.test");
        double firstFactor = model.sampleFactor(0.5, first);
        double secondFactor = model.sampleFactor(0.5, second);
        assertEquals(firstFactor, secondFactor, 1.0e-12);
        assertTrue(firstFactor >= 0.0);
        assertEquals(1.0, model.sampleFactor(0.0, first), 1.0e-12);
    }

    @Test
    void exponentialErrorNeverProducesNegativeRuntime() {
        ReshiPredictionErrorModel model =
                new ReshiPredictionErrorModel(ReshiPredictionErrorModel.DistributionFamily.EXPONENTIAL);
        Random random = SimulationRandom.newJavaRandom("prediction.reshi.exponential");
        for (int index = 0; index < 100; index++) {
            assertTrue(model.sampleFactor(10.0, random) >= 0.0);
        }
    }
}
