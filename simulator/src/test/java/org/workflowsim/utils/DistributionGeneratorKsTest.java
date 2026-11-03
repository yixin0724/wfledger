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

/**
 * R3: Kolmogorov-Smirnov goodness-of-fit test for the failure arrival distribution.
 *
 * <p>The object under test is the distribution contract the platform claims: {@link DistributionGenerator}
 * samples the four families LOGNORMAL/GAMMA/WEIBULL/NORMAL from (scale, shape) parameters, and the
 * failure arrival process is the piecewise cumulative sum of its samples. The test verifies three
 * things:</p>
 * <ol>
 *   <li><b>Family contract</b>: 1500 samples of each family pass the KS test against their theoretical
 *       distribution (p &gt; 10^-4, a conservative threshold; the random streams are seeded by name, so
 *       the result is bit-deterministic with no jitter);</li>
 *   <li><b>Parameter mapping</b>: the sample mean agrees with the theoretical moment of the family
 *       (Weibull beta*Gamma(1+1/alpha), Gamma alpha*beta, LogNormal e^{mu+sigma^2/2}, Normal mu);</li>
 *   <li><b>Failure wiring</b>: the arrival instants consumed by a full-horizon scan through
 *       {@link FailureGenerator} are exactly the cumulative sample prefix, and their inter-arrival
 *       times pass the Weibull KS test -- the failure arrival process follows the distribution the
 *       configuration claims; a sensitivity control is included as well (fitting a Weibull sample with
 *       a Gamma is rejected significantly), proving the test itself has power rather than always
 *       passing.</li>
 * </ol>
 */
class DistributionGeneratorKsTest {

    /** KS acceptance threshold: a p above it accepts "the sample comes from this distribution".
     * The random streams are deterministic, with no jitter. */
    private static final double MIN_ACCEPT_P = 1.0e-4;
    /** Rejection threshold of the test-power control: the cross-fit p must lie below it. */
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
        // DistributionSpec.of(WEIBULL, scale, shape) is the entry point of the failure-model
        // configuration; its generator's samples must pass the KS test against Weibull(alpha=shape, beta=scale).
        DistributionGenerator generator =
                DistributionSpec.of(DistributionFamily.WEIBULL, 100.0, 2.0).createGenerator("r3.ks.spec.wiring");
        double p = kolmogorovSmirnovP(generator, new WeibullDistribution(2.0, 100.0));
        assertTrue(
                p > MIN_ACCEPT_P,
                "DistributionSpec wiring samples must follow Weibull(alpha=2, beta=100), actual p=" + p);
    }

    @Test
    void sampleMeansMatchFamilyMoments() {
        // The mean of 1500 deterministic samples per family must stay within 5% relative deviation
        // from the theoretical moment.
        assertMeanNear(
                DistributionFamily.WEIBULL, 100.0, 1.5, "r3.ks.moments.weibull", 100.0 * Gamma.gamma(1.0 + 1.0 / 1.5));
        assertMeanNear(DistributionFamily.GAMMA, 2.0, 3.0, "r3.ks.moments.gamma", 3.0 * 2.0);
        assertMeanNear(
                DistributionFamily.LOGNORMAL, 1.0, 0.5, "r3.ks.moments.lognormal", Math.exp(1.0 + 0.5 * 0.5 / 2.0));
        assertMeanNear(DistributionFamily.NORMAL, 10.0, 2.0, "r3.ks.moments.normal", 10.0);
    }

    @Test
    void ksTestHasPowerToRejectWrongFamily() {
        // Power control: fitting a Weibull(1.5, 100) sample with a Gamma of similar mean
        // must yield a tiny p -- otherwise the KS verdict would be vacuous.
        DistributionGenerator generator =
                new DistributionGenerator(DistributionFamily.WEIBULL, 100.0, 1.5, "r3.ks.power");
        double weibullMean = 100.0 * Gamma.gamma(1.0 + 1.0 / 1.5);
        // Gamma(alpha=9, beta=mean/9) has the same mean as the Weibull but a different shape.
        double crossP = kolmogorovSmirnovP(generator, new GammaDistribution(9.0, weibullMean / 9.0));
        assertTrue(crossP < MAX_CROSS_FIT_P, "KS test must reject a wrong-family fit, actual p=" + crossP);
    }

    @Test
    void failureArrivalProcessFollowsConfiguredWeibullDistribution() throws Exception {
        // Failure arrival = cumulative sum of the distribution samples. Initialise the failure model
        // with Weibull(1.5, 100), consume the arrival stream through a continuous window scan, then
        // verify:
        // (a) the consumed arrival instants are exactly the cumulative sample prefix (wiring identity);
        // (b) the inter-arrival times (that is, the distribution samples) pass the Weibull KS test.
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
        // (a) Wiring identity: the consumed boundary matches the scan horizon -- the last consumed
        // arrival <= horizon, the first unconsumed arrival > horizon; and the arrival instants are
        // strictly increasing.
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
        // (b) Distribution test on the inter-arrival times.
        double[] interArrivals = new double[consumed];
        for (int i = 0; i < consumed; i++) {
            interArrivals[i] = i == 0 ? cumulative[0] : cumulative[i] - cumulative[i - 1];
        }
        double p = kolmogorovSmirnovP(interArrivals, new WeibullDistribution(shape, scale));
        assertTrue(
                p > MIN_ACCEPT_P,
                "failure inter-arrival times must follow Weibull(alpha=1.5, beta=100), actual p=" + p);
    }

    /** Family-contract assertion: the generator samples pass the KS test against their own
     * theoretical distribution. */
    private static void assertSamplesFollowDistribution(
            DistributionFamily family, double scale, double shape, String stream) {
        DistributionGenerator generator = new DistributionGenerator(family, scale, shape, stream);
        double p = kolmogorovSmirnovP(generator, generator.getDistribution(scale, shape));
        assertTrue(
                p > MIN_ACCEPT_P,
                family + "(" + scale + ", " + shape + ") sample KS p=" + p + " must exceed " + MIN_ACCEPT_P);
        assertEquals(1500, generator.getSamples().length, "the first batch must contain 1500 samples");
    }

    /** Relative-deviation assertion of the sample mean against the theoretical moment
     * (5% tolerance, 1500 deterministic samples). */
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

    /**
     * Single-sample KS asymptotic p-value (self-implemented to avoid the commons-math3 3.3+
     * KolmogorovSmirnovTest -- the repository pins 3.2). D = sup|F_n - F|, lambda = sqrt(n)*D,
     * p ~ Q(lambda) = 2*sum_{k>=1} (-1)^{k-1} exp(-2k^2 lambda^2) (the complementary CDF series of
     * the Kolmogorov asymptotic distribution). All operations are deterministic floating point, so
     * the same sample yields the same result on any platform.
     */
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
