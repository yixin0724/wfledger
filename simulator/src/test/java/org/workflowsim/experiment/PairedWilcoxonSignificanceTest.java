package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.apache.commons.math3.stat.inference.WilcoxonSignedRankTest;
import org.junit.jupiter.api.Test;

class PairedWilcoxonSignificanceTest {

    @Test
    void identicalPairedSeriesAreUnavailableAllZero() {
        List<Double> same =
                Arrays.asList(Double.valueOf(3.0), Double.valueOf(5.5), Double.valueOf(7.25), Double.valueOf(9.0));
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(same, same);

        assertEquals("UNAVAILABLE_ALL_PAIRED_DIFFERENCES_ARE_ZERO", result.getStatus());
        assertNull(result.getPValue());
        assertFalse(result.isSignificantAtDefaultAlpha());
        assertEquals(4, result.getPairedSampleCount());
        assertEquals(0, result.getEffectiveSampleCount());
        assertEquals(0.0, result.getMedianDifference().doubleValue(), 0.0);
    }

    @Test
    void fewerThanTwoPairedSamplesAreUnavailable() {
        PairedWilcoxonSignificance single = PairedWilcoxonSignificance.evaluate(
                Arrays.asList(Double.valueOf(1.0)), Arrays.asList(Double.valueOf(2.0)));
        assertEquals("UNAVAILABLE_FEWER_THAN_TWO_PAIRED_SAMPLES", single.getStatus());
        assertNull(single.getPValue());
        assertEquals(1, single.getPairedSampleCount());

        PairedWilcoxonSignificance empty = PairedWilcoxonSignificance.evaluate(
                java.util.Collections.<Double>emptyList(), java.util.Collections.<Double>emptyList());
        assertEquals("UNAVAILABLE_FEWER_THAN_TWO_PAIRED_SAMPLES", empty.getStatus());
    }

    @Test
    void singleNonzeroDifferenceIsUnavailable() {
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(
                Arrays.asList(Double.valueOf(5.0), Double.valueOf(7.0)),
                Arrays.asList(Double.valueOf(5.0), Double.valueOf(9.0)));

        assertEquals("UNAVAILABLE_SINGLE_NONZERO_PAIRED_DIFFERENCE", result.getStatus());
        assertNull(result.getPValue());
        assertFalse(result.isSignificantAtDefaultAlpha());
        assertEquals(2, result.getPairedSampleCount());
        assertEquals(1, result.getEffectiveSampleCount());
    }

    @Test
    void consistentShiftIsSignificantWithExactDistribution() {

        List<Double> baseline = new ArrayList<Double>();
        List<Double> candidate = new ArrayList<Double>();
        for (int index = 0; index < 20; index++) {
            baseline.add(Double.valueOf(100.0 + index));
            candidate.add(Double.valueOf(80.0 + index));
        }
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(baseline, candidate);

        assertEquals("AVAILABLE_WILCOXON_SIGNED_RANK_EXACT", result.getStatus());
        assertNotNull(result.getPValue());
        assertTrue(result.getPValue().doubleValue() < 0.05);
        assertTrue(result.isSignificantAtDefaultAlpha());
        assertEquals(20, result.getEffectiveSampleCount());
        assertTrue(result.getMedianDifference().doubleValue() < 0.0, "median difference negative (candidate faster)");
    }

    @Test
    void alternatingSignSmallDifferencesAreNotSignificant() {

        List<Double> baseline = new ArrayList<Double>();
        List<Double> candidate = new ArrayList<Double>();
        for (int index = 0; index < 12; index++) {
            baseline.add(Double.valueOf(50.0));
            candidate.add(Double.valueOf(50.0 + (index % 2 == 0 ? 1.0 : -1.0)));
        }
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(baseline, candidate);

        assertEquals("AVAILABLE_WILCOXON_SIGNED_RANK_EXACT", result.getStatus());
        assertFalse(result.isSignificantAtDefaultAlpha());
        assertTrue(result.getPValue().doubleValue() > 0.05);

        assertTrue(result.getPValue().doubleValue() <= 1.0, "p-value must not exceed 1");
    }

    @Test
    void zeroDifferencesAreExcludedFromTheRankStatistic() {

        List<Double> baseline = new ArrayList<Double>();
        List<Double> candidate = new ArrayList<Double>();
        for (int index = 0; index < 6; index++) {
            baseline.add(Double.valueOf(100.0));
            candidate.add(Double.valueOf(100.0));
        }
        for (int index = 0; index < 12; index++) {
            baseline.add(Double.valueOf(100.0 + index));
            candidate.add(Double.valueOf(80.0 + index));
        }
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(baseline, candidate);

        assertEquals("AVAILABLE_WILCOXON_SIGNED_RANK_EXACT", result.getStatus());
        assertEquals(18, result.getPairedSampleCount());
        assertEquals(12, result.getEffectiveSampleCount());
        double[] nonzeroX = new double[12];
        double[] nonzeroY = new double[12];
        for (int index = 0; index < 12; index++) {
            nonzeroX[index] = baseline.get(6 + index).doubleValue();
            nonzeroY[index] = candidate.get(6 + index).doubleValue();
        }
        double subset = new WilcoxonSignedRankTest().wilcoxonSignedRankTest(nonzeroX, nonzeroY, true);
        assertEquals(subset, result.getPValue().doubleValue(), 0.0, "p-value must match the zero-excluded direct test");
    }

    @Test
    void moreThanThirtyPairsWithZeroDifferencesDoNotCrash() {

        List<Double> baseline = new ArrayList<Double>();
        List<Double> candidate = new ArrayList<Double>();
        for (int index = 0; index < 11; index++) {
            baseline.add(Double.valueOf(50.0));
            candidate.add(Double.valueOf(50.0));
        }
        for (int index = 0; index < 20; index++) {
            baseline.add(Double.valueOf(200.0 + index));
            candidate.add(Double.valueOf(190.0 + index));
        }
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(baseline, candidate);

        assertEquals("AVAILABLE_WILCOXON_SIGNED_RANK_EXACT", result.getStatus());
        assertEquals(31, result.getPairedSampleCount());
        assertEquals(20, result.getEffectiveSampleCount());
        assertNotNull(result.getPValue());
        assertTrue(result.getPValue().doubleValue() < 0.05);
    }

    @Test
    void allPositiveExactPValueIsLockedToTwoOverTwoToTheN() {

        List<Double> baseline = new ArrayList<Double>();
        List<Double> candidate = new ArrayList<Double>();
        for (int index = 0; index < 20; index++) {
            baseline.add(Double.valueOf(100.0 + index));
            candidate.add(Double.valueOf(110.0 + index));
        }
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(baseline, candidate);

        assertEquals("AVAILABLE_WILCOXON_SIGNED_RANK_EXACT", result.getStatus());
        assertEquals(2.0 / (1L << 20), result.getPValue().doubleValue(), 0.0);
    }

    @Test
    void largeSamplesUseTheNormalApproximation() {

        List<Double> baseline = new ArrayList<Double>();
        List<Double> candidate = new ArrayList<Double>();
        Random random = new Random(42L);
        for (int index = 0; index < 40; index++) {
            double base = 200.0 + random.nextDouble() * 50.0;
            baseline.add(Double.valueOf(base));
            candidate.add(Double.valueOf(base - 30.0 - random.nextDouble() * 10.0));
        }
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(baseline, candidate);

        assertEquals("AVAILABLE_WILCOXON_SIGNED_RANK_NORMAL_APPROXIMATION", result.getStatus());
        assertTrue(result.getPValue().doubleValue() < 0.05);
        assertTrue(result.isSignificantAtDefaultAlpha());
        assertEquals(40, result.getEffectiveSampleCount());
    }

    @Test
    void pValueMatchesDirectCommonsMathInvocation() {
        List<Double> baseline = new ArrayList<Double>();
        List<Double> candidate = new ArrayList<Double>();
        Random random = new Random(20260911L);
        for (int index = 0; index < 18; index++) {
            double base = 100.0 + random.nextGaussian() * 10.0;
            baseline.add(Double.valueOf(base));
            candidate.add(Double.valueOf(base - 5.0 + random.nextGaussian() * 3.0));
        }
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(baseline, candidate);

        double[] x = new double[18];
        double[] y = new double[18];
        for (int index = 0; index < 18; index++) {
            x[index] = baseline.get(index).doubleValue();
            y[index] = candidate.get(index).doubleValue();
        }
        double direct = new WilcoxonSignedRankTest().wilcoxonSignedRankTest(x, y, true);
        assertEquals(direct, result.getPValue().doubleValue(), 1.0e-12);
    }

    @Test
    void unpairedIndependentReplicationsHaveNoPairedTest() {
        PairedWilcoxonSignificance unavailable = PairedWilcoxonSignificance.unavailableUnpaired();
        assertEquals("UNAVAILABLE_INDEPENDENT_REPLICATIONS_ARE_NOT_PAIRED", unavailable.getStatus());
        assertNull(unavailable.getPValue());
        assertFalse(unavailable.isSignificantAtDefaultAlpha());
        assertEquals(0, unavailable.getPairedSampleCount());
    }

    @Test
    void invalidInputsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PairedWilcoxonSignificance.evaluate(null, Arrays.asList(Double.valueOf(1.0))));
        assertThrows(
                IllegalArgumentException.class,
                () -> PairedWilcoxonSignificance.evaluate(
                        Arrays.asList(Double.valueOf(1.0), Double.valueOf(2.0)), Arrays.asList(Double.valueOf(1.0))));
        assertThrows(
                IllegalArgumentException.class,
                () -> PairedWilcoxonSignificance.evaluate(
                        Arrays.asList(Double.NaN, Double.valueOf(2.0)),
                        Arrays.asList(Double.valueOf(1.0), Double.valueOf(2.0))));
        assertThrows(
                IllegalArgumentException.class,
                () -> PairedWilcoxonSignificance.evaluate(
                        Arrays.asList(Double.valueOf(1.0), Double.valueOf(2.0)),
                        Arrays.asList(Double.valueOf(1.0), Double.POSITIVE_INFINITY)));
    }
}
