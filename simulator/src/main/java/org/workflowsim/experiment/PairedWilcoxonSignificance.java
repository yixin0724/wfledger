package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.math3.stat.inference.WilcoxonSignedRankTest;

/**
 * Runs a Wilcoxon signed-rank significance test on two simulation-run series paired by root seed.
 *
 * <p>This is the inference layer of campaign paired comparison: given the metric observations of
 * a candidate scheme and a baseline scheme under the same root seeds (for example makespan), it
 * tests whether the distribution of paired differences is centered on zero. The test is
 * non-parametric and assumes no normality, which suits the skewed distributions typical of
 * simulation output.</p>
 *
 * <p><b>Inference boundary (declared as is)</b>: the random model of the platform is currently
 * not an event-keyed model, so reusing a root seed does not constitute strict
 * common-random-numbers pairing (see {@link RandomizationDesign}). The verdict of this test must
 * therefore be read as "a distribution difference between paired series that share root seeds",
 * not as a variance-reduction inference under CRN pairing. The status string is recorded together
 * with {@link ExperimentCampaignSummary.ComparisonSummary#getInferenceStatus()} so that the
 * framing of the inference claim stays traceable in the evidence chain.</p>
 *
 * <p><b>Implementation framing</b>:</p>
 * <ul>
 * <li>Pairs are aligned by input index (the caller is responsible for supplying two equal-length
 *     series in the same seed order);</li>
 * <li>Pairs with a zero difference do not enter the rank statistics (standard Wilcoxon practice),
 *     and the effective sample count shrinks accordingly (R8 audit fix: previously the whole
 *     array including zero differences was passed straight to commons-math3, which contradicted
 *     the framing of this document, and made the exact path throw NumberIsTooLargeException once
 *     the full pair count exceeded 30);</li>
 * <li>With at most 25 effective nonzero differences the exact distribution is used (the exact
 *     two-sided p of the library is not upper-truncated, so this wrapper clamps it to [0,1]);
 *     otherwise the normal approximation is used. Note that the asymptotic path of commons-math3
 *     3.2 applies a continuity correction in the -0.5 direction (biased toward significance,
 *     anti-conservative) and does no tie variance correction; know that bias when reading an
 *     asymptotic p value;</li>
 * <li>When every difference is zero (for example a deterministic configuration) no test is run;
 *     an unavailable status is returned instead of a fabricated p value.</li>
 * </ul>
 *
 * <p>This class is immutable and copies every collection defensively.</p>
 *
 * @since R1 (multi-seed statistics framework)
 */
public final class PairedWilcoxonSignificance {

    /** Default significance level. */
    public static final double DEFAULT_ALPHA = 0.05;

    /** Maximum number of effective nonzero differences for which the exact distribution applies. */
    private static final int MAX_EXACT_EFFECTIVE_SAMPLES = 25;

    private final String status;
    private final Double pValue;
    private final boolean significantAtDefaultAlpha;
    private final int pairedSampleCount;
    private final int effectiveSampleCount;
    private final Double medianDifference;

    private PairedWilcoxonSignificance(
            String status,
            Double pValue,
            boolean significant,
            int pairedSampleCount,
            int effectiveSampleCount,
            Double medianDifference) {
        this.status = status;
        this.pValue = pValue;
        this.significantAtDefaultAlpha = significant;
        this.pairedSampleCount = pairedSampleCount;
        this.effectiveSampleCount = effectiveSampleCount;
        this.medianDifference = medianDifference;
    }

    /**
     * Builds the unavailable result for "an independent-replication design does not admit a
     * paired test".
     *
     * <p>Used for the comparison under {@link RandomizationDesign#INDEPENDENT_REPLICATIONS} in a
     * campaign: the seeds on the two sides are distinct, so no paired difference exists.</p>
     *
     * @return immutable result with status
     *         {@code UNAVAILABLE_INDEPENDENT_REPLICATIONS_ARE_NOT_PAIRED}
     */
    public static PairedWilcoxonSignificance unavailableUnpaired() {
        return new PairedWilcoxonSignificance(
                "UNAVAILABLE_INDEPENDENT_REPLICATIONS_ARE_NOT_PAIRED", null, false, 0, 0, null);
    }

    /**
     * Tests whether the distribution of the paired differences, candidate minus baseline, is
     * centered on zero.
     *
     * @param baseline metric observations of the baseline scheme in seed order, all finite
     * @param candidate metric observations of the candidate scheme in the same seed order, all
     *        finite
     * @return immutable result carrying the status, the p value, and the default significance
     *         verdict
     * @throws IllegalArgumentException if either series is empty, the lengths differ, or a value
     *         is not finite
     */
    public static PairedWilcoxonSignificance evaluate(List<Double> baseline, List<Double> candidate) {
        validateSamples(baseline, candidate);
        int pairedCount = baseline.size();
        if (pairedCount < 2) {
            return new PairedWilcoxonSignificance(
                    "UNAVAILABLE_FEWER_THAN_TWO_PAIRED_SAMPLES", null, false, pairedCount, 0, null);
        }
        List<Double> differences = new ArrayList<Double>(pairedCount);
        for (int index = 0; index < pairedCount; index++) {
            differences.add(Double.valueOf(
                    candidate.get(index).doubleValue() - baseline.get(index).doubleValue()));
        }
        double medianDifference = median(differences);
        List<Integer> nonzeroIndices = new ArrayList<Integer>(pairedCount);
        for (int index = 0; index < pairedCount; index++) {
            if (differences.get(index).doubleValue() != 0.0) {
                nonzeroIndices.add(Integer.valueOf(index));
            }
        }
        if (nonzeroIndices.isEmpty()) {
            return new PairedWilcoxonSignificance(
                    "UNAVAILABLE_ALL_PAIRED_DIFFERENCES_ARE_ZERO",
                    null,
                    false,
                    pairedCount,
                    0,
                    Double.valueOf(medianDifference));
        }
        if (nonzeroIndices.size() == 1) {
            return new PairedWilcoxonSignificance(
                    "UNAVAILABLE_SINGLE_NONZERO_PAIRED_DIFFERENCE",
                    null,
                    false,
                    pairedCount,
                    1,
                    Double.valueOf(medianDifference));
        }
        // R8 audit fix (P1): pass only the nonzero-difference pairs into the rank test --
        // commons-math3 does not drop zero differences (they occupy the lowest ranks and inflate
        // N), so passing the whole array distorts both the p value and effectiveSampleCount, and
        // once the full pair count exceeds 30 the exact path additionally throws
        // NumberIsTooLargeException and breaks the campaign summary. After the zero differences
        // are removed, the internal N of the library and the gate condition (nonzero count at
        // most 25 < 30) agree by construction.
        double[] pairedBaseline = new double[nonzeroIndices.size()];
        double[] pairedCandidate = new double[nonzeroIndices.size()];
        for (int slot = 0; slot < nonzeroIndices.size(); slot++) {
            int index = nonzeroIndices.get(slot).intValue();
            pairedBaseline[slot] = baseline.get(index).doubleValue();
            pairedCandidate[slot] = candidate.get(index).doubleValue();
        }
        WilcoxonSignedRankTest test = new WilcoxonSignedRankTest();
        boolean exact = nonzeroIndices.size() <= MAX_EXACT_EFFECTIVE_SAMPLES;
        double pValue = test.wilcoxonSignedRankTest(pairedBaseline, pairedCandidate, exact);
        // The exact two-sided p of commons-math3 is 2 * (number of subsets with rank sum >= W) / 2^N
        // with no upper truncation, so under ties or when W sits exactly in the middle it can
        // slightly exceed 1; clamp it to the [0,1] domain of a p value.
        pValue = Math.min(pValue, 1.0);
        return new PairedWilcoxonSignificance(
                exact ? "AVAILABLE_WILCOXON_SIGNED_RANK_EXACT" : "AVAILABLE_WILCOXON_SIGNED_RANK_NORMAL_APPROXIMATION",
                Double.valueOf(pValue),
                pValue < DEFAULT_ALPHA,
                pairedCount,
                nonzeroIndices.size(),
                Double.valueOf(medianDifference));
    }

    private static void validateSamples(List<Double> baseline, List<Double> candidate) {
        if (baseline == null || candidate == null) {
            throw new IllegalArgumentException("Both paired sample series are required");
        }
        if (baseline.size() != candidate.size()) {
            throw new IllegalArgumentException(
                    "Paired sample series must have identical length: " + baseline.size() + " vs " + candidate.size());
        }
        for (Double value : baseline) {
            requireFinite(value);
        }
        for (Double value : candidate) {
            requireFinite(value);
        }
    }

    private static void requireFinite(Double value) {
        if (value == null || Double.isNaN(value.doubleValue()) || Double.isInfinite(value.doubleValue())) {
            throw new IllegalArgumentException("Paired samples must be finite");
        }
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2).doubleValue();
        }
        return (sorted.get(size / 2 - 1).doubleValue() + sorted.get(size / 2).doubleValue()) / 2.0;
    }

    /**
     * @return machine-readable test status:
     * {@code AVAILABLE_WILCOXON_SIGNED_RANK_EXACT},
     * {@code AVAILABLE_WILCOXON_SIGNED_RANK_NORMAL_APPROXIMATION},
     * {@code UNAVAILABLE_FEWER_THAN_TWO_PAIRED_SAMPLES},
     * {@code UNAVAILABLE_SINGLE_NONZERO_PAIRED_DIFFERENCE}, or
     * {@code UNAVAILABLE_ALL_PAIRED_DIFFERENCES_ARE_ZERO}
     */
    public String getStatus() {
        return status;
    }

    /** @return two-sided p value; {@code null} when the status is unavailable */
    public Double getPValue() {
        return pValue;
    }

    /** @return whether the null hypothesis is rejected at {@link #DEFAULT_ALPHA} */
    public boolean isSignificantAtDefaultAlpha() {
        return significantAtDefaultAlpha;
    }

    /** @return total number of pairs aligned by seed */
    public int getPairedSampleCount() {
        return pairedSampleCount;
    }

    /** @return number of nonzero differences entering the rank statistics */
    public int getEffectiveSampleCount() {
        return effectiveSampleCount;
    }

    /** @return median of the paired differences (candidate minus baseline); {@code null} when pairs are too few */
    public Double getMedianDifference() {
        return medianDifference;
    }

    /** @return default significance level used by this test */
    public double getAlpha() {
        return DEFAULT_ALPHA;
    }
}
