package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;

class DistributionSpecTest {

    @Test
    void rejectsNullFamilyAndNonFiniteOrNonPositiveParameters() {
        assertThrows(IllegalArgumentException.class, () -> DistributionSpec.of(null, 1.0, 1.0));
        assertThrows(
                IllegalArgumentException.class, () -> DistributionSpec.of(DistributionFamily.WEIBULL, Double.NaN, 1.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> DistributionSpec.of(DistributionFamily.WEIBULL, 1.0, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> DistributionSpec.of(DistributionFamily.WEIBULL, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> DistributionSpec.of(DistributionFamily.WEIBULL, 1.0, -0.5));
        assertThrows(IllegalArgumentException.class, () -> DistributionSpec.of(DistributionFamily.GAMMA, 0.0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> DistributionSpec.of(DistributionFamily.GAMMA, -1.0, 1.0));
    }

    @Test
    void normalFamilyIsExemptFromPositiveScaleRequirement() {
        DistributionSpec spec = DistributionSpec.of(DistributionFamily.NORMAL, 0.0, 2.0);
        assertEquals(DistributionFamily.NORMAL, spec.getFamily());
        assertEquals(0.0, spec.getScale(), 0.0);
        assertEquals(2.0, spec.getShape(), 0.0);
    }

    @Test
    void priorsMustBeSuppliedTogetherAndBeFinite() {

        assertThrows(
                IllegalArgumentException.class,
                () -> DistributionSpec.withPriors(DistributionFamily.GAMMA, 1.0, 1.0, Double.NaN, 3.0, 1.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> DistributionSpec.withPriors(
                        DistributionFamily.GAMMA, 1.0, 1.0, 2.0, Double.POSITIVE_INFINITY, 1.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> DistributionSpec.withPriors(
                        DistributionFamily.GAMMA, 1.0, 1.0, 2.0, 3.0, Double.NEGATIVE_INFINITY));

        DistributionSpec spec = DistributionSpec.withPriors(DistributionFamily.GAMMA, 1.5, 2.5, 3.5, 4.5, 0.25);
        assertEquals(3.5, spec.getPriorShape(), 0.0);
        assertEquals(4.5, spec.getPriorScale(), 0.0);
        assertEquals(0.25, spec.getLikelihoodPrior(), 0.0);
    }

    @Test
    void generatorCreationBindsTheNamedRandomStream() {
        DistributionSpec plain = DistributionSpec.of(DistributionFamily.WEIBULL, 12.0, 2.0);
        assertNull(plain.getPriorShape());
        assertNull(plain.getPriorScale());
        assertNull(plain.getLikelihoodPrior());
        DistributionGenerator plainGenerator = plain.createGenerator("audit.plain");
        assertNotNull(plainGenerator);
        assertEquals(DistributionFamily.WEIBULL, plainGenerator.getFamily());
        assertEquals(12.0, plainGenerator.getScale(), 0.0);
        assertEquals(2.0, plainGenerator.getShape(), 0.0);

        DistributionSpec priored = DistributionSpec.withPriors(DistributionFamily.GAMMA, 7.0, 3.0, 1.0, 2.0, 0.5);
        DistributionGenerator prioredGenerator = priored.createGenerator("audit.priored");
        assertNotNull(prioredGenerator);
        assertEquals(DistributionFamily.GAMMA, prioredGenerator.getFamily());
        assertEquals(7.0, prioredGenerator.getScale(), 0.0);
        assertEquals(3.0, prioredGenerator.getShape(), 0.0);
    }
}
