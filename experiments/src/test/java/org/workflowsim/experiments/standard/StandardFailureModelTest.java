package org.workflowsim.experiments.standard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.DistributionSpec;

/**
 * STD-V1 (2026-11-25): hand-derived Weibull scale checks.
 *
 * <p>Gamma(1 + 1/0.78) = Gamma(2.2820513) = 1.2820513 * Gamma(1.2820513). From the Gamma table,
 * Gamma(1.28) = 0.90072 and Gamma(1.29) = 0.89904, so linear interpolation at 1.2820513 gives
 * 0.90072 - 0.2051 * 0.00168 = 0.900375 and Gamma(2.2820513) = 1.2820513 * 0.900375 = 1.15433 (to 5 digits).
 * For rho = 10 and E[task] = 100 s: scale = 1000 / 1.15433 = 866.3 s.
 */
class StandardFailureModelTest {

    @Test
    void weibullMeanOverScaleMatchesTheGammaTable() {
        assertEquals(1.15433, StandardFailureModel.weibullMeanOverScale(0.78), 2.0e-5);
        // Shape 1 is the exponential distribution: Gamma(2) = 1, mean equals scale.
        assertEquals(1.0, StandardFailureModel.weibullMeanOverScale(1.0), 1.0e-12);
        // Shape 0.5: Gamma(3) = 2.
        assertEquals(2.0, StandardFailureModel.weibullMeanOverScale(0.5), 1.0e-12);
    }

    @Test
    void scaleSetsMtbfToTheDeclaredMultipleOfTheMeanTask() {
        assertEquals(866.3, StandardFailureModel.scaleSeconds(10.0, 100.0, 0.78), 0.05);
        assertEquals(1732.6, StandardFailureModel.scaleSeconds(20.0, 100.0, 0.78), 0.1);
        for (double rho : StandardFailureModel.STANDARD_MTBF_TO_TASK_RATIOS) {
            double scale = StandardFailureModel.scaleSeconds(rho, 37.5, 0.78);
            // Conservation: scale * Gamma(1 + 1/k) recovers MTBF = rho * E[task]; lambda * E[task] = 1 / rho.
            assertEquals(rho * 37.5, scale * StandardFailureModel.weibullMeanOverScale(0.78), 1.0e-9 * rho);
        }
        assertThrows(IllegalArgumentException.class, () -> StandardFailureModel.scaleSeconds(0.0, 1.0, 0.78));
    }

    @Test
    void configurationAppliesOneIndependentWeibullProcessPerVm() {
        FailureModelConfig config = StandardFailureModel.weibull(Arrays.asList(0, 1, 2), 10.0, 100.0, 4);
        assertEquals(FailureParameters.FTCFailure.FAILURE_VM, config.getGeneratorMode());
        assertEquals(200, config.getMaxTotalRetryJobs());
        Map<Integer, DistributionSpec[]> rows = config.getGeneratorSpecsByVmId();
        assertEquals(Arrays.asList(0, 1, 2), new java.util.ArrayList<Integer>(rows.keySet()));
        for (DistributionSpec[] row : rows.values()) {
            assertEquals(1, row.length);
            assertEquals(0.78, row[0].getShape(), 0.0);
            assertEquals(866.3, row[0].getScale(), 0.05);
        }
    }
}
