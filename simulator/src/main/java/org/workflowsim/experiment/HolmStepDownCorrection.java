package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Holm (1979) step-down correction for within-family multiplicity, shared by every
 * read path that reports p values for more than one comparison.
 *
 * <p>Reference: Sture Holm, "A simple sequentially rejective multiple test
 * procedure", Scandinavian Journal of Statististics 6(2), 1979, pp. 65-70. Family
 * members are ordered by ascending raw p value; the member at rank {@code i} is
 * compared against {@code (m - i) * p} where {@code m} is the family size, and the
 * reported adjusted values are the running maximum, clamped to 1.</p>
 *
 * <p>Registered platform convention (conservative, deliberately stricter than the
 * textbook variant that counts only tested hypotheses): the multiplier base is the
 * <em>declared</em> family size, including members whose p value is unavailable.
 * An unavailable member consumes correction budget but never receives an adjusted
 * value: it is reported as {@code null} and never significant. The network study
 * path and the Python auditor ({@code scripts/verify-study-statistics.py}) follow
 * the identical convention, so all three read paths must be changed together if
 * this ever changes.</p>
 *
 * <p>A member is significant after correction only when its adjusted p value is
 * strictly below {@link PairedWilcoxonSignificance#DEFAULT_ALPHA}.</p>
 *
 * <p>History: (AUD-F06, 2026-11-03) previously only the network study path applied
 * Holm; the generic campaign path reported raw p values. The arithmetic was moved
 * here so the two Java paths cannot drift, with the network record protocol left
 * byte-identical.</p>
 */
public final class HolmStepDownCorrection {

    private HolmStepDownCorrection() {}

    /**
     * Apply the step-down correction to one family of raw p values.
     *
     * @param rawPValues raw p values in declared family order; a {@code null}
     *                   entry marks a family member whose paired test produced no
     *                   p value, which still consumes correction budget
     * @return one result per input entry, positionally aligned with the input;
     *         unavailable members carry a {@code null} adjusted value
     * @throws IllegalArgumentException if the family is {@code null} or a
     *                                  non-null p value is not finite in [0, 1]
     */
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

    /**
     * One family member's correction outcome.
     */
    public static final class Adjusted {

        private final Double adjustedPValue;
        private final boolean significantAfterHolm;

        private Adjusted(Double adjustedPValue, boolean significantAfterHolm) {
            this.adjustedPValue = adjustedPValue;
            this.significantAfterHolm = significantAfterHolm;
        }

        /**
         * @return the step-down adjusted p value, or {@code null} when the raw
         *         p value was unavailable
         */
        public Double getAdjustedPValue() {
            return adjustedPValue;
        }

        /**
         * @return whether the adjusted p value is strictly below
         *         {@link PairedWilcoxonSignificance#DEFAULT_ALPHA}; always
         *         {@code false} for unavailable members
         */
        public boolean isSignificantAfterHolm() {
            return significantAfterHolm;
        }
    }
}
