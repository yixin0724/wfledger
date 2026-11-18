package org.workflowsim.experiments.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.experiment.HolmStepDownCorrection;

public final class NetworkStudySummary {
    private NetworkStudySummary() {}

    public static Map<String, Object> summarize(List<Map<String, Object>> runs) {
        boolean heterogeneityAxis = detectHeterogeneityAxis(runs);
        GroupedRuns grouped = groupCompletedRuns(runs, heterogeneityAxis);
        List<Map<String, Object>> aggregates = aggregateGroups(grouped.groups, heterogeneityAxis);
        List<Map<String, Object>> comparisons = buildComparisons(aggregates, heterogeneityAxis, grouped.failures);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("runCount", runs.size());
        result.put("failedRunCount", grouped.failures);
        result.put("aggregates", aggregates);
        result.put("comparisons", comparisons);
        result.put("status", grouped.failures == 0 ? "COMPLETE" : "INCOMPLETE_NO_INFERENCE");
        result.put(
                "seedInterpretation",
                "DETERMINISTIC_PLANNERS_ONCE;RANDOMIZED_PLANNERS_SEED_REPEATS;NOT_EVENT_KEYED_CRN");
        return result;
    }

    private static boolean detectHeterogeneityAxis(List<Map<String, Object>> runs) {

        boolean heterogeneityAxis = false;
        for (Map<String, Object> run : runs) {
            if (run.get("heterogeneity") != null) {
                heterogeneityAxis = true;
                break;
            }
        }
        return heterogeneityAxis;
    }

    private static GroupedRuns groupCompletedRuns(List<Map<String, Object>> runs, boolean heterogeneityAxis) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<String, List<Map<String, Object>>>();
        int failures = 0;
        for (Map<String, Object> run : runs) {
            if (!"COMPLETED_SUCCESSFULLY".equals(run.get("status"))) {
                failures++;
                continue;
            }
            String key = run.get("workflowId") + "/" + run.get("vmCount") + "/" + run.get("network")
                    + (heterogeneityAxis ? "/" + run.get("heterogeneity") : "") + "/" + run.get("planner");
            if (!groups.containsKey(key)) {
                groups.put(key, new ArrayList<Map<String, Object>>());
            }
            groups.get(key).add(run);
        }
        return new GroupedRuns(groups, failures);
    }

    private static List<Map<String, Object>> aggregateGroups(
            Map<String, List<Map<String, Object>>> groups, boolean heterogeneityAxis) {
        List<Map<String, Object>> aggregates = new ArrayList<Map<String, Object>>();
        List<String> aggregateFields = new ArrayList<String>(
                java.util.Arrays.asList("workflowId", "family", "population", "vmCount", "network"));
        if (heterogeneityAxis) {
            aggregateFields.add("heterogeneity");
        }
        aggregateFields.add("planner");
        for (List<Map<String, Object>> group : groups.values()) {
            Map<String, Object> first = group.get(0);
            List<Double> values = new ArrayList<Double>();
            for (Map<String, Object> run : group) {
                values.add(number(run, "makespanSeconds"));
            }
            Collections.sort(values);
            double sum = 0;
            for (double value : values) {
                sum += value;
            }
            Map<String, Object> aggregate = new LinkedHashMap<String, Object>();
            for (String field : aggregateFields) {
                aggregate.put(field, first.get(field));
            }
            aggregate.put("observedRuns", values.size());
            aggregate.put("meanSeconds", sum / values.size());
            aggregate.put("medianSeconds", median(values));
            aggregate.put("minimumSeconds", values.get(0));
            aggregate.put("maximumSeconds", values.get(values.size() - 1));
            aggregates.add(aggregate);
        }
        return aggregates;
    }

    private static List<Map<String, Object>> buildComparisons(
            List<Map<String, Object>> aggregates, boolean heterogeneityAxis, int failures) {
        List<Map<String, Object>> comparisons = new ArrayList<Map<String, Object>>();
        if (failures == 0) {
            Map<String, List<Map<String, Object>>> strata = new LinkedHashMap<String, List<Map<String, Object>>>();
            for (Map<String, Object> aggregate : aggregates) {
                String key = aggregate.get("population") + "/" + aggregate.get("vmCount") + "/"
                        + aggregate.get("network") + (heterogeneityAxis ? "/" + aggregate.get("heterogeneity") : "");
                if (!strata.containsKey(key)) {
                    strata.put(key, new ArrayList<Map<String, Object>>());
                }
                strata.get(key).add(aggregate);
            }
            for (List<Map<String, Object>> stratum : strata.values()) {
                List<Map<String, Object>> family = new ArrayList<Map<String, Object>>();
                List<String> candidates = new ArrayList<String>();
                for (Map<String, Object> aggregate : stratum) {
                    String planner = aggregate.get("planner").toString();
                    if (!"LOCAL_HEFT".equals(planner) && !candidates.contains(planner)) {
                        candidates.add(planner);
                    }
                }
                for (String candidate : candidates) {
                    compareCandidateAgainstBaseline(stratum, candidate, heterogeneityAxis, family);
                }
                applyHolm(family);
                comparisons.addAll(family);
            }
        }
        return comparisons;
    }

    private static void compareCandidateAgainstBaseline(
            List<Map<String, Object>> stratum,
            String candidate,
            boolean heterogeneityAxis,
            List<Map<String, Object>> family) {
        List<Double> effects = new ArrayList<Double>();
        int wins = 0, ties = 0, losses = 0;
        for (Map<String, Object> baseline : stratum) {
            if (!"LOCAL_HEFT".equals(baseline.get("planner"))) {
                continue;
            }
            Map<String, Object> other = find(stratum, baseline.get("workflowId").toString(), candidate);
            if (other == null) {
                continue;
            }
            double base = number(baseline, "meanSeconds");
            double value = number(other, "meanSeconds");
            effects.add((base - value) / base * 100.0);
            double tolerance = 1e-9 * Math.max(1.0, Math.max(base, value));
            if (value < base - tolerance) {
                wins++;
            } else if (value > base + tolerance) {
                losses++;
            } else {
                ties++;
            }
        }
        if (effects.isEmpty()) {
            return;
        }
        Collections.sort(effects);
        Map<String, Object> comparison = new LinkedHashMap<String, Object>();
        for (String field : new String[] {"population", "vmCount", "network"}) {
            comparison.put(field, stratum.get(0).get(field));
        }
        if (heterogeneityAxis) {
            comparison.put("heterogeneity", stratum.get(0).get("heterogeneity"));
        }
        comparison.put("baseline", "LOCAL_HEFT");
        comparison.put("candidate", candidate);
        comparison.put("dagPairs", effects.size());
        comparison.put("wins", wins);
        comparison.put("ties", ties);
        comparison.put("losses", losses);
        comparison.put("medianImprovementPercent", median(effects));
        comparison.put("pValue", signTest(wins, losses));
        comparison.put("test", "EXACT_TWO_SIDED_SIGN_TEST_ON_DAG_SEED_MEANS");
        comparison.put("inferenceScope", "EXPLORATORY_SELECTED_CORPUS_NOT_RANDOM_WORKLOAD_POPULATION");
        family.add(comparison);
    }

    private static final class GroupedRuns {
        final Map<String, List<Map<String, Object>>> groups;
        final int failures;

        GroupedRuns(Map<String, List<Map<String, Object>>> groups, int failures) {
            this.groups = groups;
            this.failures = failures;
        }
    }

    static Double signTest(int wins, int losses) {
        int n = wins + losses;
        if (n == 0) {
            return null;
        }
        int tail = Math.min(wins, losses);
        double term = Math.pow(0.5, n), sum = term;
        for (int i = 1; i <= tail; i++) {
            term *= (double) (n - i + 1) / i;
            sum += term;
        }
        return Math.min(1.0, 2.0 * sum);
    }

    static void applyHolm(List<Map<String, Object>> family) {

        List<Double> rawPValues = new ArrayList<Double>();
        for (Map<String, Object> record : family) {
            record.put("holmAdjustedPValue", null);
            record.put("significantAfterHolm", false);
            Object pValue = record.get("pValue");
            rawPValues.add(pValue == null ? null : Double.valueOf(((Number) pValue).doubleValue()));
        }
        List<HolmStepDownCorrection.Adjusted> adjusted = HolmStepDownCorrection.adjustWithinFamily(rawPValues);
        for (int index = 0; index < family.size(); index++) {
            if (rawPValues.get(index) != null) {
                family.get(index).put("holmAdjustedPValue", adjusted.get(index).getAdjustedPValue());
                family.get(index)
                        .put(
                                "significantAfterHolm",
                                Boolean.valueOf(adjusted.get(index).isSignificantAfterHolm()));
            }
        }
    }

    private static Map<String, Object> find(List<Map<String, Object>> values, String workflow, String planner) {
        for (Map<String, Object> value : values) {
            if (workflow.equals(value.get("workflowId")) && planner.equals(value.get("planner"))) {
                return value;
            }
        }
        return null;
    }

    private static double number(Map<String, Object> value, String key) {
        return ((Number) value.get(key)).doubleValue();
    }

    private static double median(List<Double> sorted) {
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
