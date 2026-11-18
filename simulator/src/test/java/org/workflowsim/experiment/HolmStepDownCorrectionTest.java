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

class HolmStepDownCorrectionTest {

    @Test
    void threeTestedHypothesesFollowTheStepDownMultipliers() {

        List<Double> family = new ArrayList<Double>();
        family.add(Double.valueOf(0.02));
        family.add(Double.valueOf(0.01));
        family.add(Double.valueOf(0.04));
        List<HolmStepDownCorrection.Adjusted> adjusted = HolmStepDownCorrection.adjustWithinFamily(family);
        assertEquals(3, adjusted.size());
        assertEquals(0.04, adjusted.get(0).getAdjustedPValue().doubleValue(), 1.0e-15);
        assertEquals(0.03, adjusted.get(1).getAdjustedPValue().doubleValue(), 1.0e-15);
        assertEquals(0.04, adjusted.get(2).getAdjustedPValue().doubleValue(), 1.0e-15);

        assertTrue(adjusted.get(0).isSignificantAfterHolm());
        assertTrue(adjusted.get(1).isSignificantAfterHolm());
        assertTrue(adjusted.get(2).isSignificantAfterHolm());
    }

    @Test
    void adjustedValuesNeverDecreaseAcrossRanks() {

        List<Double> family = new ArrayList<Double>();
        family.add(Double.valueOf(0.01));
        family.add(Double.valueOf(0.04));
        List<HolmStepDownCorrection.Adjusted> adjusted = HolmStepDownCorrection.adjustWithinFamily(family);
        assertEquals(0.02, adjusted.get(0).getAdjustedPValue().doubleValue(), 1.0e-15);
        assertEquals(0.04, adjusted.get(1).getAdjustedPValue().doubleValue(), 1.0e-15);
    }

    @Test
    void smallestRawPValueIsTheOnlyPossibleSurvivor() {

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
