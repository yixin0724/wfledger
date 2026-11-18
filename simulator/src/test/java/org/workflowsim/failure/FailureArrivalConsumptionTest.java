package org.workflowsim.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.utils.DistributionGenerator;

class FailureArrivalConsumptionTest {

    @AfterEach
    void resetFailureState() {
        FailureGenerator.reset();
        FailureMonitor.reset();
        FailureParameters.reset();
    }

    @Test
    void consumesExpiredAndMatchedArrivalsThroughTheirActualCumulativeIndices() {

        FixedDistributionGenerator generator = new FixedDistributionGenerator(2.0, 3.0, 5.0);
        FailureParameters.init(
                FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP,
                FailureParameters.FTCMonitor.MONITOR_ALL,
                FailureParameters.FTCFailure.FAILURE_ALL,
                new DistributionGenerator[][] {{generator}});
        FailureMonitor.init();
        FailureGenerator.init();

        assertFalse(FailureGenerator.generate(jobWithWindow(1, 3.0, 4.0)));
        assertEquals(1, generator.getConsumedSampleCount());

        assertTrue(FailureGenerator.generate(jobWithWindow(2, 4.0, 6.0)));
        assertEquals(2, generator.getConsumedSampleCount());
        assertTrue(FailureGenerator.generate(jobWithWindow(3, 9.0, 11.0)));
        assertEquals(3, generator.getConsumedSampleCount());

        assertEquals(10.0 / 3.0, generator.getMean(), 0.0);
    }

    private static Job jobWithWindow(int id, double start, double finish) {
        Task task = new Task(id, 1L);
        task.setDepth(0);
        task.setExecStartTime(start);
        task.setTaskFinishTime(finish);
        Job job = new Job(id, 1L);
        job.setUserId(0);
        job.setVmId(0);
        job.addTaskList(Collections.singletonList(task));
        return job;
    }

    private static final class FixedDistributionGenerator extends DistributionGenerator {

        private FixedDistributionGenerator(double... values) {
            super(DistributionFamily.WEIBULL, 1.0, 1.0, "failure-arrival-consumption-test");
            this.samples = values.clone();
            updateCumulativeSamples();
            this.cursor = 0;
        }
    }
}
