package org.workflowsim.experiments.standard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.math3.stat.ranking.NaturalRanking;

/**
 * Paired effect sizes reported next to the Wilcoxon test of the standard study.
 *
 * <p>STD-V1 (2026-11-25). Root cause: the platform reported p values and median differences only, so a study could
 * not state how large an effect is in scale-free terms. Three declared effect sizes, all over matched instances:
 *
 * <ul>
 *   <li><b>Median ratio</b>: median over instances of {@code candidate_i / baseline_i} (scale-free; below 1 means
 *       the candidate is smaller, i.e. better for a minimised metric). Even counts average the two middle
 *       values.</li>
 *   <li><b>Matched-pairs rank-biserial correlation</b> (Kerby, "The simple difference formula", Comprehensive
 *       Psychology 3, 2014): with {@code d_i = candidate_i - baseline_i}, zero differences dropped (the same rule
 *       as {@code PairedWilcoxonSignificance}), average ranks of {@code |d_i|}, {@code W+} and {@code W-} the rank
 *       sums of positive and negative differences: {@code r = (W+ - W-) / (W+ + W-)} in {@code [-1, 1]}; negative
 *       means the candidate is mostly smaller. It is the effect size that matches the signed-rank test.</li>
 *   <li><b>Cliff's delta</b> (Cliff, Psychological Bulletin 114(3), 1993), the unpaired dominance statistic
 *       {@code (#(c_i > b_j) - #(c_i < b_j)) / (n * n)} over all candidate/baseline pairs, reported for
 *       comparability with studies that use it; it ignores the pairing.</li>
 * </ul>
 */
public final class PairedEffectSize {

    private PairedEffectSize() {}

    /**
     * Returns the median of the per-instance ratios.
     *
     * @param baseline baseline values, positive
     * @param candidate candidate values, same length
     * @return median ratio
     */
    public static double medianRatio(List<Double> baseline, List<Double> candidate) {
        validate(baseline, candidate);
        List<Double> ratios = new ArrayList<Double>();
        for (int i = 0; i < baseline.size(); i++) {
            if (!(baseline.get(i).doubleValue() > 0.0)) {
                throw new IllegalArgumentException(
                        "Median ratio requires positive baseline values: " + baseline.get(i));
            }
            ratios.add(Double.valueOf(
                    candidate.get(i).doubleValue() / baseline.get(i).doubleValue()));
        }
        Collections.sort(ratios);
        int n = ratios.size();
        return n % 2 == 1
                ? ratios.get(n / 2).doubleValue()
                : (ratios.get(n / 2 - 1).doubleValue() + ratios.get(n / 2).doubleValue()) / 2.0;
    }

    /**
     * Returns the matched-pairs rank-biserial correlation, or {@code null} when every difference is zero.
     *
     * @param baseline baseline values
     * @param candidate candidate values, same length
     * @return {@code r} or null
     */
    public static Double matchedPairsRankBiserial(List<Double> baseline, List<Double> candidate) {
        validate(baseline, candidate);
        List<Double> differences = new ArrayList<Double>();
        for (int i = 0; i < baseline.size(); i++) {
            double d = candidate.get(i).doubleValue() - baseline.get(i).doubleValue();
            if (d != 0.0) {
                differences.add(Double.valueOf(d));
            }
        }
        if (differences.isEmpty()) {
            return null;
        }
        double[] absolute = new double[differences.size()];
        for (int i = 0; i < absolute.length; i++) {
            absolute[i] = Math.abs(differences.get(i).doubleValue());
        }
        double[] ranks = new NaturalRanking().rank(absolute);
        double positive = 0.0;
        double negative = 0.0;
        for (int i = 0; i < ranks.length; i++) {
            if (differences.get(i).doubleValue() > 0.0) {
                positive += ranks[i];
            } else {
                negative += ranks[i];
            }
        }
        return Double.valueOf((positive - negative) / (positive + negative));
    }

    /**
     * Returns Cliff's delta of candidate versus baseline.
     *
     * @param baseline baseline values
     * @param candidate candidate values, same length
     * @return delta in {@code [-1, 1]}
     */
    public static double cliffsDelta(List<Double> baseline, List<Double> candidate) {
        validate(baseline, candidate);
        long greater = 0L;
        long less = 0L;
        for (Double c : candidate) {
            for (Double b : baseline) {
                int comparison = Double.compare(c.doubleValue(), b.doubleValue());
                if (comparison > 0) {
                    greater++;
                } else if (comparison < 0) {
                    less++;
                }
            }
        }
        return (greater - less) / ((double) candidate.size() * baseline.size());
    }

    private static void validate(List<Double> baseline, List<Double> candidate) {
        if (baseline == null || candidate == null || baseline.isEmpty() || baseline.size() != candidate.size()) {
            throw new IllegalArgumentException("Effect sizes require two non-empty series of equal length");
        }
        for (int i = 0; i < baseline.size(); i++) {
            if (!isFinite(baseline.get(i)) || !isFinite(candidate.get(i))) {
                throw new IllegalArgumentException("Effect sizes require finite values");
            }
        }
    }

    private static boolean isFinite(Double value) {
        return value != null && !Double.isNaN(value.doubleValue()) && !Double.isInfinite(value.doubleValue());
    }
}
