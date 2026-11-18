package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.apache.commons.math3.distribution.GammaDistribution;
import org.apache.commons.math3.distribution.RealDistribution;
import org.apache.commons.math3.distribution.WeibullDistribution;
import org.apache.commons.math3.special.Gamma;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.failure.FailureGenerator;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;

class DistributionGeneratorKsTest {

    private static final double MIN_ACCEPT_P = 1.0e-4;

    private static final double MAX_CROSS_FIT_P = 1.0e-6;

    @AfterEach
    void resetFailureState() {
        FailureGenerator.reset();
        FailureMonitor.reset();
        FailureParameters.reset();
    }

    @Test
    void weibullSamplesFollowClaimedDistribution() {
        assertSamplesFollowDistribution(DistributionFamily.WEIBULL, 100.0, 1.5, "r3.ks.weibull");
    }

    @Test
    void gammaSamplesFollowClaimedDistribution() {
        assertSamplesFollowDistribution(DistributionFamily.GAMMA, 2.0, 3.0, "r3.ks.gamma");
    }

    @Test
    void lognormalSamplesFollowClaimedDistribution() {
        assertSamplesFollowDistribution(DistributionFamily.LOGNORMAL, 1.0, 0.5, "r3.ks.lognormal");
    }

    @Test
    void normalSamplesFollowClaimedDistribution() {
        assertSamplesFollowDistribution(DistributionFamily.NORMAL, 10.0, 2.0, "r3.ks.normal");
    }

    @Test
    void distributionSpecWiringMatchesFamilyContract() {

        DistributionGenerator generator =
                DistributionSpec.of(DistributionFamily.WEIBULL, 100.0, 2.0).createGenerator("r3.ks.spec.wiring");
        double p = kolmogorovSmirnovP(generator, new WeibullDistribution(2.0, 100.0));
        assertTrue(
                p > MIN_ACCEPT_P,
                "DistributionSpec wiring samples must follow Weibull(alpha=2, beta=100), actual p=" + p);
    }

    @Test
    void sampleMeansMatchFamilyMoments() {

        assertMeanNear(
                DistributionFamily.WEIBULL, 100.0, 1.5, "r3.ks.moments.weibull", 100.0 * Gamma.gamma(1.0 + 1.0 / 1.5));
        assertMeanNear(DistributionFamily.GAMMA, 2.0, 3.0, "r3.ks.moments.gamma", 3.0 * 2.0);
        assertMeanNear(
                DistributionFamily.LOGNORMAL, 1.0, 0.5, "r3.ks.moments.lognormal", Math.exp(1.0 + 0.5 * 0.5 / 2.0));
        assertMeanNear(DistributionFamily.NORMAL, 10.0, 2.0, "r3.ks.moments.normal", 10.0);
    }

    @Test
    void ksTestHasPowerToRejectWrongFamily() {

        DistributionGenerator generator =
                new DistributionGenerator(DistributionFamily.WEIBULL, 100.0, 1.5, "r3.ks.power");
        double weibullMean = 100.0 * Gamma.gamma(1.0 + 1.0 / 1.5);

        double crossP = kolmogorovSmirnovP(generator, new GammaDistribution(9.0, weibullMean / 9.0));
        assertTrue(crossP < MAX_CROSS_FIT_P, "KS test must reject a wrong-family fit, actual p=" + crossP);
    }

    @Test
    void failureArrivalProcessFollowsConfiguredWeibullDistribution() throws Exception {

        double scale = 100.0;
        double shape = 1.5;
        DistributionGenerator generator =
                new DistributionGenerator(DistributionFamily.WEIBULL, scale, shape, "r3.arrival.weibull");
        FailureParameters.init(
                FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP,
                FailureParameters.FTCMonitor.MONITOR_ALL,
                FailureParameters.FTCFailure.FAILURE_ALL,
                new DistributionGenerator[][] {{generator}});
        FailureMonitor.init();
        FailureGenerator.init();

        double horizon = 5000.0;
        double step = 10.0;
        for (double start = 0.0; start < horizon; start += step) {
            FailureGenerator.generate(jobWithWindow(start, start + step));
        }
        int consumed = generator.getConsumedSampleCount();
        assertTrue(consumed > 20, "the scan must consume enough arrival samples, actual " + consumed);

        double[] cumulative = generator.getCumulativeSamples();
        assertTrue(
                cumulative[consumed - 1] <= horizon,
                "last consumed arrival " + cumulative[consumed - 1] + " must be <= horizon " + horizon);
        if (consumed < cumulative.length) {
            assertTrue(
                    cumulative[consumed] > horizon,
                    "first unconsumed arrival " + cumulative[consumed] + " must be > horizon " + horizon);
        }
        for (int i = 0; i < consumed; i++) {
            assertTrue(
                    i == 0 || cumulative[i] > cumulative[i - 1],
                    "Weibull arrival instants must be strictly increasing");
        }

        double[] interArrivals = new double[consumed];
        for (int i = 0; i < consumed; i++) {
            interArrivals[i] = i == 0 ? cumulative[0] : cumulative[i] - cumulative[i - 1];
        }
        double p = kolmogorovSmirnovP(interArrivals, new WeibullDistribution(shape, scale));
        assertTrue(
                p > MIN_ACCEPT_P,
                "failure inter-arrival times must follow Weibull(alpha=1.5, beta=100), actual p=" + p);
    }

    private static void assertSamplesFollowDistribution(
            DistributionFamily family, double scale, double shape, String stream) {
        DistributionGenerator generator = new DistributionGenerator(family, scale, shape, stream);
        double p = kolmogorovSmirnovP(generator, generator.getDistribution(scale, shape));
        assertTrue(
                p > MIN_ACCEPT_P,
                family + "(" + scale + ", " + shape + ") sample KS p=" + p + " must exceed " + MIN_ACCEPT_P);
        assertEquals(1500, generator.getSamples().length, "the first batch must contain 1500 samples");
    }

    private static void assertMeanNear(
            DistributionFamily family, double scale, double shape, String stream, double theoreticalMean) {
        DistributionGenerator generator = new DistributionGenerator(family, scale, shape, stream);
        double[] samples = generator.getSamples();
        double sum = 0.0;
        for (double sample : samples) {
            sum += sample;
        }
        double mean = sum / samples.length;
        double relativeError = Math.abs(mean - theoreticalMean) / theoreticalMean;
        assertTrue(
                relativeError < 0.05,
                family + " sample mean " + mean + " vs theoretical moment " + theoreticalMean
                        + " has relative deviation " + relativeError + " that must be below 5%");
    }

    private static double kolmogorovSmirnovP(DistributionGenerator generator, RealDistribution theoretical) {
        return kolmogorovSmirnovP(generator.getSamples(), theoretical);
    }

    private static double kolmogorovSmirnovP(double[] samples, RealDistribution theoretical) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        double statistic = 0.0;
        for (int i = 0; i < n; i++) {
            double fitted = theoretical.cumulativeProbability(sorted[i]);
            statistic = Math.max(statistic, Math.max((i + 1.0) / n - fitted, fitted - i / (double) n));
        }
        double lambda = Math.sqrt(n) * statistic;
        double p = 0.0;
        for (int k = 1; k <= 100; k++) {
            double term = 2.0 * ((k % 2 == 1) ? 1.0 : -1.0) * Math.exp(-2.0 * k * k * lambda * lambda);
            p += term;
            if (Math.abs(term) < 1.0e-14) {
                break;
            }
        }
        return Math.max(0.0, Math.min(1.0, p));
    }

    private static Job jobWithWindow(double start, double finish) {
        Task task = new Task(1, 1L);
        task.setDepth(0);
        task.setExecStartTime(start);
        task.setTaskFinishTime(finish);
        Job job = new Job(1, 1L);
        job.setUserId(0);
        job.setVmId(0);
        job.addTaskList(Collections.singletonList(task));
        return job;
    }
}
