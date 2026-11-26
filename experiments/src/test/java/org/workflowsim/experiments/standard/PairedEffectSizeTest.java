package org.workflowsim.experiments.standard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class PairedEffectSizeTest {

    private static final List<Double> BASELINE = Arrays.asList(10.0, 20.0, 30.0, 40.0, 50.0);
    private static final List<Double> CANDIDATE = Arrays.asList(8.0, 22.0, 27.0, 40.0, 45.0);

    @Test
    void rankBiserialMatchesTheSimpleDifferenceFormulaWithTiesAndZeros() {
        assertEquals(-0.7, PairedEffectSize.matchedPairsRankBiserial(BASELINE, CANDIDATE), 1.0e-12);
        assertEquals(0.7, PairedEffectSize.matchedPairsRankBiserial(CANDIDATE, BASELINE), 1.0e-12);
        assertNull(PairedEffectSize.matchedPairsRankBiserial(BASELINE, BASELINE));

        assertEquals(
                -1.0, PairedEffectSize.matchedPairsRankBiserial(Arrays.asList(5.0, 6.0), Arrays.asList(1.0, 2.0)), 0.0);
    }

    @Test
    void medianRatioAndCliffsDeltaMatchTheHandCounts() {
        assertEquals(0.9, PairedEffectSize.medianRatio(BASELINE, CANDIDATE), 1.0e-12);
        assertEquals(-0.08, PairedEffectSize.cliffsDelta(BASELINE, CANDIDATE), 1.0e-12);
        assertEquals(1.05, PairedEffectSize.medianRatio(Arrays.asList(1.0, 1.0), Arrays.asList(1.0, 1.1)), 1.0e-12);
        assertThrows(IllegalArgumentException.class, () -> PairedEffectSize.cliffsDelta(BASELINE, Arrays.asList(1.0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> PairedEffectSize.medianRatio(Arrays.asList(0.0), Arrays.asList(1.0)));
    }
}
