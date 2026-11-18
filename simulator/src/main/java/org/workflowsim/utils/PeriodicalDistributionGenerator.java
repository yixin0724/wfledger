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

public class PeriodicalDistributionGenerator extends DistributionGenerator {

    protected PeriodicalSignal signal;

    public PeriodicalDistributionGenerator(
            DistributionFamily dist, double scale, double shape, PeriodicalSignal signal) {
        super(dist, scale, shape);
        this.signal = requireSignal(signal);

        double currentTime = 0.0;
        samples = generatePeriodicalSamples(currentTime);

        updateCumulativeSamples();
        cursor = 0;
    }

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

    @Override
    public void extendSamples() {
        double currentTime = cumulativeSamples[cumulativeSamples.length - 1];
        double[] newSamples = generatePeriodicalSamples(currentTime);
        samples = concat(samples, newSamples);
        updateCumulativeSamples();
    }

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
