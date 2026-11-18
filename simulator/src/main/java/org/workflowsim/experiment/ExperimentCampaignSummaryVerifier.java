package org.workflowsim.experiment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.TDistribution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class ExperimentCampaignSummaryVerifier {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final double CONFIDENCE_LEVEL = 0.95;

    private ExperimentCampaignSummaryVerifier() {}

    static void verify(Path index, JsonObject document) throws IOException {
        Map<String, CellData> cells = readCells(requireObject(document, "plan"));
        readRuns(index, requireArray(document, "runs"), cells);
        JsonObject expected = new JsonObject();
        JsonArray expectedCells = new JsonArray();
        for (CellData cell : cells.values()) {
            cell.finish();
            expectedCells.add(cell.summary);
        }
        expected.add("cells", expectedCells);
        expected.add("comparisons", comparisons(cells));
        if (!expected.equals(document.get("summary"))) {
            throw new IOException("Campaign summary does not match the independently recomputed per-run evidence");
        }
    }

    private static Map<String, CellData> readCells(JsonObject plan) throws IOException {
        Map<String, CellData> cells = new LinkedHashMap<String, CellData>();
        for (JsonElement element : requireArray(plan, "cells")) {
            JsonObject value = object(element);
            CellData cell = new CellData(value);
            if (cells.put(cell.id, cell) != null) {
                throw new IOException("Duplicate campaign plan cell: " + cell.id);
            }
        }
        if (cells.isEmpty()) {
            throw new IOException("Campaign plan has no cells");
        }
        return cells;
    }

    private static void readRuns(Path index, JsonArray records, Map<String, CellData> cells) throws IOException {
        for (JsonElement element : records) {
            JsonObject record = object(element);
            String cellId = string(record, "cellId");
            CellData cell = cells.get(cellId);
            if (cell == null) {
                throw new IOException("Campaign run references undeclared cell " + cellId);
            }
            if (!cell.group.equals(string(record, "comparisonGroup"))
                    || !cell.candidateId.equals(string(record, "candidateId"))
                    || cell.baseline != bool(record, "baseline")) {
                throw new IOException("Campaign run identity differs from its plan cell: " + cellId);
            }
            int replication = integer(record, "replicationIndex");
            long seed = longInteger(record, "seed");
            if (replication < 0
                    || replication >= cell.rootSeeds.size()
                    || cell.rootSeeds.get(replication).longValue() != seed) {
                throw new IOException("Campaign run seed differs from its plan: " + cellId + "/" + replication);
            }
            ExperimentArtifactValidator.ValidationResult evidence =
                    ExperimentArtifactValidator.validate(index.getParent().resolve(string(record, "manifest")));
            JsonObject metrics = requireObject(evidence.getManifestSnapshot(), "metrics");
            if (Double.compare(number(record, "makespanSeconds"), number(metrics, "makespanSeconds")) != 0) {
                throw new IOException("Campaign run makespan differs from its validated manifest: " + cellId);
            }
            cell.runs.add(new RunData(replication, metrics));
        }
    }

    private static JsonArray comparisons(Map<String, CellData> cells) throws IOException {
        Map<String, List<CellData>> groups = new LinkedHashMap<String, List<CellData>>();
        for (CellData cell : cells.values()) {
            List<CellData> group = groups.get(cell.group);
            if (group == null) {
                group = new ArrayList<CellData>();
                groups.put(cell.group, group);
            }
            group.add(cell);
        }
        JsonArray result = new JsonArray();
        for (List<CellData> group : groups.values()) {
            CellData baseline = null;
            for (CellData cell : group) {
                if (cell.baseline) {
                    if (baseline != null) {
                        throw new IOException("Comparison group has multiple baselines: " + cell.group);
                    }
                    baseline = cell;
                }
            }
            if (baseline == null) {
                throw new IOException("Comparison group has no baseline: " + group.get(0).group);
            }
            List<RawComparison> family = new ArrayList<RawComparison>();
            List<Double> rawPValues = new ArrayList<Double>();
            for (CellData candidate : group) {
                if (candidate == baseline) {
                    continue;
                }
                RawComparison comparison = compare(baseline, candidate);
                family.add(comparison);
                rawPValues.add(comparison.paired.getPValue());
            }
            List<HolmStepDownCorrection.Adjusted> adjusted = HolmStepDownCorrection.adjustWithinFamily(rawPValues);
            for (int index = 0; index < family.size(); index++) {
                RawComparison comparison = family.get(index);
                HolmStepDownCorrection.Adjusted correction = adjusted.get(index);
                comparison.document.addProperty("holmAdjustedPValue", correction.getAdjustedPValue());
                comparison.document.addProperty(
                        "significantAfterHolmAtDefaultAlpha", correction.isSignificantAfterHolm());
                result.add(comparison.document);
            }
        }
        return result;
    }

    private static RawComparison compare(CellData baseline, CellData candidate) {
        JsonObject document = new JsonObject();
        document.addProperty("comparisonGroup", baseline.group);
        document.addProperty("baselineCellId", baseline.id);
        document.addProperty("candidateCellId", candidate.id);
        double baselineMean = mean(baseline.makespans);
        double candidateMean = mean(candidate.makespans);
        double baselineCost = mean(baseline.costs);
        double candidateCost = mean(candidate.costs);
        boolean independent = baseline.design == RandomizationDesign.INDEPENDENT_REPLICATIONS;
        List<Double> matchedBaseline = new ArrayList<Double>();
        List<Double> matchedCandidate = new ArrayList<Double>();
        List<Double> deltas = new ArrayList<Double>();
        if (!independent) {
            Map<Long, Double> baselineBySeed = new HashMap<Long, Double>();
            for (int index = 0; index < baseline.rootSeeds.size(); index++) {
                baselineBySeed.put(baseline.rootSeeds.get(index), baseline.makespans.get(index));
            }
            for (int index = 0; index < candidate.rootSeeds.size(); index++) {
                Long seed = candidate.rootSeeds.get(index);
                Double reference = baselineBySeed.get(seed);
                if (reference != null) {
                    matchedBaseline.add(reference);
                    double value = candidate.makespans.get(index).doubleValue();
                    matchedCandidate.add(Double.valueOf(value));
                    deltas.add(Double.valueOf(value - reference.doubleValue()));
                }
            }
        }
        document.addProperty("matchedRootSeedCount", deltas.size());
        document.addProperty("meanMatchedMakespanDeltaSeconds", deltas.isEmpty() ? null : Double.valueOf(mean(deltas)));
        document.addProperty("baselineMeanMakespanSeconds", baselineMean);
        document.addProperty("candidateMeanMakespanSeconds", candidateMean);
        document.addProperty("meanMakespanRatio", ratio(candidateMean, baselineMean));
        document.addProperty("baselineRelativeMakespanSpeedup", ratio(baselineMean, candidateMean));
        document.addProperty(
                "makespanImprovementPercent",
                baselineMean == 0.0 ? null : Double.valueOf(100.0 * (baselineMean - candidateMean) / baselineMean));
        document.addProperty("baselineMeanModeledProcessingCost", baselineCost);
        document.addProperty("candidateMeanModeledProcessingCost", candidateCost);
        document.addProperty("meanModeledProcessingCostRatio", ratio(candidateCost, baselineCost));
        document.addProperty(
                "successfulLogicalTaskCompletionRateDelta",
                mean(candidate.completionRates) - mean(baseline.completionRates));
        document.addProperty(
                "successfulWorkflowRunRateDelta",
                difference(candidate.workflowSuccessRate, baseline.workflowSuccessRate));
        document.addProperty(
                "inferenceStatus",
                independent
                        ? "UNPAIRED_INDEPENDENT_REPLICATIONS"
                        : baseline.design == RandomizationDesign.DETERMINISTIC
                                ? "DESCRIPTIVE_ONLY_DETERMINISTIC"
                                : "DESCRIPTIVE_ONLY_COMMON_ROOT_SEEDS_ARE_NOT_EVENT_KEYED_CRN");
        PairedWilcoxonSignificance paired = independent
                ? PairedWilcoxonSignificance.unavailableUnpaired()
                : PairedWilcoxonSignificance.evaluate(matchedBaseline, matchedCandidate);
        document.add("pairedSignificance", pairedDocument(paired));
        return new RawComparison(document, paired);
    }

    private static JsonObject pairedDocument(PairedWilcoxonSignificance value) {
        JsonObject document = new JsonObject();
        document.addProperty("status", value.getStatus());
        document.addProperty("pValue", value.getPValue());
        document.addProperty("significantAtDefaultAlpha", value.isSignificantAtDefaultAlpha());
        document.addProperty("pairedSampleCount", value.getPairedSampleCount());
        document.addProperty("effectiveSampleCount", value.getEffectiveSampleCount());
        document.addProperty("medianDifference", value.getMedianDifference());
        return document;
    }

    private static final class CellData {
        private final String id;
        private final String group;
        private final String candidateId;
        private final boolean baseline;
        private final RandomizationDesign design;
        private final List<Long> rootSeeds;
        private final List<RunData> runs = new ArrayList<RunData>();
        private final List<Double> makespans = new ArrayList<Double>();
        private final List<Double> costs = new ArrayList<Double>();
        private final List<Double> completionRates = new ArrayList<Double>();
        private final List<Double> waitingTimes = new ArrayList<Double>();
        private final List<Double> trueSlowdowns = new ArrayList<Double>();
        private final List<Double> utilizations = new ArrayList<Double>();
        private final List<Double> jainIndexes = new ArrayList<Double>();
        private final List<Double> successfulCompletionTimes = new ArrayList<Double>();
        private int eligibleRuns;
        private int successfulRuns;
        private int incompleteRuns;
        private int noLogicalTaskRuns;
        private Double workflowSuccessRate;
        private JsonObject summary;

        CellData(JsonObject value) throws IOException {
            id = string(value, "id");
            group = string(value, "comparisonGroup");
            candidateId = string(value, "candidateId");
            baseline = bool(value, "baseline");
            try {
                design = RandomizationDesign.valueOf(string(value, "randomizationDesign"));
            } catch (IllegalArgumentException failure) {
                throw new IOException("Unknown campaign randomization design", failure);
            }
            rootSeeds = longList(value, "rootSeeds");
            if (rootSeeds.isEmpty()) {
                throw new IOException("Campaign plan cell has no root seeds: " + id);
            }
        }

        void finish() throws IOException {
            Collections.sort(runs, Comparator.comparingInt(run -> run.replication));
            int previous = -1;
            for (RunData run : runs) {
                if (run.replication != previous + 1) {
                    throw new IOException("Campaign replications are incomplete or duplicated for cell " + id);
                }
                previous = run.replication;
                accumulate(run.metrics);
            }
            if (runs.size() != rootSeeds.size()) {
                throw new IOException("Campaign run count differs from the seed plan for cell " + id);
            }
            workflowSuccessRate = eligibleRuns == 0 ? null : Double.valueOf(((double) successfulRuns) / eligibleRuns);
            summary = new JsonObject();
            summary.addProperty("cellId", id);
            summary.addProperty("comparisonGroup", group);
            summary.addProperty("candidateId", candidateId);
            summary.addProperty("baseline", baseline);
            summary.addProperty("randomizationDesign", design.name());
            summary.add("rootSeeds", longArray(rootSeeds));
            summary.add("makespanSeconds", metric(makespans, design));
            summary.add("modeledProcessingCost", metric(costs, design));
            summary.add("successfulLogicalTaskCompletionRate", metric(completionRates, design));
            summary.add("meanComputeTotalWaitingTimeSeconds", metric(waitingTimes, design));
            summary.add("meanComputeTrueSlowdown", trueSlowdowns.isEmpty() ? null : metric(trueSlowdowns, design));
            summary.addProperty("trueSlowdownObservationRunCount", trueSlowdowns.size());
            summary.add("meanVmModeledIntervalUtilization", metric(utilizations, design));
            summary.add("vmUtilizationJainFairnessIndex", metric(jainIndexes, design));
            summary.add("workflowRunCompletion", workflowCompletion());
            summary.add("successfulWorkflowLogicalCompletionSeconds", conditionalCompletion());
        }

        private void accumulate(JsonObject metrics) throws IOException {
            makespans.add(Double.valueOf(number(metrics, "makespanSeconds")));
            costs.add(Double.valueOf(number(metrics, "totalModeledProcessingCost")));
            completionRates.add(Double.valueOf(number(metrics, "successfulLogicalTaskCompletionRate")));
            waitingTimes.add(Double.valueOf(number(metrics, "meanComputeTotalWaitingTimeSeconds")));
            if (integer(metrics, "trueSlowdownObservationCount") > 0) {
                trueSlowdowns.add(Double.valueOf(number(metrics, "meanComputeTrueSlowdown")));
            }
            utilizations.add(Double.valueOf(number(metrics, "meanVmModeledIntervalUtilization")));
            jainIndexes.add(Double.valueOf(number(metrics, "vmUtilizationJainFairnessIndex")));
            if (integer(metrics, "logicalTaskCount") == 0) {
                noLogicalTaskRuns++;
                return;
            }
            eligibleRuns++;
            if (bool(metrics, "allLogicalTasksCompletedSuccessfully")) {
                successfulRuns++;
                if (!"COMPLETED_SUCCESSFULLY".equals(string(metrics, "logicalTaskCompletionStatus"))) {
                    throw new IOException("Successful workflow lacks logical completion status in cell " + id);
                }
                successfulCompletionTimes.add(Double.valueOf(number(metrics, "logicalTaskCompletionSeconds")));
            } else {
                incompleteRuns++;
            }
        }

        private JsonObject workflowCompletion() {
            JsonObject document = new JsonObject();
            document.addProperty("declaredRunCount", runs.size());
            document.addProperty("eligibleWorkflowRunCount", eligibleRuns);
            document.addProperty("successfullyCompletedWorkflowRunCount", successfulRuns);
            document.addProperty("incompleteWorkflowRunCount", incompleteRuns);
            document.addProperty("noLogicalTaskRunCount", noLogicalTaskRuns);
            document.addProperty("successfulWorkflowRunRate", workflowSuccessRate);
            document.add("wilsonConfidenceInterval", wilson(successfulRuns, eligibleRuns, design));
            return document;
        }

        private JsonObject conditionalCompletion() {
            JsonObject document = new JsonObject();
            document.addProperty("population", "SUCCESSFUL_WORKFLOW_RUNS_ONLY");
            document.addProperty("successfulWorkflowRunCount", successfulRuns);
            document.addProperty("completionTimeObservationCount", successfulCompletionTimes.size());
            document.addProperty("unavailableCompletionTimeCount", 0);
            document.add(
                    "summary", successfulCompletionTimes.isEmpty() ? null : metric(successfulCompletionTimes, design));
            document.addProperty(
                    "status",
                    successfulCompletionTimes.isEmpty()
                            ? "UNAVAILABLE_NO_SUCCESSFUL_WORKFLOW_RUNS"
                            : "AVAILABLE_SUCCESSFUL_WORKFLOW_RUNS_ONLY");
            return document;
        }
    }

    private static final class RunData {
        private final int replication;
        private final JsonObject metrics;

        RunData(int replication, JsonObject metrics) {
            this.replication = replication;
            this.metrics = metrics;
        }
    }

    private static final class RawComparison {
        private final JsonObject document;
        private final PairedWilcoxonSignificance paired;

        RawComparison(JsonObject document, PairedWilcoxonSignificance paired) {
            this.document = document;
            this.paired = paired;
        }
    }

    private static JsonObject metric(List<Double> samples, RandomizationDesign design) {
        JsonObject document = new JsonObject();
        JsonArray values = new JsonArray();
        double sum = 0.0;
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (Double value : samples) {
            values.add(value);
            sum += value.doubleValue();
            minimum = Math.min(minimum, value.doubleValue());
            maximum = Math.max(maximum, value.doubleValue());
        }
        double mean = sum / samples.size();
        double squaredDifferenceSum = 0.0;
        for (Double value : samples) {
            double difference = value.doubleValue() - mean;
            squaredDifferenceSum += difference * difference;
        }
        double deviation = samples.size() < 2 ? 0.0 : Math.sqrt(squaredDifferenceSum / (samples.size() - 1));
        document.add("samples", values);
        document.addProperty("sampleSize", samples.size());
        document.addProperty("mean", mean);
        document.addProperty("sampleStandardDeviation", deviation);
        document.addProperty("minimum", minimum);
        document.addProperty("maximum", maximum);
        document.add("meanConfidenceInterval", meanInterval(samples.size(), mean, deviation, design));
        return document;
    }

    private static JsonObject meanInterval(int count, double mean, double deviation, RandomizationDesign design) {
        if (design != RandomizationDesign.INDEPENDENT_REPLICATIONS) {
            return interval(
                    false, null, null, "UNAVAILABLE_RANDOMIZATION_DESIGN_IS_NOT_INDEPENDENT_REPLICATIONS", null);
        }
        if (count < 2) {
            return interval(false, null, null, "UNAVAILABLE_FEWER_THAN_TWO_REPLICATIONS", null);
        }
        double critical = new TDistribution(count - 1).inverseCumulativeProbability(0.5 + CONFIDENCE_LEVEL / 2.0);
        double margin = critical * deviation / Math.sqrt(count);
        return interval(
                true,
                Double.valueOf(mean - margin),
                Double.valueOf(mean + margin),
                "AVAILABLE_STUDENT_T_MEAN_INTERVAL",
                null);
    }

    private static JsonObject wilson(int successes, int observations, RandomizationDesign design) {
        if (observations == 0) {
            return interval(false, null, null, "UNAVAILABLE_NO_ELIGIBLE_WORKFLOW_RUNS", "WILSON_SCORE");
        }
        if (design != RandomizationDesign.INDEPENDENT_REPLICATIONS) {
            return interval(
                    false,
                    null,
                    null,
                    "UNAVAILABLE_RANDOMIZATION_DESIGN_IS_NOT_INDEPENDENT_REPLICATIONS",
                    "WILSON_SCORE");
        }
        double proportion = ((double) successes) / observations;
        double z = new NormalDistribution().inverseCumulativeProbability(0.5 + CONFIDENCE_LEVEL / 2.0);
        double zSquared = z * z;
        double denominator = 1.0 + zSquared / observations;
        double center = (proportion + zSquared / (2.0 * observations)) / denominator;
        double halfWidth = z
                * Math.sqrt((proportion * (1.0 - proportion) + zSquared / (4.0 * observations)) / observations)
                / denominator;
        return interval(
                true,
                Double.valueOf(Math.max(0.0, center - halfWidth)),
                Double.valueOf(Math.min(1.0, center + halfWidth)),
                "AVAILABLE_WILSON_SCORE_PROPORTION_INTERVAL",
                "WILSON_SCORE");
    }

    private static JsonObject interval(boolean available, Double lower, Double upper, String status, String method) {
        JsonObject document = new JsonObject();
        document.addProperty("available", available);
        document.addProperty("confidenceLevel", CONFIDENCE_LEVEL);
        document.addProperty("lower", lower);
        document.addProperty("upper", upper);
        document.addProperty("status", status);
        if (method != null) {
            document.addProperty("method", method);
        }
        return document;
    }

    private static Double ratio(double numerator, double denominator) {
        return denominator == 0.0 ? null : Double.valueOf(numerator / denominator);
    }

    private static Double difference(Double candidate, Double baseline) {
        return candidate == null || baseline == null ? null : Double.valueOf(candidate - baseline);
    }

    private static double mean(List<Double> values) {
        double sum = 0.0;
        for (Double value : values) {
            sum += value.doubleValue();
        }
        return sum / values.size();
    }

    private static JsonArray longArray(List<Long> values) {
        JsonArray result = new JsonArray();
        for (Long value : values) {
            result.add(value);
        }
        return result;
    }

    private static JsonObject requireObject(JsonObject parent, String key) throws IOException {
        return object(parent.get(key));
    }

    private static JsonObject object(JsonElement value) throws IOException {
        if (value == null || !value.isJsonObject()) {
            throw new IOException("Campaign summary verifier expected an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject parent, String key) throws IOException {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonArray()) {
            throw new IOException("Campaign summary verifier expected an array: " + key);
        }
        return value.getAsJsonArray();
    }

    private static String string(JsonObject parent, String key) throws IOException {
        JsonElement value = parent.get(key);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Campaign summary verifier expected a string: " + key);
        }
        return value.getAsString();
    }

    private static boolean bool(JsonObject parent, String key) throws IOException {
        JsonElement value = parent.get(key);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IOException("Campaign summary verifier expected a boolean: " + key);
        }
        return value.getAsBoolean();
    }

    private static int integer(JsonObject parent, String key) throws IOException {
        try {
            return parent.get(key).getAsBigDecimal().intValueExact();
        } catch (RuntimeException failure) {
            throw new IOException("Campaign summary verifier expected an integer: " + key, failure);
        }
    }

    private static long longInteger(JsonObject parent, String key) throws IOException {
        try {
            return parent.get(key).getAsBigDecimal().longValueExact();
        } catch (RuntimeException failure) {
            throw new IOException("Campaign summary verifier expected an integer: " + key, failure);
        }
    }

    private static double number(JsonObject parent, String key) throws IOException {
        try {
            double value = parent.get(key).getAsDouble();
            if (!Double.isFinite(value)) {
                throw new IOException("Campaign summary verifier expected a finite number: " + key);
            }
            return value;
        } catch (RuntimeException failure) {
            throw new IOException("Campaign summary verifier expected a number: " + key, failure);
        }
    }

    private static List<Long> longList(JsonObject parent, String key) throws IOException {
        List<Long> result = new ArrayList<Long>();
        for (JsonElement element : requireArray(parent, key)) {
            try {
                result.add(Long.valueOf(element.getAsBigDecimal().longValueExact()));
            } catch (RuntimeException failure) {
                throw new IOException("Campaign summary verifier expected an integer list: " + key, failure);
            }
        }
        return result;
    }
}
