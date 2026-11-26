package org.workflowsim.experiments.standard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.math3.stat.ranking.NaturalRanking;

/** Paired effect sizes reported next to the Wilcoxon test of the standard study. */
public final class PairedEffectSize {

    private PairedEffectSize() {}

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
