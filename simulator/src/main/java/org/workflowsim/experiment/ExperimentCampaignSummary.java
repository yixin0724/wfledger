package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.TDistribution;

/**
 * Descriptive statistics summary for one campaign, with the admissible statistical
 * interpretation stated explicitly.
 */
public final class ExperimentCampaignSummary {

    private static final double CONFIDENCE_LEVEL = 0.95;

    private final List<CellSummary> cells;
    private final List<ComparisonSummary> comparisons;

    private ExperimentCampaignSummary(List<CellSummary> cells, List<ComparisonSummary> comparisons) {
        this.cells = Collections.unmodifiableList(new ArrayList<CellSummary>(cells));
        this.comparisons = Collections.unmodifiableList(new ArrayList<ComparisonSummary>(comparisons));
    }

    static ExperimentCampaignSummary calculate(ExperimentPlan plan, List<ExperimentCampaignResult.Run> runs) {
        Map<String, List<ExperimentCampaignResult.Run>> runsByCell =
                new LinkedHashMap<String, List<ExperimentCampaignResult.Run>>();
        for (ExperimentPlan.Cell cell : plan.getCells()) {
            runsByCell.put(cell.getId(), new ArrayList<ExperimentCampaignResult.Run>());
        }
        for (ExperimentCampaignResult.Run run : runs) {
            List<ExperimentCampaignResult.Run> cellRuns =
                    runsByCell.get(run.getCell().getId());
            if (cellRuns == null) {
                throw new IllegalArgumentException("Campaign result contains a run not declared by its plan");
            }
            cellRuns.add(run);
        }

        List<CellSummary> cellSummaries = new ArrayList<CellSummary>();
        Map<String, CellSummary> baselines = new HashMap<String, CellSummary>();
        Map<String, List<CellSummary>> candidatesByGroup = new LinkedHashMap<String, List<CellSummary>>();
        for (ExperimentPlan.Cell cell : plan.getCells()) {
            List<ExperimentCampaignResult.Run> cellRuns = runsByCell.get(cell.getId());
            if (cellRuns.size() != cell.getSeedPlan().getReplicationCount()) {
                throw new IllegalArgumentException("Campaign result count differs from seed plan for " + cell.getId());
            }
            CellSummary summary = CellSummary.from(cell, cellRuns);
            cellSummaries.add(summary);
            List<CellSummary> grouped = candidatesByGroup.get(cell.getComparisonGroup());
            if (grouped == null) {
                grouped = new ArrayList<CellSummary>();
                candidatesByGroup.put(cell.getComparisonGroup(), grouped);
            }
            grouped.add(summary);
            if (cell.isBaseline()) {
                baselines.put(cell.getComparisonGroup(), summary);
            }
        }

        List<ComparisonSummary> comparisonSummaries = new ArrayList<ComparisonSummary>();
        for (Map.Entry<String, List<CellSummary>> entry : candidatesByGroup.entrySet()) {
            CellSummary baseline = baselines.get(entry.getKey());
            // (AUD-F06, 2026-11-03) All non-baseline candidates of one comparison
            // group form one Holm family, including comparisons whose paired test
            // produced no p value; the declared family size is the correction
            // multiplier base.
            List<ComparisonSummary> family = new ArrayList<ComparisonSummary>();
            List<Double> rawPValues = new ArrayList<Double>();
            for (CellSummary candidate : entry.getValue()) {
                if (!candidate.isBaseline()) {
                    ComparisonSummary raw = ComparisonSummary.from(baseline, candidate);
                    family.add(raw);
                    rawPValues.add(raw.getPairedSignificance().getPValue());
                }
            }
            List<HolmStepDownCorrection.Adjusted> adjusted = HolmStepDownCorrection.adjustWithinFamily(rawPValues);
            for (int index = 0; index < family.size(); index++) {
                comparisonSummaries.add(ComparisonSummary.withHolm(
                        family.get(index),
                        adjusted.get(index).getAdjustedPValue(),
                        adjusted.get(index).isSignificantAfterHolm()));
            }
        }
        return new ExperimentCampaignSummary(cellSummaries, comparisonSummaries);
    }

    /**
     * Return the descriptive summary of each declared cell.
     *
     * @return immutable cell summary list in plan declaration order
     */
    public List<CellSummary> getCells() {
        return cells;
    }
    /**
     * Return the comparison summary of each non-baseline candidate against its
     * declared baseline.
     *
     * @return immutable comparison summary list
     */
    public List<ComparisonSummary> getComparisons() {
        return comparisons;
    }

    /** Aggregated observations for one declared cell. */
    public static final class CellSummary {
        private final String cellId;
        private final String comparisonGroup;
        private final String candidateId;
        private final boolean baseline;
        private final RandomizationDesign randomizationDesign;
        private final List<Long> rootSeeds;
        private final MetricSummary makespanSeconds;
        private final MetricSummary modeledProcessingCost;
        private final MetricSummary successfulLogicalTaskCompletionRate;
        /**
         * PLAT-10: replication-level aggregation of the core performance metrics
         * (waiting, slowdown ratio, utilization, fairness).
         */
        private final MetricSummary meanComputeTotalWaitingTimeSeconds;
        /**
         * Replication-level aggregation of the true slowdown ratio.
         *
         * <p>Slowdown ratio semantics are always >= 1.0, so the 0.0 of a run without
         * observations is an illegal sample; therefore only runs with
         * {@code trueSlowdownObservationCount > 0} are aggregated, and this field is
         * {@code null} when no run has an observation (rather than polluting the mean
         * and confidence interval with 0.0).</p>
         */
        private final MetricSummary meanComputeTrueSlowdown;
        /**
         * Number of runs that contributed a true slowdown sample (the remaining runs
         * have no observation and are excluded from the aggregation).
         */
        private final int trueSlowdownObservationRunCount;

        private final MetricSummary meanVmModeledIntervalUtilization;
        private final MetricSummary vmUtilizationJainFairnessIndex;
        private final WorkflowRunCompletionSummary workflowRunCompletion;
        private final ConditionalMetricSummary successfulWorkflowLogicalCompletionSeconds;

        private CellSummary(
                String cellId,
                String comparisonGroup,
                String candidateId,
                boolean baseline,
                RandomizationDesign randomizationDesign,
                List<Long> rootSeeds,
                MetricSummary makespanSeconds,
                MetricSummary modeledProcessingCost,
                MetricSummary successfulLogicalTaskCompletionRate,
                MetricSummary meanComputeTotalWaitingTimeSeconds,
                MetricSummary meanComputeTrueSlowdown,
                int trueSlowdownObservationRunCount,
                MetricSummary meanVmModeledIntervalUtilization,
                MetricSummary vmUtilizationJainFairnessIndex,
                WorkflowRunCompletionSummary workflowRunCompletion,
                ConditionalMetricSummary successfulWorkflowLogicalCompletionSeconds) {
            this.cellId = cellId;
            this.comparisonGroup = comparisonGroup;
            this.candidateId = candidateId;
            this.baseline = baseline;
            this.randomizationDesign = randomizationDesign;
            this.rootSeeds = Collections.unmodifiableList(new ArrayList<Long>(rootSeeds));
            this.makespanSeconds = makespanSeconds;
            this.modeledProcessingCost = modeledProcessingCost;
            this.successfulLogicalTaskCompletionRate = successfulLogicalTaskCompletionRate;
            this.meanComputeTotalWaitingTimeSeconds = meanComputeTotalWaitingTimeSeconds;
            this.meanComputeTrueSlowdown = meanComputeTrueSlowdown;
            this.trueSlowdownObservationRunCount = trueSlowdownObservationRunCount;
            this.meanVmModeledIntervalUtilization = meanVmModeledIntervalUtilization;
            this.vmUtilizationJainFairnessIndex = vmUtilizationJainFairnessIndex;
            this.workflowRunCompletion = workflowRunCompletion;
            this.successfulWorkflowLogicalCompletionSeconds = successfulWorkflowLogicalCompletionSeconds;
        }

        private static CellSummary from(ExperimentPlan.Cell cell, List<ExperimentCampaignResult.Run> runs) {
            RunSampleAccumulator samples = accumulateRuns(cell, runs);
            List<Long> seeds = samples.seeds;
            List<Double> makespans = samples.makespans;
            List<Double> costs = samples.costs;
            List<Double> meanTrueSlowdowns = samples.meanTrueSlowdowns;
            List<Double> meanUtilizations = samples.meanUtilizations;
            List<Double> jainIndexes = samples.jainIndexes;
            WorkflowRunCompletionSummary workflowRunCompletion = WorkflowRunCompletionSummary.from(
                    runs.size(),
                    samples.eligibleWorkflowRuns,
                    samples.successfullyCompletedWorkflowRuns,
                    samples.incompleteWorkflowRuns,
                    samples.noLogicalTaskRuns,
                    cell.getSeedPlan().getRandomizationDesign());
            ConditionalMetricSummary successfulWorkflowCompletion = ConditionalMetricSummary.from(
                    samples.successfullyCompletedWorkflowRuns,
                    samples.successfulWorkflowCompletionTimes,
                    0,
                    cell.getSeedPlan().getRandomizationDesign());
            return new CellSummary(
                    cell.getId(),
                    cell.getComparisonGroup(),
                    cell.getCandidateId(),
                    cell.isBaseline(),
                    cell.getSeedPlan().getRandomizationDesign(),
                    seeds,
                    MetricSummary.from(makespans, cell.getSeedPlan().getRandomizationDesign()),
                    MetricSummary.from(costs, cell.getSeedPlan().getRandomizationDesign()),
                    MetricSummary.from(
                            samples.completionRates, cell.getSeedPlan().getRandomizationDesign()),
                    MetricSummary.from(
                            samples.meanWaitingTimes, cell.getSeedPlan().getRandomizationDesign()),
                    meanTrueSlowdowns.isEmpty()
                            ? null
                            : MetricSummary.from(
                                    meanTrueSlowdowns, cell.getSeedPlan().getRandomizationDesign()),
                    meanTrueSlowdowns.size(),
                    MetricSummary.from(meanUtilizations, cell.getSeedPlan().getRandomizationDesign()),
                    MetricSummary.from(jainIndexes, cell.getSeedPlan().getRandomizationDesign()),
                    workflowRunCompletion,
                    successfulWorkflowCompletion);
        }

        /**
         * Run-sample aggregation stage of from() (verbatim extraction of the PLAT-10
         * illegal-sample exclusion semantics for runs without observations).
         */
        private static RunSampleAccumulator accumulateRuns(
                ExperimentPlan.Cell cell, List<ExperimentCampaignResult.Run> runs) {
            RunSampleAccumulator samples = new RunSampleAccumulator();
            samples.seeds = new ArrayList<Long>();
            samples.makespans = new ArrayList<Double>();
            samples.costs = new ArrayList<Double>();
            samples.completionRates = new ArrayList<Double>();
            samples.meanWaitingTimes = new ArrayList<Double>();
            samples.meanTrueSlowdowns = new ArrayList<Double>();
            samples.meanUtilizations = new ArrayList<Double>();
            samples.jainIndexes = new ArrayList<Double>();
            samples.successfulWorkflowCompletionTimes = new ArrayList<Double>();
            for (ExperimentCampaignResult.Run run : runs) {
                if (run.getCell() != cell) {
                    throw new IllegalArgumentException("Campaign result cell identity is inconsistent");
                }
                samples.seeds.add(Long.valueOf(run.getSeed()));
                samples.makespans.add(Double.valueOf(run.getReport().getMakespan()));
                samples.costs.add(Double.valueOf(run.getReport().getMetrics().getTotalModeledProcessingCost()));
                samples.completionRates.add(
                        Double.valueOf(run.getReport().getMetrics().getSuccessfulLogicalTaskCompletionRate()));
                SimulationMetrics metrics = run.getReport().getMetrics();
                // PLAT-10: core performance metrics enter replication-level aggregation, so a
                // campaign comparison no longer has only makespan/cost/completion rate. The
                // 0.0/1.0 of waiting/utilization/fairness without observations are legal
                // values (same definition as a single run); but true slowdown semantics are
                // always >= 1.0, so the 0.0 of a run without observations is an illegal
                // sample that must be excluded rather than filled, otherwise it
                // systematically lowers the replication mean and pollutes the confidence
                // interval.
                samples.meanWaitingTimes.add(Double.valueOf(metrics.getMeanComputeTotalWaitingTimeSeconds()));
                if (metrics.getTrueSlowdownObservationCount() > 0) {
                    samples.meanTrueSlowdowns.add(Double.valueOf(metrics.getMeanComputeTrueSlowdown()));
                }
                samples.meanUtilizations.add(Double.valueOf(metrics.getMeanVmModeledIntervalUtilization()));
                samples.jainIndexes.add(Double.valueOf(metrics.getVmUtilizationJainFairnessIndex()));
                if (metrics.getLogicalTaskCount() == 0) {
                    samples.noLogicalTaskRuns++;
                    continue;
                }
                samples.eligibleWorkflowRuns++;
                if (metrics.isWorkflowCompletedSuccessfully()) {
                    samples.successfullyCompletedWorkflowRuns++;
                    Double completion = run.getReport().getLogicalTaskCompletionSeconds();
                    if (completion == null
                            || !"COMPLETED_SUCCESSFULLY".equals(run.getReport().getLogicalTaskCompletionStatus())) {
                        throw new IllegalStateException("Successful workflow run has no logical "
                                + "completion-time evidence: " + cell.getId() + "/"
                                + run.getReplicationIndex());
                    }
                    samples.successfulWorkflowCompletionTimes.add(completion);
                } else {
                    samples.incompleteWorkflowRuns++;
                }
            }
            return samples;
        }

        /**
         * Private verbatim-extraction structure for from() sample aggregation (direct
         * field reads and writes, no forwarding behavior).
         */
        private static final class RunSampleAccumulator {
            private List<Long> seeds;
            private List<Double> makespans;
            private List<Double> costs;
            private List<Double> completionRates;
            private List<Double> meanWaitingTimes;
            private List<Double> meanTrueSlowdowns;
            private List<Double> meanUtilizations;
            private List<Double> jainIndexes;
            private List<Double> successfulWorkflowCompletionTimes;
            private int eligibleWorkflowRuns;
            private int successfullyCompletedWorkflowRuns;
            private int incompleteWorkflowRuns;
            private int noLogicalTaskRuns;
        }

        public String getCellId() {
            return cellId;
        }

        public String getComparisonGroup() {
            return comparisonGroup;
        }

        public String getCandidateId() {
            return candidateId;
        }

        public boolean isBaseline() {
            return baseline;
        }

        public RandomizationDesign getRandomizationDesign() {
            return randomizationDesign;
        }

        public List<Long> getRootSeeds() {
            return rootSeeds;
        }

        public MetricSummary getMakespanSeconds() {
            return makespanSeconds;
        }

        public MetricSummary getModeledProcessingCost() {
            return modeledProcessingCost;
        }

        public MetricSummary getSuccessfulLogicalTaskCompletionRate() {
            return successfulLogicalTaskCompletionRate;
        }
        /** @return replication-level summary of the per-run mean total waiting time in seconds */
        public MetricSummary getMeanComputeTotalWaitingTimeSeconds() {
            return meanComputeTotalWaitingTimeSeconds;
        }
        /**
         * Return the replication-level summary of the per-run mean true slowdown ratio.
         *
         * <p>Only runs with {@code trueSlowdownObservationCount > 0} are aggregated
         * (slowdown ratio semantics are always >= 1.0, so the 0.0 of a run without
         * observations must not be mixed in). Returns {@code null} when no run in the
         * cell has an observation; callers should use
         * {@link #getTrueSlowdownObservationRunCount()} to determine availability.</p>
         *
         * @return true slowdown replication summary; {@code null} when no observation is available
         */
        public MetricSummary getMeanComputeTrueSlowdown() {
            return meanComputeTrueSlowdown;
        }
        /**
         * @return number of runs in the cell that contributed a true slowdown sample
         *         ({@code 0..replicationCount})
         */
        public int getTrueSlowdownObservationRunCount() {
            return trueSlowdownObservationRunCount;
        }
        /** @return replication-level summary of the per-run mean VM utilization */
        public MetricSummary getMeanVmModeledIntervalUtilization() {
            return meanVmModeledIntervalUtilization;
        }
        /** @return replication-level summary of the per-run VM utilization Jain fairness index */
        public MetricSummary getVmUtilizationJainFairnessIndex() {
            return vmUtilizationJainFairnessIndex;
        }
        /**
         * Return the replication-level complete workflow success rate and the proportion
         * interval that applies to it.
         *
         * <p>It differs from the task-level completion proportion of
         * {@link #getSuccessfulLogicalTaskCompletionRate()}.</p>
         *
         * @return workflow-run completion summary
         */
        public WorkflowRunCompletionSummary getWorkflowRunCompletion() {
            return workflowRunCompletion;
        }
        /**
         * Return the logical completion-time summary conditioned on fully successful
         * workflows only.
         *
         * @return successful-workflow conditional completion-time summary
         */
        public ConditionalMetricSummary getSuccessfulWorkflowLogicalCompletionSeconds() {
            return successfulWorkflowLogicalCompletionSeconds;
        }
    }

    /**
     * Makespan comparison of the candidate minus the baseline.
     *
     * <p>The Wilcoxon signed-rank test on the paired series
     * ({@link #getPairedSignificance()}) provides a conservative
     * distribution-difference verdict; because random sampling is not yet event-keyed,
     * that test result does not constitute a CRN paired-inference claim (see the
     * inference-boundary statement of {@link PairedWilcoxonSignificance}).</p>
     */
    public static final class ComparisonSummary {
        private final String comparisonGroup;
        private final String baselineCellId;
        private final String candidateCellId;
        private final int matchedRootSeedCount;
        private final Double meanMatchedMakespanDeltaSeconds;
        private final double baselineMeanMakespanSeconds;
        private final double candidateMeanMakespanSeconds;
        private final Double meanMakespanRatio;
        private final Double baselineRelativeMakespanSpeedup;
        private final Double makespanImprovementPercent;
        private final double baselineMeanModeledProcessingCost;
        private final double candidateMeanModeledProcessingCost;
        private final Double meanModeledProcessingCostRatio;
        private final double successfulLogicalTaskCompletionRateDelta;
        private final Double successfulWorkflowRunRateDelta;
        private final String inferenceStatus;
        private final PairedWilcoxonSignificance pairedSignificance;
        private final Double holmAdjustedPValue;
        private final boolean significantAfterHolmAtDefaultAlpha;

        private ComparisonSummary(
                String comparisonGroup,
                String baselineCellId,
                String candidateCellId,
                int matchedRootSeedCount,
                Double meanMatchedMakespanDeltaSeconds,
                double baselineMeanMakespanSeconds,
                double candidateMeanMakespanSeconds,
                Double meanMakespanRatio,
                Double baselineRelativeMakespanSpeedup,
                Double makespanImprovementPercent,
                double baselineMeanModeledProcessingCost,
                double candidateMeanModeledProcessingCost,
                Double meanModeledProcessingCostRatio,
                double successfulLogicalTaskCompletionRateDelta,
                Double successfulWorkflowRunRateDelta,
                String inferenceStatus,
                PairedWilcoxonSignificance pairedSignificance,
                Double holmAdjustedPValue,
                boolean significantAfterHolmAtDefaultAlpha) {
            this.comparisonGroup = comparisonGroup;
            this.baselineCellId = baselineCellId;
            this.candidateCellId = candidateCellId;
            this.matchedRootSeedCount = matchedRootSeedCount;
            this.meanMatchedMakespanDeltaSeconds = meanMatchedMakespanDeltaSeconds;
            this.baselineMeanMakespanSeconds = baselineMeanMakespanSeconds;
            this.candidateMeanMakespanSeconds = candidateMeanMakespanSeconds;
            this.meanMakespanRatio = meanMakespanRatio;
            this.baselineRelativeMakespanSpeedup = baselineRelativeMakespanSpeedup;
            this.makespanImprovementPercent = makespanImprovementPercent;
            this.baselineMeanModeledProcessingCost = baselineMeanModeledProcessingCost;
            this.candidateMeanModeledProcessingCost = candidateMeanModeledProcessingCost;
            this.meanModeledProcessingCostRatio = meanModeledProcessingCostRatio;
            this.successfulLogicalTaskCompletionRateDelta = successfulLogicalTaskCompletionRateDelta;
            this.successfulWorkflowRunRateDelta = successfulWorkflowRunRateDelta;
            this.inferenceStatus = inferenceStatus;
            this.pairedSignificance = pairedSignificance;
            this.holmAdjustedPValue = holmAdjustedPValue;
            this.significantAfterHolmAtDefaultAlpha = significantAfterHolmAtDefaultAlpha;
        }

        private static ComparisonSummary from(CellSummary baseline, CellSummary candidate) {
            double baselineMakespan = baseline.getMakespanSeconds().getMean();
            double candidateMakespan = candidate.getMakespanSeconds().getMean();
            double baselineCost = baseline.getModeledProcessingCost().getMean();
            double candidateCost = candidate.getModeledProcessingCost().getMean();
            Double makespanRatio = ratio(candidateMakespan, baselineMakespan);
            Double speedup = ratio(baselineMakespan, candidateMakespan);
            Double improvementPercent = baselineMakespan == 0.0
                    ? null
                    : Double.valueOf(100.0 * (baselineMakespan - candidateMakespan) / baselineMakespan);
            Double costRatio = ratio(candidateCost, baselineCost);
            double completionRateDelta =
                    candidate.getSuccessfulLogicalTaskCompletionRate().getMean()
                            - baseline.getSuccessfulLogicalTaskCompletionRate().getMean();
            Double workflowSuccessRateDelta = difference(
                    candidate.getWorkflowRunCompletion().getSuccessfulWorkflowRunRate(),
                    baseline.getWorkflowRunCompletion().getSuccessfulWorkflowRunRate());
            if (baseline.getRandomizationDesign() == RandomizationDesign.INDEPENDENT_REPLICATIONS) {
                return new ComparisonSummary(
                        baseline.getComparisonGroup(),
                        baseline.getCellId(),
                        candidate.getCellId(),
                        0,
                        null,
                        baselineMakespan,
                        candidateMakespan,
                        makespanRatio,
                        speedup,
                        improvementPercent,
                        baselineCost,
                        candidateCost,
                        costRatio,
                        completionRateDelta,
                        workflowSuccessRateDelta,
                        "UNPAIRED_INDEPENDENT_REPLICATIONS",
                        PairedWilcoxonSignificance.unavailableUnpaired(),
                        null,
                        false);
            }
            Map<Long, Double> baselineBySeed = valuesBySeed(baseline);
            Map<Long, Double> candidateBySeed = valuesBySeed(candidate);
            List<Double> deltas = new ArrayList<Double>();
            List<Double> matchedBaseline = new ArrayList<Double>();
            List<Double> matchedCandidate = new ArrayList<Double>();
            for (Map.Entry<Long, Double> entry : candidateBySeed.entrySet()) {
                Double reference = baselineBySeed.get(entry.getKey());
                if (reference != null) {
                    deltas.add(Double.valueOf(entry.getValue().doubleValue() - reference.doubleValue()));
                    matchedBaseline.add(reference);
                    matchedCandidate.add(entry.getValue());
                }
            }
            Double meanDelta = deltas.isEmpty() ? null : Double.valueOf(mean(deltas));
            String status = baseline.getRandomizationDesign() == RandomizationDesign.DETERMINISTIC
                    ? "DESCRIPTIVE_ONLY_DETERMINISTIC"
                    : "DESCRIPTIVE_ONLY_COMMON_ROOT_SEEDS_ARE_NOT_EVENT_KEYED_CRN";
            return new ComparisonSummary(
                    baseline.getComparisonGroup(),
                    baseline.getCellId(),
                    candidate.getCellId(),
                    deltas.size(),
                    meanDelta,
                    baselineMakespan,
                    candidateMakespan,
                    makespanRatio,
                    speedup,
                    improvementPercent,
                    baselineCost,
                    candidateCost,
                    costRatio,
                    completionRateDelta,
                    workflowSuccessRateDelta,
                    status,
                    PairedWilcoxonSignificance.evaluate(matchedBaseline, matchedCandidate),
                    null,
                    false);
        }

        /**
         * Copy of {@code raw} carrying the within-family Holm correction outcome;
         * the raw comparison itself stays immutable. (AUD-F06, 2026-11-03)
         */
        static ComparisonSummary withHolm(
                ComparisonSummary raw, Double holmAdjustedPValue, boolean significantAfterHolmAtDefaultAlpha) {
            return new ComparisonSummary(
                    raw.comparisonGroup,
                    raw.baselineCellId,
                    raw.candidateCellId,
                    raw.matchedRootSeedCount,
                    raw.meanMatchedMakespanDeltaSeconds,
                    raw.baselineMeanMakespanSeconds,
                    raw.candidateMeanMakespanSeconds,
                    raw.meanMakespanRatio,
                    raw.baselineRelativeMakespanSpeedup,
                    raw.makespanImprovementPercent,
                    raw.baselineMeanModeledProcessingCost,
                    raw.candidateMeanModeledProcessingCost,
                    raw.meanModeledProcessingCostRatio,
                    raw.successfulLogicalTaskCompletionRateDelta,
                    raw.successfulWorkflowRunRateDelta,
                    raw.inferenceStatus,
                    raw.pairedSignificance,
                    holmAdjustedPValue,
                    significantAfterHolmAtDefaultAlpha);
        }

        private static Double ratio(double numerator, double denominator) {
            return denominator == 0.0 ? null : Double.valueOf(numerator / denominator);
        }

        private static Double difference(Double candidate, Double baseline) {
            return candidate == null || baseline == null
                    ? null
                    : Double.valueOf(candidate.doubleValue() - baseline.doubleValue());
        }

        private static Map<Long, Double> valuesBySeed(CellSummary summary) {
            Map<Long, Double> values = new HashMap<Long, Double>();
            List<Long> seeds = summary.getRootSeeds();
            List<Double> makespans = summary.getMakespanSeconds().getSamples();
            for (int index = 0; index < seeds.size(); index++) {
                values.put(seeds.get(index), makespans.get(index));
            }
            return values;
        }

        public String getComparisonGroup() {
            return comparisonGroup;
        }

        public String getBaselineCellId() {
            return baselineCellId;
        }

        public String getCandidateCellId() {
            return candidateCellId;
        }

        public int getMatchedRootSeedCount() {
            return matchedRootSeedCount;
        }

        public Double getMeanMatchedMakespanDeltaSeconds() {
            return meanMatchedMakespanDeltaSeconds;
        }

        public double getBaselineMeanMakespanSeconds() {
            return baselineMeanMakespanSeconds;
        }

        public double getCandidateMeanMakespanSeconds() {
            return candidateMeanMakespanSeconds;
        }
        /**
         * Candidate mean makespan divided by baseline mean makespan; returns {@code null}
         * when the baseline is zero.
         */
        public Double getMeanMakespanRatio() {
            return meanMakespanRatio;
        }
        /**
         * Baseline mean makespan divided by candidate mean makespan; returns {@code null}
         * when the candidate is zero. This value is not a serial speedup.
         */
        public Double getBaselineRelativeMakespanSpeedup() {
            return baselineRelativeMakespanSpeedup;
        }
        /**
         * Makespan reduction percentage relative to the declared baseline; returns
         * {@code null} when baseline makespan is zero. This value is descriptive only and
         * is not an effect-size estimate.
         */
        public Double getMakespanImprovementPercent() {
            return makespanImprovementPercent;
        }

        public double getBaselineMeanModeledProcessingCost() {
            return baselineMeanModeledProcessingCost;
        }

        public double getCandidateMeanModeledProcessingCost() {
            return candidateMeanModeledProcessingCost;
        }
        /**
         * Candidate mean modeled cost divided by baseline mean cost; returns {@code null}
         * when the baseline is zero.
         */
        public Double getMeanModeledProcessingCostRatio() {
            return meanModeledProcessingCostRatio;
        }

        public double getSuccessfulLogicalTaskCompletionRateDelta() {
            return successfulLogicalTaskCompletionRateDelta;
        }
        /**
         * Return the candidate minus baseline workflow-run full success rate.
         *
         * <p>This is a descriptive difference; it carries no significance or interval
         * inference for the difference between the two proportions.</p>
         *
         * @return the difference when both cells have a definable workflow-run success
         *         rate; otherwise {@code null}
         */
        public Double getSuccessfulWorkflowRunRateDelta() {
            return successfulWorkflowRunRateDelta;
        }

        public String getInferenceStatus() {
            return inferenceStatus;
        }
        /**
         * @return Wilcoxon signed-rank significance test result on the paired makespan
         * series; the status is {@code UNAVAILABLE_INDEPENDENT_REPLICATIONS_ARE_NOT_PAIRED}
         * for the independent-replication design
         */
        public PairedWilcoxonSignificance getPairedSignificance() {
            return pairedSignificance;
        }
        /**
         * Holm step-down adjusted p value within this comparison's candidate
         * family; the family is every non-baseline candidate of the same
         * comparison group, including comparisons whose paired test produced no
         * p value, which still consume correction budget under the platform's
         * registered conservative convention. Multiplicity-aware consumers must
         * read this field (or {@link #isSignificantAfterHolmAtDefaultAlpha()})
         * instead of the raw paired test result.
         *
         * @return the adjusted p value, or {@code null} when the paired test
         *         produced no p value
         */
        public Double getHolmAdjustedPValue() {
            return holmAdjustedPValue;
        }
        /**
         * @return whether the Holm adjusted p value is strictly below
         *         {@link PairedWilcoxonSignificance#DEFAULT_ALPHA}; always
         *         {@code false} when no adjusted value exists
         */
        public boolean isSignificantAfterHolmAtDefaultAlpha() {
            return significantAfterHolmAtDefaultAlpha;
        }
    }

    /**
     * Replication-level summary of complete logical workflow completion.
     *
     * <p>It is a different estimand from the logical Task completion proportion inside a
     * single run: one success here requires that every logical Task of that run was
     * completed by a successful compute Job.</p>
     */
    public static final class WorkflowRunCompletionSummary {
        private final int declaredRunCount;
        private final int eligibleWorkflowRunCount;
        private final int successfullyCompletedWorkflowRunCount;
        private final int incompleteWorkflowRunCount;
        private final int noLogicalTaskRunCount;
        private final Double successfulWorkflowRunRate;
        private final ProportionConfidenceInterval wilsonConfidenceInterval;

        private WorkflowRunCompletionSummary(
                int declaredRunCount,
                int eligibleWorkflowRunCount,
                int successfullyCompletedWorkflowRunCount,
                int incompleteWorkflowRunCount,
                int noLogicalTaskRunCount,
                Double successfulWorkflowRunRate,
                ProportionConfidenceInterval wilsonConfidenceInterval) {
            this.declaredRunCount = declaredRunCount;
            this.eligibleWorkflowRunCount = eligibleWorkflowRunCount;
            this.successfullyCompletedWorkflowRunCount = successfullyCompletedWorkflowRunCount;
            this.incompleteWorkflowRunCount = incompleteWorkflowRunCount;
            this.noLogicalTaskRunCount = noLogicalTaskRunCount;
            this.successfulWorkflowRunRate = successfulWorkflowRunRate;
            this.wilsonConfidenceInterval = wilsonConfidenceInterval;
        }

        static WorkflowRunCompletionSummary from(
                int declaredRunCount,
                int eligibleWorkflowRunCount,
                int successfullyCompletedWorkflowRunCount,
                int incompleteWorkflowRunCount,
                int noLogicalTaskRunCount,
                RandomizationDesign design) {
            if (declaredRunCount < 0
                    || eligibleWorkflowRunCount < 0
                    || successfullyCompletedWorkflowRunCount < 0
                    || incompleteWorkflowRunCount < 0
                    || noLogicalTaskRunCount < 0
                    || declaredRunCount != eligibleWorkflowRunCount + noLogicalTaskRunCount
                    || eligibleWorkflowRunCount != successfullyCompletedWorkflowRunCount + incompleteWorkflowRunCount) {
                throw new IllegalArgumentException("Workflow-run completion counts are inconsistent");
            }
            Double rate = eligibleWorkflowRunCount == 0
                    ? null
                    : Double.valueOf(((double) successfullyCompletedWorkflowRunCount) / eligibleWorkflowRunCount);
            return new WorkflowRunCompletionSummary(
                    declaredRunCount,
                    eligibleWorkflowRunCount,
                    successfullyCompletedWorkflowRunCount,
                    incompleteWorkflowRunCount,
                    noLogicalTaskRunCount,
                    rate,
                    ProportionConfidenceInterval.forWilson(
                            successfullyCompletedWorkflowRunCount, eligibleWorkflowRunCount, design));
        }

        public int getDeclaredRunCount() {
            return declaredRunCount;
        }
        /**
         * @return number of runs with at least one logical Task, whose success or
         *         incomplete outcome is definable
         */
        public int getEligibleWorkflowRunCount() {
            return eligibleWorkflowRunCount;
        }

        public int getSuccessfullyCompletedWorkflowRunCount() {
            return successfullyCompletedWorkflowRunCount;
        }

        public int getIncompleteWorkflowRunCount() {
            return incompleteWorkflowRunCount;
        }
        /**
         * @return number of runs without a definable logical workflow, counted as
         *         neither success nor failure
         */
        public int getNoLogicalTaskRunCount() {
            return noLogicalTaskRunCount;
        }
        /**
         * Return the complete workflow success rate at the model-run level.
         *
         * @return successes divided by eligible runs; {@code null} when there is no eligible run
         */
        public Double getSuccessfulWorkflowRunRate() {
            return successfulWorkflowRunRate;
        }
        /**
         * @return Wilson score interval that is interpretable as inference only under the
         *         independent-replication design
         */
        public ProportionConfidenceInterval getWilsonConfidenceInterval() {
            return wilsonConfidenceInterval;
        }
    }

    /**
     * Summarize only run samples that satisfy an explicit condition, avoiding imputation
     * with the simulation end time of failed runs.
     */
    public static final class ConditionalMetricSummary {
        private final String population;
        private final int successfulWorkflowRunCount;
        private final int completionTimeObservationCount;
        private final int unavailableCompletionTimeCount;
        private final MetricSummary summary;
        private final String status;

        private ConditionalMetricSummary(
                String population,
                int successfulWorkflowRunCount,
                int completionTimeObservationCount,
                int unavailableCompletionTimeCount,
                MetricSummary summary,
                String status) {
            this.population = population;
            this.successfulWorkflowRunCount = successfulWorkflowRunCount;
            this.completionTimeObservationCount = completionTimeObservationCount;
            this.unavailableCompletionTimeCount = unavailableCompletionTimeCount;
            this.summary = summary;
            this.status = status;
        }

        static ConditionalMetricSummary from(
                int successfulWorkflowRunCount,
                List<Double> completionTimes,
                int unavailableCompletionTimeCount,
                RandomizationDesign design) {
            if (successfulWorkflowRunCount < 0
                    || unavailableCompletionTimeCount < 0
                    || completionTimes == null
                    || successfulWorkflowRunCount != completionTimes.size() + unavailableCompletionTimeCount) {
                throw new IllegalArgumentException("Successful workflow completion-time evidence is inconsistent");
            }
            if (unavailableCompletionTimeCount != 0) {
                throw new IllegalStateException("Successful workflow runs cannot lack logical completion time");
            }
            if (completionTimes.isEmpty()) {
                return new ConditionalMetricSummary(
                        "SUCCESSFUL_WORKFLOW_RUNS_ONLY",
                        successfulWorkflowRunCount,
                        0,
                        0,
                        null,
                        "UNAVAILABLE_NO_SUCCESSFUL_WORKFLOW_RUNS");
            }
            return new ConditionalMetricSummary(
                    "SUCCESSFUL_WORKFLOW_RUNS_ONLY",
                    successfulWorkflowRunCount,
                    completionTimes.size(),
                    0,
                    MetricSummary.from(completionTimes, design),
                    "AVAILABLE_SUCCESSFUL_WORKFLOW_RUNS_ONLY");
        }

        /** @return fixed to {@code SUCCESSFUL_WORKFLOW_RUNS_ONLY} */
        public String getPopulation() {
            return population;
        }

        public int getSuccessfulWorkflowRunCount() {
            return successfulWorkflowRunCount;
        }

        public int getCompletionTimeObservationCount() {
            return completionTimeObservationCount;
        }

        public int getUnavailableCompletionTimeCount() {
            return unavailableCompletionTimeCount;
        }
        /** @return conditional sample summary; {@code null} when there is no successful workflow run */
        public MetricSummary getSummary() {
            return summary;
        }

        public String getStatus() {
            return status;
        }
    }

    /** Wilson score interval for the binary workflow-run success rate, or the reason it is unavailable. */
    public static final class ProportionConfidenceInterval {
        private final boolean available;
        private final double confidenceLevel;
        private final Double lower;
        private final Double upper;
        private final String status;
        private final String method;

        private ProportionConfidenceInterval(boolean available, Double lower, Double upper, String status) {
            this.available = available;
            this.confidenceLevel = CONFIDENCE_LEVEL;
            this.lower = lower;
            this.upper = upper;
            this.status = status;
            this.method = "WILSON_SCORE";
        }

        static ProportionConfidenceInterval forWilson(int successes, int observations, RandomizationDesign design) {
            if (successes < 0 || observations < 0 || successes > observations) {
                throw new IllegalArgumentException("Wilson interval success counts are inconsistent");
            }
            if (observations == 0) {
                return new ProportionConfidenceInterval(false, null, null, "UNAVAILABLE_NO_ELIGIBLE_WORKFLOW_RUNS");
            }
            if (design != RandomizationDesign.INDEPENDENT_REPLICATIONS) {
                return new ProportionConfidenceInterval(
                        false, null, null, "UNAVAILABLE_RANDOMIZATION_DESIGN_IS_NOT_INDEPENDENT_REPLICATIONS");
            }
            double proportion = ((double) successes) / observations;
            double z = new NormalDistribution().inverseCumulativeProbability(0.5 + CONFIDENCE_LEVEL / 2.0);
            double zSquared = z * z;
            double denominator = 1.0 + zSquared / observations;
            double center = (proportion + zSquared / (2.0 * observations)) / denominator;
            double halfWidth = z
                    * Math.sqrt((proportion * (1.0 - proportion) + zSquared / (4.0 * observations)) / observations)
                    / denominator;
            return new ProportionConfidenceInterval(
                    true,
                    Double.valueOf(Math.max(0.0, center - halfWidth)),
                    Double.valueOf(Math.min(1.0, center + halfWidth)),
                    "AVAILABLE_WILSON_SCORE_PROPORTION_INTERVAL");
        }

        public boolean isAvailable() {
            return available;
        }

        public double getConfidenceLevel() {
            return confidenceLevel;
        }

        public Double getLower() {
            return lower;
        }

        public Double getUpper() {
            return upper;
        }

        public String getStatus() {
            return status;
        }

        public String getMethod() {
            return method;
        }
    }

    /** 95% mean confidence interval whose sample summary and applicability conditions are explicitly labeled. */
    public static final class MetricSummary {
        private final List<Double> samples;
        private final int sampleSize;
        private final double mean;
        private final double sampleStandardDeviation;
        private final double minimum;
        private final double maximum;
        private final ConfidenceInterval meanConfidenceInterval;

        private MetricSummary(
                List<Double> samples,
                double mean,
                double sampleStandardDeviation,
                double minimum,
                double maximum,
                ConfidenceInterval meanConfidenceInterval) {
            this.samples = Collections.unmodifiableList(new ArrayList<Double>(samples));
            this.sampleSize = samples.size();
            this.mean = mean;
            this.sampleStandardDeviation = sampleStandardDeviation;
            this.minimum = minimum;
            this.maximum = maximum;
            this.meanConfidenceInterval = meanConfidenceInterval;
        }

        static MetricSummary from(List<Double> values, RandomizationDesign design) {
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("A metric summary requires at least one sample");
            }
            double sum = 0.0;
            double minimum = Double.POSITIVE_INFINITY;
            double maximum = Double.NEGATIVE_INFINITY;
            for (Double boxed : values) {
                if (boxed == null || Double.isNaN(boxed.doubleValue()) || Double.isInfinite(boxed.doubleValue())) {
                    throw new IllegalArgumentException("Metric samples must be finite");
                }
                sum += boxed.doubleValue();
                minimum = Math.min(minimum, boxed.doubleValue());
                maximum = Math.max(maximum, boxed.doubleValue());
            }
            double mean = sum / values.size();
            double squaredDifferenceSum = 0.0;
            for (Double value : values) {
                double difference = value.doubleValue() - mean;
                squaredDifferenceSum += difference * difference;
            }
            double standardDeviation = values.size() < 2 ? 0.0 : Math.sqrt(squaredDifferenceSum / (values.size() - 1));
            ConfidenceInterval interval = ConfidenceInterval.forMean(values.size(), mean, standardDeviation, design);
            return new MetricSummary(values, mean, standardDeviation, minimum, maximum, interval);
        }

        public List<Double> getSamples() {
            return samples;
        }

        public int getSampleSize() {
            return sampleSize;
        }

        public double getMean() {
            return mean;
        }

        public double getSampleStandardDeviation() {
            return sampleStandardDeviation;
        }

        public double getMinimum() {
            return minimum;
        }

        public double getMaximum() {
            return maximum;
        }

        public ConfidenceInterval getMeanConfidenceInterval() {
            return meanConfidenceInterval;
        }
    }

    /** Mean confidence interval, or a machine-readable status stating why it is unavailable. */
    public static final class ConfidenceInterval {
        private final boolean available;
        private final double confidenceLevel;
        private final Double lower;
        private final Double upper;
        private final String status;

        private ConfidenceInterval(boolean available, Double lower, Double upper, String status) {
            this.available = available;
            this.confidenceLevel = CONFIDENCE_LEVEL;
            this.lower = lower;
            this.upper = upper;
            this.status = status;
        }

        private static ConfidenceInterval forMean(
                int sampleSize, double mean, double standardDeviation, RandomizationDesign design) {
            if (design != RandomizationDesign.INDEPENDENT_REPLICATIONS) {
                return new ConfidenceInterval(
                        false, null, null, "UNAVAILABLE_RANDOMIZATION_DESIGN_IS_NOT_INDEPENDENT_REPLICATIONS");
            }
            if (sampleSize < 2) {
                return new ConfidenceInterval(false, null, null, "UNAVAILABLE_FEWER_THAN_TWO_REPLICATIONS");
            }
            double critical =
                    new TDistribution(sampleSize - 1).inverseCumulativeProbability(0.5 + CONFIDENCE_LEVEL / 2.0);
            double margin = critical * standardDeviation / Math.sqrt(sampleSize);
            return new ConfidenceInterval(
                    true,
                    Double.valueOf(mean - margin),
                    Double.valueOf(mean + margin),
                    "AVAILABLE_STUDENT_T_MEAN_INTERVAL");
        }

        public boolean isAvailable() {
            return available;
        }

        public double getConfidenceLevel() {
            return confidenceLevel;
        }

        public Double getLower() {
            return lower;
        }

        public Double getUpper() {
            return upper;
        }

        public String getStatus() {
            return status;
        }
    }

    private static double mean(List<Double> values) {
        double total = 0.0;
        for (Double value : values) {
            total += value.doubleValue();
        }
        return total / values.size();
    }
}
