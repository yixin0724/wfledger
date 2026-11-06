package org.workflowsim.utils;

import java.util.Random;

import org.apache.commons.math3.distribution.ExponentialDistribution;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.random.JDKRandomGenerator;

/** Reshi prediction-error distributions with the official sign-and-clamp convention. */
public final class ReshiPredictionErrorModel {

    public enum DistributionFamily {
        NORMAL,
        EXPONENTIAL
    }

    private final DistributionFamily family;

    public ReshiPredictionErrorModel(DistributionFamily family) {
        if (family == null) {
            throw new IllegalArgumentException("Reshi prediction-error family is required");
        }
        this.family = family;
    }

    /** Samples a non-negative runtime multiplier from the declared error fraction. */
    public double sampleFactor(double errorFraction, Random random) {
        if (!(errorFraction >= 0.0) || !Double.isFinite(errorFraction)) {
            throw new IllegalArgumentException("Reshi error fraction must be finite and non-negative");
        }
        if (errorFraction == 0.0) {
            return 1.0;
        }
        double sample;
        if (family == DistributionFamily.NORMAL) {
            JDKRandomGenerator generator = new JDKRandomGenerator();
            generator.setSeed(random.nextLong());
            sample = new NormalDistribution(generator, 1.0, 0.5, NormalDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY)
                    .sample();
        } else {
            JDKRandomGenerator generator = new JDKRandomGenerator();
            generator.setSeed(random.nextLong());
            sample = new ExponentialDistribution(
                            generator, 1.0, ExponentialDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY)
                    .sample();
        }
        if (random.nextDouble() > 0.5) {
            return 1.0 + sample * errorFraction;
        }
        return Math.max(1.0 - sample * errorFraction, 0.0);
    }
}
