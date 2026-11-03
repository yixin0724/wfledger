package org.workflowsim.utils;

import org.workflowsim.utils.DistributionGenerator.DistributionFamily;

/**
 * Immutable description of the parameters of a sampling distribution, independent of any
 * random seed.
 *
 * <p>This object stores only the distribution family and its parameters and holds no random
 * number generator. Every call to {@link #createGenerator(String)} creates a new generator
 * from the specified {@link SimulationRandom} stream, which decouples the configuration
 * lifetime from the random sequence lifetime.</p>
 */
public final class DistributionSpec {

    private final DistributionFamily family;
    private final double scale;
    private final double shape;
    private final Double priorShape;
    private final Double priorScale;
    private final Double likelihoodPrior;

    private DistributionSpec(
            DistributionFamily family,
            double scale,
            double shape,
            Double priorShape,
            Double priorScale,
            Double likelihoodPrior) {
        this.family = family;
        this.scale = scale;
        this.shape = shape;
        this.priorShape = priorShape;
        this.priorScale = priorScale;
        this.likelihoodPrior = likelihoodPrior;
    }

    /**
     * Creates a distribution description without prior parameters.
     *
     * @param family distribution family
     * @param scale scale parameter
     * @param shape shape parameter
     * @return validated distribution description
     */
    public static DistributionSpec of(DistributionFamily family, double scale, double shape) {
        validate(family, scale, shape, null, null, null);
        return new DistributionSpec(family, scale, shape, null, null, null);
    }

    /**
     * Creates a distribution description with the full set of prior parameters.
     *
     * @param family distribution family
     * @param scale scale parameter
     * @param shape shape parameter
     * @param priorShape prior shape parameter
     * @param priorScale prior scale parameter
     * @param likelihoodPrior likelihood prior parameter
     * @return validated distribution description
     */
    public static DistributionSpec withPriors(
            DistributionFamily family,
            double scale,
            double shape,
            double priorShape,
            double priorScale,
            double likelihoodPrior) {
        validate(family, scale, shape, priorShape, priorScale, likelihoodPrior);
        return new DistributionSpec(family, scale, shape, priorShape, priorScale, likelihoodPrior);
    }

    /** @return distribution family */
    public DistributionFamily getFamily() {
        return family;
    }
    /** @return scale parameter */
    public double getScale() {
        return scale;
    }
    /** @return shape parameter */
    public double getShape() {
        return shape;
    }
    /** @return prior shape parameter; {@code null} when no prior is configured */
    public Double getPriorShape() {
        return priorShape;
    }
    /** @return prior scale parameter; {@code null} when no prior is configured */
    public Double getPriorScale() {
        return priorScale;
    }
    /** @return likelihood prior parameter; {@code null} when no prior is configured */
    public Double getLikelihoodPrior() {
        return likelihoodPrior;
    }

    /**
     * Creates a new distribution generator bound to the specified named random stream.
     *
     * @param randomStream stable random component name
     * @return generator bound to that random stream
     */
    public DistributionGenerator createGenerator(String randomStream) {
        if (priorShape == null) {
            return new DistributionGenerator(family, scale, shape, randomStream);
        }
        return new DistributionGenerator(family, scale, shape, priorShape, priorScale, likelihoodPrior, randomStream);
    }

    private static void validate(
            DistributionFamily family,
            double scale,
            double shape,
            Double priorShape,
            Double priorScale,
            Double likelihoodPrior) {
        if (family == null || !isFinite(scale) || !isFinite(shape)) {
            throw new IllegalArgumentException("Distribution family and finite parameters are required");
        }
        if (shape <= 0.0 || (family != DistributionFamily.NORMAL && scale <= 0.0)) {
            throw new IllegalArgumentException("Distribution shape and non-normal scale must be positive");
        }
        validatePriors(priorShape, priorScale, likelihoodPrior);
    }

    /**
     * 2026-10-26 complexity extraction from validate; statements verbatim; order unchanged.
     * Scope: validates only that the three Bayesian prior parameters are supplied together and
     * are all finite; otherwise throws a parameter validation exception.
     */
    private static void validatePriors(Double priorShape, Double priorScale, Double likelihoodPrior) {
        boolean anyPrior = priorShape != null || priorScale != null || likelihoodPrior != null;
        boolean allPriors = priorShape != null && priorScale != null && likelihoodPrior != null;
        if (anyPrior != allPriors
                || (allPriors && (!isFinite(priorShape) || !isFinite(priorScale) || !isFinite(likelihoodPrior)))) {
            throw new IllegalArgumentException("Distribution priors must be supplied together and be finite");
        }
    }

    private static boolean isFinite(double value) {
        return !Double.isInfinite(value) && !Double.isNaN(value);
    }
}
