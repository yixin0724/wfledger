package org.workflowsim.experiments.standard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * STD-V1 (2026-11-25): hand-derived effect sizes (Kerby 2014 simple difference formula; Cliff 1993).
 *
 * <p>Baseline (10, 20, 30, 40, 50), candidate (8, 22, 27, 40, 45): differences (-2, +2, -3, 0, -5). The zero is
 * dropped; |d| = (2, 2, 3, 5) has average ranks (1.5, 1.5, 3, 4). W+ = 1.5 (the +2), W- = 1.5 + 3 + 4 = 8.5, so
 * r = (1.5 - 8.5) / 10 = -0.7. Ratios (0.8, 1.1, 0.9, 1.0, 0.9) sorted (0.8, 0.9, 0.9, 1.0, 1.1): median 0.9.
 * Cliff: over the 25 pairs, candidate c > baseline b counts: 8 -> 0, 22 -> 2, 27 -> 2, 40 -> 3, 45 -> 4: 11;
 * c < b counts: 8 -> 5, 22 -> 3, 27 -> 3, 40 -> 1, 45 -> 1: 13 (ties: 40 = 40 once); delta = (11 - 13) / 25 = -0.08.
 */
class PairedEffectSizeTest {

    private static final List<Double> BASELINE = Arrays.asList(10.0, 20.0, 30.0, 40.0, 50.0);
    private static final List<Double> CANDIDATE = Arrays.asList(8.0, 22.0, 27.0, 40.0, 45.0);

    @Test
    void rankBiserialMatchesTheSimpleDifferenceFormulaWithTiesAndZeros() {
        assertEquals(-0.7, PairedEffectSize.matchedPairsRankBiserial(BASELINE, CANDIDATE), 1.0e-12);
        assertEquals(0.7, PairedEffectSize.matchedPairsRankBiserial(CANDIDATE, BASELINE), 1.0e-12);
        assertNull(PairedEffectSize.matchedPairsRankBiserial(BASELINE, BASELINE));
        // All candidates smaller: r = -1.
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
