/*
 *
 *   Copyright 2013-2014 University Of Southern California
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.workflowsim.utils;

import java.util.Arrays;

import org.apache.commons.math3.distribution.GammaDistribution;
import org.apache.commons.math3.distribution.LogNormalDistribution;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.RealDistribution;
import org.apache.commons.math3.distribution.WeibullDistribution;
import org.apache.commons.math3.random.RandomGenerator;

/**
 * Probability distribution generator that provides pre-sampled sequences to the cost and
 * failure models.
 *
 * <p>The generator creates Apache Commons Math distributions from named {@link SimulationRandom}
 * streams and caches the samples together with their cumulative sums. The cache is a performance
 * implementation only and does not change the sampling order; callers should go through
 * {@link DistributionSpec} or an explicit random stream name so that distinct model components
 * do not accidentally share a random sequence.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Mar 11, 2014
 */
public class DistributionGenerator {

    protected DistributionFamily dist;
    protected double scale;
    protected double shape;
    protected double scale_prior;
    protected double shape_prior;
    protected double likelihood_prior;
    protected double[] samples;
    protected double[] cumulativeSamples;
    protected int cursor;
    /** Number of samples per pre-sampling or extension step. */
    protected final int SAMPLE_SIZE = 1500;

    private final RandomGenerator randomGenerator;
    private static long streamSequence;

    /** Supported probability distribution families. */
    public enum DistributionFamily {
        LOGNORMAL,
        GAMMA,
        WEIBULL,
        NORMAL
    }

    /**
     * Creates a generator that uses an automatically allocated default random stream.
     *
     * <p>New experiment code should prefer the constructor taking {@code randomStream}, so that
     * the random stream identity becomes part of the configuration.</p>
     *
     * @param dist distribution family
     * @param scale scale parameter
     * @param shape shape parameter
     */
    public DistributionGenerator(DistributionFamily dist, double scale, double shape) {
        this(dist, scale, shape, nextDefaultStream(dist));
    }

    /**
     * Creates a generator with an explicit random stream identity.
     *
     * <p>Experiments under configuration management should use this constructor, so that
     * independent delay categories do not become correlated merely by sharing a distribution
     * family.</p>
     *
     * @param dist distribution family
     * @param scale scale parameter
     * @param shape shape parameter
     * @param randomStream stable random component name
     */
    public DistributionGenerator(DistributionFamily dist, double scale, double shape, String randomStream) {
        validateParameters(dist, scale, shape);
        if (randomStream == null || randomStream.trim().isEmpty()) {
            throw new IllegalArgumentException("Distribution random stream cannot be empty");
        }
        this.dist = dist;
        this.scale = scale;
        this.shape = shape;
        this.scale_prior = scale;
        this.shape_prior = shape;
        this.randomGenerator = SimulationRandom.newApacheRandom(randomStream);
        RealDistribution distribution = getDistribution(scale, shape);
        samples = distribution.sample(SAMPLE_SIZE);
        updateCumulativeSamples();
        cursor = 0;
    }

    /**
     * Creates a generator with prior parameters that uses an automatically allocated default
     * random stream.
     *
     * @param dist distribution family
     * @param scale scale parameter
     * @param shape shape parameter
     * @param a prior shape parameter
     * @param b prior scale parameter
     * @param c prior likelihood parameter
     */
    public DistributionGenerator(DistributionFamily dist, double scale, double shape, double a, double b, double c) {
        this(dist, scale, shape, a, b, c, nextDefaultStream(dist));
    }

    /**
     * Creates a generator with prior parameters and an explicit random stream identity.
     *
     * @param dist distribution family
     * @param scale scale parameter
     * @param shape shape parameter
     * @param a prior shape parameter
     * @param b prior scale parameter
     * @param c prior likelihood parameter
     * @param randomStream stable random component name
     */
    public DistributionGenerator(
            DistributionFamily dist, double scale, double shape, double a, double b, double c, String randomStream) {
        this(dist, scale, shape, randomStream);
        this.scale_prior = b;
        this.shape_prior = a;
        this.likelihood_prior = c;
    }

    /**
     * @return current pre-sampled sequence; callers must not infer from it that the real
     *         distribution has been calibrated
     */
    public double[] getSamples() {
        return samples;
    }

    /** @return cumulative sums of the pre-sampled sequence */
    public double[] getCumulativeSamples() {
        return cumulativeSamples;
    }

    /**
     * Returns the number of raw samples already consumed.
     *
     * <p>For failure arrival streams this cursor is the number of inter-arrival samples that past
     * time windows have skipped or hit; for cost models it is the number of delay samples already
     * issued.</p>
     *
     * @return prefix length of the consumed samples
     */
    public int getConsumedSampleCount() {
        return cursor;
    }

    /**
     * Monotonically advances the sample consumption cursor past the given index.
     *
     * <p>This method serves cumulative arrival streams: when an arrival event lies in an idle
     * period that has already passed or in the current execution window, the caller must consume
     * that event and every inter-arrival sample before it, so that the same arrival is not
     * interpreted again by a later task. The call is idempotent for an index earlier than the
     * current cursor.</p>
     *
     * @param lastConsumedIndex index of the last sample that is to be treated as consumed
     * @throws IllegalArgumentException if the index does not belong to the current cache
     */
    public void consumeSamplesThrough(int lastConsumedIndex) {
        if (lastConsumedIndex < 0 || lastConsumedIndex >= samples.length) {
            throw new IllegalArgumentException(
                    "Consumed sample index " + lastConsumedIndex + " is outside the current sample cache");
        }
        cursor = Math.max(cursor, lastConsumedIndex + 1);
    }

    /** Extends the pre-sampled sequence with the same distribution and random stream. */
    public void extendSamples() {
        double[] newSamples = getDistribution(scale, shape).sample(SAMPLE_SIZE);
        samples = concat(samples, newSamples);
        updateCumulativeSamples();
    }

    /** Recomputes the cumulative sums from the current sample sequence. */
    public void updateCumulativeSamples() {
        cumulativeSamples = new double[samples.length];
        cumulativeSamples[0] = samples[0];
        for (int i = 1; i < samples.length; i++) {
            cumulativeSamples[i] = cumulativeSamples[i - 1] + samples[i];
        }
    }

    /**
     * Returns the estimate derived from the prior parameters.
     *
     * @return prior estimate
     */
    public double getPKEMean() {
        return this.shape_prior / this.scale_prior;
    }

    /**
     * Computes the arithmetic mean of the consumed samples.
     *
     * @return mean of the consumed samples
     * @throws IllegalStateException if no sample has been consumed yet
     */
    public double getMean() {
        if (cursor == 0) {
            throw new IllegalStateException("No distribution samples have been consumed");
        }
        double sum = 0.0;
        for (int i = 0; i < cursor; i++) {
            sum += samples[i];
        }
        return sum / cursor;
    }

    /** @return prior likelihood parameter */
    public double getLikelihoodPrior() {
        return this.likelihood_prior;
    }

    /**
     * Computes the maximum-likelihood-related estimate from the consumed samples and the prior
     * parameters.
     *
     * <p>The formula is retained only for the historical failure/cost experiment path; it is not a
     * general estimator for arbitrary distribution families, and returns {@code 0.0} for the
     * unsupported families.</p>
     *
     * @return estimated delay
     */
    public double getMLEMean() {
        double a = shape_prior, b = scale_prior;
        double sum = 0.0;

        for (int i = 0; i < cursor; i++) {
            switch (dist) {
                case GAMMA:
                    sum += samples[i];
                    break;
                case WEIBULL:
                    sum += Math.pow(samples[i], likelihood_prior);
                    break;
                    // Contract: unsupported distribution families (LOGNORMAL/NORMAL) contribute 0,
                    // so the method as a whole returns 0.0 (see the method javadoc).
                default:
                    break;
            }
        }
        double result = 0.0;
        switch (dist) {
            case GAMMA:
                result = (b + sum) / (a + cursor * likelihood_prior - 1);
                break;
            case WEIBULL:
                result = (b + sum) / (a + cursor + 1);
                break;
            default:
                break;
        }
        return result;
    }

    /**
     * Changes the distribution parameters and resamples, keeping the prior parameters unchanged.
     *
     * @param scale new scale parameter
     * @param shape new shape parameter
     */
    public void varyDistribution(double scale, double shape) {
        validateParameters(dist, scale, shape);
        this.scale = scale;
        this.shape = shape;
        RealDistribution distribution = getDistribution(scale, shape);
        samples = distribution.sample(SAMPLE_SIZE);
        updateCumulativeSamples();
        // Keeping the consumed position is historical behavior; a caller that needs to restart
        // should create a new generator.
    }

    /**
     * Concatenates two sample arrays.
     *
     * @param first leading sample segment
     * @param second trailing sample segment
     * @return new concatenated array
     */
    public double[] concat(double[] first, double[] second) {
        double[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /**
     * Obtains the next sample and extends the cache with the same distribution when it is
     * exhausted.
     *
     * @return next sampled delay
     */
    public double getNextSample() {
        while (cursor >= samples.length) {
            // Subclasses can preserve their own sampling semantics through extendSamples(),
            // for example periodic parameter modulation.
            extendSamples();
        }
        double delay = samples[cursor];
        cursor++;
        return delay;
    }

    /**
     * Creates a Commons Math distribution instance for the current family and parameters.
     *
     * @param scale scale parameter; the mean for the normal distribution
     * @param shape shape parameter; the standard deviation for the normal distribution
     * @return distribution instance bound to the random stream
     */
    public RealDistribution getDistribution(double scale, double shape) {
        validateParameters(dist, scale, shape);
        RealDistribution distribution = null;
        switch (this.dist) {
            case LOGNORMAL:
                distribution = new LogNormalDistribution(
                        randomGenerator, scale, shape, LogNormalDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            case WEIBULL:
                distribution = new WeibullDistribution(
                        randomGenerator, shape, scale, WeibullDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            case GAMMA:
                distribution = new GammaDistribution(
                        randomGenerator, shape, scale, GammaDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            case NORMAL:
                // For the normal distribution scale is the mean and shape is the standard deviation.
                distribution = new NormalDistribution(
                        randomGenerator, scale, shape, NormalDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            default:
                break;
        }
        return distribution;
    }

    /** @return current scale parameter */
    public double getScale() {
        return this.scale;
    }

    /** @return current distribution family */
    public DistributionFamily getFamily() {
        return this.dist;
    }

    /** @return current shape parameter */
    public double getShape() {
        return this.shape;
    }

    /** Resets the default random stream numbering when a new simulation configuration is installed. */
    public static synchronized void resetStreamAllocation() {
        streamSequence = 0L;
    }

    private static synchronized String nextDefaultStream(DistributionFamily family) {
        if (family == null) {
            throw new IllegalArgumentException("Distribution family cannot be null");
        }
        return "distribution." + family.name() + "." + streamSequence++;
    }

    private static void validateParameters(DistributionFamily family, double scale, double shape) {
        if (family == null
                || Double.isNaN(scale)
                || Double.isInfinite(scale)
                || Double.isNaN(shape)
                || Double.isInfinite(shape)) {
            throw new IllegalArgumentException("Distribution family and finite parameters are required");
        }
        if (shape <= 0.0 || (family != DistributionFamily.NORMAL && scale <= 0.0)) {
            throw new IllegalArgumentException("Distribution shape and non-normal scale must be positive");
        }
    }
}
