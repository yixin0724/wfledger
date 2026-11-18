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

    protected final int SAMPLE_SIZE = 1500;

    private final RandomGenerator randomGenerator;
    private static long streamSequence;

    public enum DistributionFamily {
        LOGNORMAL,
        GAMMA,
        WEIBULL,
        NORMAL
    }

    public DistributionGenerator(DistributionFamily dist, double scale, double shape) {
        this(dist, scale, shape, nextDefaultStream(dist));
    }

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

    public DistributionGenerator(DistributionFamily dist, double scale, double shape, double a, double b, double c) {
        this(dist, scale, shape, a, b, c, nextDefaultStream(dist));
    }

    public DistributionGenerator(
            DistributionFamily dist, double scale, double shape, double a, double b, double c, String randomStream) {
        this(dist, scale, shape, randomStream);
        this.scale_prior = b;
        this.shape_prior = a;
        this.likelihood_prior = c;
    }

    public double[] getSamples() {
        return samples;
    }

    public double[] getCumulativeSamples() {
        return cumulativeSamples;
    }

    public int getConsumedSampleCount() {
        return cursor;
    }

    public void consumeSamplesThrough(int lastConsumedIndex) {
        if (lastConsumedIndex < 0 || lastConsumedIndex >= samples.length) {
            throw new IllegalArgumentException(
                    "Consumed sample index " + lastConsumedIndex + " is outside the current sample cache");
        }
        cursor = Math.max(cursor, lastConsumedIndex + 1);
    }

    public void extendSamples() {
        double[] newSamples = getDistribution(scale, shape).sample(SAMPLE_SIZE);
        samples = concat(samples, newSamples);
        updateCumulativeSamples();
    }

    public void updateCumulativeSamples() {
        cumulativeSamples = new double[samples.length];
        cumulativeSamples[0] = samples[0];
        for (int i = 1; i < samples.length; i++) {
            cumulativeSamples[i] = cumulativeSamples[i - 1] + samples[i];
        }
    }

    public double getPKEMean() {
        return this.shape_prior / this.scale_prior;
    }

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

    public double getLikelihoodPrior() {
        return this.likelihood_prior;
    }

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

    public void varyDistribution(double scale, double shape) {
        validateParameters(dist, scale, shape);
        this.scale = scale;
        this.shape = shape;
        RealDistribution distribution = getDistribution(scale, shape);
        samples = distribution.sample(SAMPLE_SIZE);
        updateCumulativeSamples();
    }

    public double[] concat(double[] first, double[] second) {
        double[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    public double getNextSample() {
        while (cursor >= samples.length) {

            extendSamples();
        }
        double delay = samples[cursor];
        cursor++;
        return delay;
    }

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
                distribution = new NormalDistribution(
                        randomGenerator, scale, shape, NormalDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            default:
                break;
        }
        return distribution;
    }

    public double getScale() {
        return this.scale;
    }

    public DistributionFamily getFamily() {
        return this.dist;
    }

    public double getShape() {
        return this.shape;
    }

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
