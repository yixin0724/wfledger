package org.workflowsim.experiments.standard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.DistributionSpec;

class StandardFailureModelTest {

    @Test
    void weibullMeanOverScaleMatchesTheGammaTable() {
        assertEquals(1.15433, StandardFailureModel.weibullMeanOverScale(0.78), 2.0e-5);

        assertEquals(1.0, StandardFailureModel.weibullMeanOverScale(1.0), 1.0e-12);

        assertEquals(2.0, StandardFailureModel.weibullMeanOverScale(0.5), 1.0e-12);
    }

    @Test
    void scaleSetsMtbfToTheDeclaredMultipleOfTheMeanTask() {
        assertEquals(866.3, StandardFailureModel.scaleSeconds(10.0, 100.0, 0.78), 0.05);
        assertEquals(1732.6, StandardFailureModel.scaleSeconds(20.0, 100.0, 0.78), 0.1);
        for (double rho : StandardFailureModel.STANDARD_MTBF_TO_TASK_RATIOS) {
            double scale = StandardFailureModel.scaleSeconds(rho, 37.5, 0.78);

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
