package org.workflowsim.utils;

import org.workflowsim.utils.DistributionGenerator.DistributionFamily;

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

    public static DistributionSpec of(DistributionFamily family, double scale, double shape) {
        validate(family, scale, shape, null, null, null);
        return new DistributionSpec(family, scale, shape, null, null, null);
    }

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

    public DistributionFamily getFamily() {
        return family;
    }

    public double getScale() {
        return scale;
    }

    public double getShape() {
        return shape;
    }

    public Double getPriorShape() {
        return priorShape;
    }

    public Double getPriorScale() {
        return priorScale;
    }

    public Double getLikelihoodPrior() {
        return likelihoodPrior;
    }

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
