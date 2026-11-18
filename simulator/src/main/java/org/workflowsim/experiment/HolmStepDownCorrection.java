package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class HolmStepDownCorrection {

    private HolmStepDownCorrection() {}

    public static List<Adjusted> adjustWithinFamily(List<Double> rawPValues) {
        if (rawPValues == null) {
            throw new IllegalArgumentException("family must not be null");
        }
        for (Double raw : rawPValues) {
            if (raw != null) {
                double value = raw.doubleValue();
                if (!isFinite(value) || value < 0.0 || value > 1.0) {
                    throw new IllegalArgumentException("raw p values must be finite in [0, 1], got " + value);
                }
            }
        }

        List<Adjusted> results = new ArrayList<Adjusted>(rawPValues.size());
        for (int index = 0; index < rawPValues.size(); index++) {
            results.add(new Adjusted(null, false));
        }

        List<Integer> testedIndices = new ArrayList<Integer>();
        for (int index = 0; index < rawPValues.size(); index++) {
            if (rawPValues.get(index) != null) {
                testedIndices.add(Integer.valueOf(index));
            }
        }
        Collections.sort(
                testedIndices,
                Comparator.comparingDouble(index -> rawPValues.get(index).doubleValue()));

        int familySize = rawPValues.size();
        double previous = 0.0;
        for (int rank = 0; rank < testedIndices.size(); rank++) {
            int index = testedIndices.get(rank).intValue();
            double raw = rawPValues.get(index).doubleValue();
            double adjusted = Math.max(previous, Math.min(1.0, (familySize - rank) * raw));
            previous = adjusted;
            results.set(
                    index, new Adjusted(Double.valueOf(adjusted), adjusted < PairedWilcoxonSignificance.DEFAULT_ALPHA));
        }
        return results;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    public static final class Adjusted {

        private final Double adjustedPValue;
        private final boolean significantAfterHolm;

        private Adjusted(Double adjustedPValue, boolean significantAfterHolm) {
            this.adjustedPValue = adjustedPValue;
            this.significantAfterHolm = significantAfterHolm;
        }

        public Double getAdjustedPValue() {
            return adjustedPValue;
        }

        public boolean isSignificantAfterHolm() {
            return significantAfterHolm;
        }
    }
}
