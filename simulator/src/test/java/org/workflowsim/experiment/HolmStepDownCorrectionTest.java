package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Hand-derived expectations for the Holm (1979) step-down procedure under the
 * platform's registered family convention: the multiplier base is the declared
 * family size, including members whose p value is unavailable.
 *
 * <p>Every expected value below is derived from the procedure definition, not from
 * the implementation: sorted ascending, the member at rank {@code i} is compared
 * against {@code (m - i) * p}, and reported adjusted values are the running
 * maximum, clamped to 1.</p>
 */
class HolmStepDownCorrectionTest {

    @Test
    void threeTestedHypothesesFollowTheStepDownMultipliers() {
        // m = 3; sorted raw p = (0.01, 0.02, 0.04)
        // rank 0: 3 * 0.01 = 0.03; rank 1: max(0.03, 2 * 0.02) = 0.04;
        // rank 2: max(0.04, 1 * 0.04) = 0.04
        List<Double> family = new ArrayList<Double>();
        family.add(Double.valueOf(0.02));
        family.add(Double.valueOf(0.01));
        family.add(Double.valueOf(0.04));
        List<HolmStepDownCorrection.Adjusted> adjusted = HolmStepDownCorrection.adjustWithinFamily(family);
        assertEquals(3, adjusted.size());
        assertEquals(0.04, adjusted.get(0).getAdjustedPValue().doubleValue(), 1.0e-15);
        assertEquals(0.03, adjusted.get(1).getAdjustedPValue().doubleValue(), 1.0e-15);
        assertEquals(0.04, adjusted.get(2).getAdjustedPValue().doubleValue(), 1.0e-15);
        // All three adjusted values (0.03, 0.04, 0.04) stay below the 0.05
        // default alpha, so every member survives the step-down here.
        assertTrue(adjusted.get(0).isSignificantAfterHolm());
        assertTrue(adjusted.get(1).isSignificantAfterHolm());
        assertTrue(adjusted.get(2).isSignificantAfterHolm());
    }

    @Test
    void adjustedValuesNeverDecreaseAcrossRanks() {
        // m = 2; rank 0: 2 * 0.01 = 0.02; rank 1: max(0.02, 1 * 0.04) = 0.04
        List<Double> family = new ArrayList<Double>();
        family.add(Double.valueOf(0.01));
        family.add(Double.valueOf(0.04));
        List<HolmStepDownCorrection.Adjusted> adjusted = HolmStepDownCorrection.adjustWithinFamily(family);
        assertEquals(0.02, adjusted.get(0).getAdjustedPValue().doubleValue(), 1.0e-15);
        assertEquals(0.04, adjusted.get(1).getAdjustedPValue().doubleValue(), 1.0e-15);
    }

    @Test
    void smallestRawPValueIsTheOnlyPossibleSurvivor() {
        // m = 2; sorted raw p = (0.008, 0.20)
        // rank 0: 2 * 0.008 = 0.016 < 0.05 survives; rank 1: max(0.016, 1 * 0.20) = 0.20
        List<Double> family = new ArrayList<Double>();
        family.add(Double.valueOf(0.20));
        family.add(Double.valueOf(0.008));
        List<HolmStepDownCorrection.Adjusted> adjusted = HolmStepDownCorrection.adjustWithinFamily(family);
        assertEquals(0.20, adjusted.get(0).getAdjustedPValue().doubleValue(), 1.0e-15);
        assertEquals(0.016, adjusted.get(1).getAdjustedPValue().doubleValue(), 1.0e-15);
        assertFalse(adjusted.get(0).isSignificantAfterHolm());
        assertTrue(adjusted.get(1).isSignificantAfterHolm());
    }

    @Test
    void untestableMembersConsumeCorrectionBudget() {
        // Registered conservative convention: the declared family size (2) is the
        // multiplier base, so the single tested p = 0.25 is doubled to 0.5. The
        // textbook variant that counts only tested hypotheses would report 0.25.
        List<Double> family = new ArrayList<Double>();
        family.add(null);
        family.add(Double.valueOf(0.25));
        List<HolmStepDownCorrection.Adjusted> adjusted = HolmStepDownCorrection.adjustWithinFamily(family);
        assertNull(adjusted.get(0).getAdjustedPValue());
        assertFalse(adjusted.get(0).isSignificantAfterHolm());
        assertEquals(0.5, adjusted.get(1).getAdjustedPValue().doubleValue(), 1.0e-15);
        assertFalse(adjusted.get(1).isSignificantAfterHolm());
    }

    @Test
    void adjustedValuesClampToOne() {
        // m = 2; sorted raw p = (0.6, 0.7); rank 0: min(1, 2 * 0.6) = 1;
        // rank 1: max(1, min(1, 0.7)) = 1
        List<Double> family = new ArrayList<Double>();
        family.add(Double.valueOf(0.7));
        family.add(Double.valueOf(0.6));
        List<HolmStepDownCorrection.Adjusted> adjusted = HolmStepDownCorrection.adjustWithinFamily(family);
        assertEquals(1.0, adjusted.get(0).getAdjustedPValue().doubleValue(), 0.0);
        assertEquals(1.0, adjusted.get(1).getAdjustedPValue().doubleValue(), 0.0);
        assertFalse(adjusted.get(0).isSignificantAfterHolm());
        assertFalse(adjusted.get(1).isSignificantAfterHolm());
    }

    @Test
    void singleMemberFamilyKeepsItsOwnPValue() {
        List<Double> aboveAlpha = new ArrayList<Double>();
        aboveAlpha.add(Double.valueOf(0.07));
        List<HolmStepDownCorrection.Adjusted> adjusted = HolmStepDownCorrection.adjustWithinFamily(aboveAlpha);
        assertEquals(0.07, adjusted.get(0).getAdjustedPValue().doubleValue(), 0.0);
        assertFalse(adjusted.get(0).isSignificantAfterHolm());

        List<Double> belowAlpha = new ArrayList<Double>();
        belowAlpha.add(Double.valueOf(0.03));
        adjusted = HolmStepDownCorrection.adjustWithinFamily(belowAlpha);
        assertEquals(0.03, adjusted.get(0).getAdjustedPValue().doubleValue(), 0.0);
        assertTrue(adjusted.get(0).isSignificantAfterHolm());

        // The significance verdict is strict: an adjusted p exactly equal to the
        // default alpha is not a rejection.
        List<Double> exactlyAlpha = new ArrayList<Double>();
        exactlyAlpha.add(Double.valueOf(PairedWilcoxonSignificance.DEFAULT_ALPHA));
        adjusted = HolmStepDownCorrection.adjustWithinFamily(exactlyAlpha);
        assertEquals(0.05, adjusted.get(0).getAdjustedPValue().doubleValue(), 0.0);
        assertFalse(adjusted.get(0).isSignificantAfterHolm());
    }

    @Test
    void emptyAndAllNullFamiliesYieldNoAdjustments() {
        assertTrue(HolmStepDownCorrection.adjustWithinFamily(Collections.<Double>emptyList())
                .isEmpty());
        List<Double> family = new ArrayList<Double>();
        family.add(null);
        family.add(null);
        List<HolmStepDownCorrection.Adjusted> adjusted = HolmStepDownCorrection.adjustWithinFamily(family);
        assertEquals(2, adjusted.size());
        assertNull(adjusted.get(0).getAdjustedPValue());
        assertNull(adjusted.get(1).getAdjustedPValue());
        assertFalse(adjusted.get(0).isSignificantAfterHolm());
        assertFalse(adjusted.get(1).isSignificantAfterHolm());
    }

    @Test
    void rejectsMissingFamilyAndInvalidPValues() {
        assertThrows(IllegalArgumentException.class, () -> HolmStepDownCorrection.adjustWithinFamily(null));
        List<Double> aboveOne = new ArrayList<Double>();
        aboveOne.add(Double.valueOf(1.5));
        assertThrows(IllegalArgumentException.class, () -> HolmStepDownCorrection.adjustWithinFamily(aboveOne));
        List<Double> negative = new ArrayList<Double>();
        negative.add(Double.valueOf(-0.1));
        assertThrows(IllegalArgumentException.class, () -> HolmStepDownCorrection.adjustWithinFamily(negative));
        List<Double> notANumber = new ArrayList<Double>();
        notANumber.add(Double.valueOf(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> HolmStepDownCorrection.adjustWithinFamily(notANumber));
    }
}
