/*
 *
 *   Copyright 2012-2013 University Of Southern California
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

import org.apache.commons.math3.distribution.RealDistribution;

/**
 * Distribution generator that switches its parameters within a period according to a
 * {@link PeriodicalSignal}.
 *
 * <p>It inherits the pre-sampling mechanism of {@link DistributionGenerator} and selects, at each
 * sample position, the distribution that corresponds to the upper or the lower bound chosen by the
 * periodical signal. This mechanism is a simplified abstraction of periodic overhead.</p>
 *
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 11, 2014
 * @author Weiwei Chen
 */
public class PeriodicalDistributionGenerator extends DistributionGenerator {

    /** Periodical signal that controls the switching of the distribution parameters. */
    protected PeriodicalSignal signal;

    /**
     * Create a periodical distribution generator without prior knowledge.
     *
     * @param dist distribution family
     * @param scale scale parameter
     * @param shape shape parameter
     * @param signal periodical signal
     */
    public PeriodicalDistributionGenerator(
            DistributionFamily dist, double scale, double shape, PeriodicalSignal signal) {
        super(dist, scale, shape);
        this.signal = requireSignal(signal);
        // Initial samples must be drawn from the distribution selected by the periodical signal,
        // not from the fixed-distribution sampling of the parent class.
        double currentTime = 0.0;
        samples = generatePeriodicalSamples(currentTime);

        updateCumulativeSamples();
        cursor = 0;
    }

    /**
     * Create a periodical distribution generator with prior knowledge.
     *
     * @param dist distribution family
     * @param scale scale parameter
     * @param shape shape parameter
     * @param a prior shape parameter
     * @param b prior scale parameter
     * @param c prior likelihood parameter
     * @param signal periodical signal
     */
    public PeriodicalDistributionGenerator(
            DistributionFamily dist,
            double scale,
            double shape,
            double a,
            double b,
            double c,
            PeriodicalSignal signal) {
        super(dist, scale, shape, a, b, c);
        this.signal = requireSignal(signal);
        double currentTime = 0.0;
        samples = generatePeriodicalSamples(currentTime);
        updateCumulativeSamples();
        cursor = 0;
    }

    /**
     * Create a periodical distribution generator with prior knowledge and a stable random stream
     * name.
     *
     * <p>An explicit random stream prevents historical examples from sharing the default stream
     * identity because of construction order within the same model.</p>
     *
     * @param dist distribution family
     * @param scale base scale parameter
     * @param shape shape parameter
     * @param a prior shape parameter
     * @param b prior scale parameter
     * @param c prior likelihood parameter
     * @param signal periodical signal
     * @param randomStream stable random component name
     */
    public PeriodicalDistributionGenerator(
            DistributionFamily dist,
            double scale,
            double shape,
            double a,
            double b,
            double c,
            PeriodicalSignal signal,
            String randomStream) {
        super(dist, scale, shape, a, b, c, randomStream);
        this.signal = requireSignal(signal);
        double currentTime = 0.0;
        samples = generatePeriodicalSamples(currentTime);
        updateCumulativeSamples();
        cursor = 0;
    }
    /** Extend the pre-sampled sequence at the current cumulative simulation time. */
    @Override
    public void extendSamples() {
        double currentTime = cumulativeSamples[cumulativeSamples.length - 1];
        double[] newSamples = generatePeriodicalSamples(currentTime);
        samples = concat(samples, newSamples);
        updateCumulativeSamples();
    }

    /**
     * Generate a batch of samples that switch according to the periodical signal.
     *
     * @param currentTime current cumulative simulation time
     * @return new sample array
     */
    private double[] generatePeriodicalSamples(double currentTime) {
        RealDistribution distributionUpper = getDistribution(signal.getUpperBound(), shape);
        RealDistribution distributionLower = getDistribution(signal.getLowerBound(), shape);
        RealDistribution distribution;
        double[] periodicalSamples = new double[SAMPLE_SIZE];
        boolean direction = signal.getDirection();
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            if (currentTime % signal.getPeriod() < signal.getPeriod() * signal.getPortion()) {
                if (direction) {
                    distribution = distributionUpper;
                } else {
                    distribution = distributionLower;
                }
            } else {
                if (direction) {
                    distribution = distributionLower;
                } else {
                    distribution = distributionUpper;
                }
            }
            periodicalSamples[i] = distribution.sample();
            currentTime += periodicalSamples[i];
        }
        return periodicalSamples;
    }

    private static PeriodicalSignal requireSignal(PeriodicalSignal value) {
        if (value == null) {
            throw new IllegalArgumentException("Periodical signal cannot be null");
        }
        return value;
    }
}
