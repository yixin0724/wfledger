package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.math3.stat.inference.WilcoxonSignedRankTest;
import org.apache.commons.math3.stat.ranking.NaturalRanking;

public final class PairedWilcoxonSignificance {

    public static final double DEFAULT_ALPHA = 0.05;

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

    public static PairedWilcoxonSignificance unavailableUnpaired() {
        return new PairedWilcoxonSignificance(
                "UNAVAILABLE_INDEPENDENT_REPLICATIONS_ARE_NOT_PAIRED", null, false, 0, 0, null);
    }

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

        double[] pairedBaseline = new double[nonzeroIndices.size()];
        double[] pairedCandidate = new double[nonzeroIndices.size()];
        for (int slot = 0; slot < nonzeroIndices.size(); slot++) {
            int index = nonzeroIndices.get(slot).intValue();
            pairedBaseline[slot] = baseline.get(index).doubleValue();
            pairedCandidate[slot] = candidate.get(index).doubleValue();
        }
        boolean exact = nonzeroIndices.size() <= MAX_EXACT_EFFECTIVE_SAMPLES;
        double pValue;
        if (exact) {

            pValue = exactTwoSidedPValue(pairedBaseline, pairedCandidate);
        } else {
            pValue = new WilcoxonSignedRankTest().wilcoxonSignedRankTest(pairedBaseline, pairedCandidate, false);
        }
        pValue = Math.min(pValue, 1.0);
        return new PairedWilcoxonSignificance(
                exact ? "AVAILABLE_WILCOXON_SIGNED_RANK_EXACT" : "AVAILABLE_WILCOXON_SIGNED_RANK_NORMAL_APPROXIMATION",
                Double.valueOf(pValue),
                pValue < DEFAULT_ALPHA,
                pairedCount,
                nonzeroIndices.size(),
                Double.valueOf(medianDifference));
    }

    private static double exactTwoSidedPValue(double[] baseline, double[] candidate) {
        int count = baseline.length;
        double[] differences = new double[count];
        double[] absolute = new double[count];
        for (int index = 0; index < count; index++) {
            differences[index] = candidate[index] - baseline[index];
            absolute[index] = Math.abs(differences[index]);
        }
        double[] ranks = new NaturalRanking().rank(absolute);
        int totalRank = 0;
        int positiveRank = 0;
        int[] doubledRanks = new int[count];
        for (int index = 0; index < count; index++) {
            doubledRanks[index] = (int) Math.round(ranks[index] * 2.0);
            totalRank += doubledRanks[index];
            if (differences[index] > 0.0) {
                positiveRank += doubledRanks[index];
            }
        }
        int observedMinimum = Math.min(positiveRank, totalRank - positiveRank);
        double[] subsets = new double[totalRank + 1];
        subsets[0] = 1.0;
        for (int rank : doubledRanks) {
            for (int sum = totalRank; sum >= rank; sum--) {
                subsets[sum] += subsets[sum - rank];
            }
        }
        double tail = 0.0;
        for (int sum = 0; sum <= totalRank; sum++) {
            if (Math.min(sum, totalRank - sum) <= observedMinimum) {
                tail += subsets[sum];
            }
        }
        return tail / Math.pow(2.0, count);
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

    public String getStatus() {
        return status;
    }

    public Double getPValue() {
        return pValue;
    }

    public boolean isSignificantAtDefaultAlpha() {
        return significantAtDefaultAlpha;
    }

    public int getPairedSampleCount() {
        return pairedSampleCount;
    }

    public int getEffectiveSampleCount() {
        return effectiveSampleCount;
    }

    public Double getMedianDifference() {
        return medianDifference;
    }

    public double getAlpha() {
        return DEFAULT_ALPHA;
    }
}
