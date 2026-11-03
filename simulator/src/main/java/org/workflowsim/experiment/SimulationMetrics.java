package org.workflowsim.experiment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/**
 * Explicitly named model-level metrics derived from an immutable {@link SimulationReport}.
 * These values describe this simulator run, not measured data of production workflow execution.
 *
 * <p><b>MethodLength precedent (2026-10-23).</b>The only two over-limit sections of this class
 * -- the constructor and {@code assembleMetrics(...)} -- are structurally irreducible: a
 * field-by-field construction of a ~79-field immutable metric ledger plus a single 79-parameter
 * atomic snapshot call. Any "extraction" would only manufacture artificial fragments and break
 * the cohesion of snapshot assembly, and a field-by-field review found no groupable semantic
 * clusters. Under the MethodLength Tier-B precedent ledger these two sites are registered as
 * documented irreducible and are not forced to zero; the only legitimate way to clear the
 * precedent is a contractual reorganization of the metric fields (a schema proposal, not a style
 * refactor).
 * (BAT-D2, 2026-10-29: the +2 fields are the per-tier busy-seconds ledger pair; the precedent
 * registration follows the ledger.)</p>
 */
public final class SimulationMetrics {

    private final double makespanSeconds;
    private final Double logicalTaskCompletionSeconds;
    private final String logicalTaskCompletionStatus;
    private final Double terminalLifecycleTailSeconds;
    private final int jobOutcomeCount;
    private final int computeJobOutcomeCount;
    private final int stageInJobOutcomeCount;
    private final int successfulJobOutcomeCount;
    private final int failedJobOutcomeCount;
    private final int successfulComputeJobOutcomeCount;
    private final int failedComputeJobOutcomeCount;
    private final double successfulComputeJobOutcomeRate;
    private final int logicalTaskCount;
    private final int successfullyCompletedLogicalTaskCount;
    private final int logicalTasksNotYetSuccessfullyCompletedCount;
    private final double successfulLogicalTaskCompletionRate;
    private final boolean allLogicalTasksCompletedSuccessfully;
    private final int initialComputeJobOutcomeCount;
    private final int retryJobCreatedCount;
    private final int completedRetryComputeJobOutcomeCount;
    private final int logicalTaskAttemptCount;
    private final int retriedLogicalTaskCount;
    private final double failedComputeAttemptEnvelopeSeconds;
    private final double failedComputeAttemptModeledProcessingCost;
    private final double retryComputeAttemptModeledProcessingCost;
    private final double computeJobOutcomeThroughputPerSecond;
    private final double meanComputeJobRunTimeSeconds;
    private final double meanJobVmQueueWaitingTimeSeconds;
    private final double meanJobResponseTimeSeconds;
    private final double meanJobVmLevelSlowdown;
    /** Number of VM-level slowdown observations (only jobs with executionTime > 0.001s; same set as the mean). */
    private final int vmLevelSlowdownObservationCount;

    private final double meanComputeTotalWaitingTimeSeconds;
    private final double meanComputeTrueSlowdown;
    private final int trueSlowdownObservationCount;
    /** Total-waiting-time observation count (same scope as true slowdown: compute jobs with complete ready events). */
    private final int totalWaitingTimeObservationCount;
    /** Median total waiting time (seconds); a tail statistic -- scheduling differences often show in the tail. */
    private final double medianComputeTotalWaitingTimeSeconds;
    /** Total waiting time P95 (seconds, nearest-rank method). */
    private final double p95ComputeTotalWaitingTimeSeconds;
    /** Maximum total waiting time (seconds). */
    private final double maxComputeTotalWaitingTimeSeconds;
    /** Median true slowdown; a tail statistic of the distribution. */
    private final double medianComputeTrueSlowdown;
    /** True slowdown P95 (nearest-rank method). */
    private final double p95ComputeTrueSlowdown;
    /** Maximum true slowdown. */
    private final double maxComputeTrueSlowdown;
    /** Success-only mean total waiting time (seconds); conditioned on success, no selection-bias claim. */
    private final double successOnlyMeanComputeTotalWaitingTimeSeconds;
    /** Success-only mean true slowdown; conditioned on success, no selection-bias claim. */
    private final double successOnlyMeanComputeTrueSlowdown;
    /** Observation count of the success-only variant (successful compute jobs with complete ready events). */
    private final int successOnlyWaitingObservationCount;
    /** Retry amplification ratio = logical task attempts / logical tasks; 1.0 with no failures, 0.0 with no tasks. */
    private final double retryAmplificationRatio;

    private final double meanComputeReadyToDecisionDelaySeconds;
    private final double meanComputeDecisionToStartDelaySeconds;
    private final int readyToDecisionObservationCount;
    private final int decisionToStartObservationCount;
    private final int schedulingCycleCount;
    private final long totalSchedulingDecisionWallClockNanos;
    private final int explicitPlannerDecisionObservationCount;
    private final long totalPlanningDecisionWallClockNanos;
    private final int dataStageInModelObservationCount;
    private final int modeledDataTransferFileCount;
    private final double totalModeledDataTransferSeconds;
    private final double totalModeledRequiredInputBytes;
    private final double meanModeledDataTransferSeconds;
    private final double totalModeledProcessingCost;
    private final double totalModeledCpuEnvelopeCost;
    private final double totalModeledDeclaredFileBandwidthCost;
    private final double totalModeledDeclaredFileBytes;
    private final Map<Integer, VmMetrics> vmMetrics;
    private final double totalVmModeledBusyIntervalSeconds;
    private final double meanVmModeledIntervalUtilization;
    private final double vmModeledBusyTimeCoefficientOfVariation;
    /** VM utilization Jain fairness index in (0,1]; 1/(1+CV^2) under nonzero load in the same window. */
    private final double vmUtilizationJainFairnessIndex;

    /**
     * BAT-D2 (2026-10-29): per-VM per-tier busy-seconds ledger --
     * {@code vmId -> (tier MIPS -> busy seconds)}.
     *
     * <p>Each Job attempt's [startTime, finishTime) interval is attributed to its assigned tier;
     * a Job with {@code tierMips == null} (impossible outside a tierless VM -- a tierless VM is
     * not booked at all) is attributed to that VM's default tier (the first table entry). An empty
     * map = a v1 run without frequency tiers (the conservation law degenerates to D1's
     * "all default tier" scope). transient: the serialization surface of the manifest metrics
     * section does not grow with this ledger (an evidence-surface change belongs to the D3/D4
     * ledger batch); the Java side exposes it through a getter for energy-conservation auditing
     * and tests.</p>
     */
    private final transient Map<Integer, Map<Double, Double>> perTierBusySecondsByVm;
    /** BAT-D2: fleet total per-tier busy seconds -- {@code tier MIPS -> busy seconds} (TreeMap ascending). */
    private final transient Map<Double, Double> fleetPerTierBusySeconds;

    private final boolean controlledSharedStorageCriticalPathReferenceAvailable;
    private final String controlledSharedStorageCriticalPathReferenceScope;
    private final double controlledSharedStorageCriticalPathLowerBoundSeconds;
    private final double controlledSharedStorageScheduleLengthRatio;
    private final boolean deadlineObservationEnabled;
    private final String deadlineObservationTimeBasis;
    private final String deadlineObservationOutcome;
    private final boolean deadlineMet;
    private final double deadlineSlackSeconds;
    private final double deadlineTardinessSeconds;
    private final int exactTaskTimingObservationCount;
    private final int modeledApproximateTaskTimingObservationCount;

    private SimulationMetrics(
            double makespanSeconds,
            Double logicalTaskCompletionSeconds,
            String logicalTaskCompletionStatus,
            Double terminalLifecycleTailSeconds,
            int jobOutcomeCount,
            int computeJobOutcomeCount,
            int stageInJobOutcomeCount,
            int successfulJobOutcomeCount,
            int failedJobOutcomeCount,
            int successfulComputeJobOutcomeCount,
            int failedComputeJobOutcomeCount,
            double successfulComputeJobOutcomeRate,
            double computeJobOutcomeThroughputPerSecond,
            int logicalTaskCount,
            int successfullyCompletedLogicalTaskCount,
            int logicalTasksNotYetSuccessfullyCompletedCount,
            double successfulLogicalTaskCompletionRate,
            boolean allLogicalTasksCompletedSuccessfully,
            int initialComputeJobOutcomeCount,
            int retryJobCreatedCount,
            int completedRetryComputeJobOutcomeCount,
            int logicalTaskAttemptCount,
            int retriedLogicalTaskCount,
            double failedComputeAttemptEnvelopeSeconds,
            double failedComputeAttemptModeledProcessingCost,
            double retryComputeAttemptModeledProcessingCost,
            double meanComputeJobRunTimeSeconds,
            double meanJobVmQueueWaitingTimeSeconds,
            double meanJobResponseTimeSeconds,
            double meanJobVmLevelSlowdown,
            int vmLevelSlowdownObservationCount,
            double meanComputeTotalWaitingTimeSeconds,
            double meanComputeTrueSlowdown,
            int trueSlowdownObservationCount,
            int totalWaitingTimeObservationCount,
            double medianComputeTotalWaitingTimeSeconds,
            double p95ComputeTotalWaitingTimeSeconds,
            double maxComputeTotalWaitingTimeSeconds,
            double medianComputeTrueSlowdown,
            double p95ComputeTrueSlowdown,
            double maxComputeTrueSlowdown,
            double successOnlyMeanComputeTotalWaitingTimeSeconds,
            double successOnlyMeanComputeTrueSlowdown,
            int successOnlyWaitingObservationCount,
            double retryAmplificationRatio,
            double meanComputeReadyToDecisionDelaySeconds,
            double meanComputeDecisionToStartDelaySeconds,
            int readyToDecisionObservationCount,
            int decisionToStartObservationCount,
            int schedulingCycleCount,
            long totalSchedulingDecisionWallClockNanos,
            int explicitPlannerDecisionObservationCount,
            long totalPlanningDecisionWallClockNanos,
            int dataStageInModelObservationCount,
            int modeledDataTransferFileCount,
            double totalModeledDataTransferSeconds,
            double totalModeledRequiredInputBytes,
            double meanModeledDataTransferSeconds,
            double totalModeledProcessingCost,
            double totalModeledCpuEnvelopeCost,
            double totalModeledDeclaredFileBandwidthCost,
            double totalModeledDeclaredFileBytes,
            Map<Integer, VmMetrics> vmMetrics,
            double totalVmModeledBusyIntervalSeconds,
            double meanVmModeledIntervalUtilization,
            double vmModeledBusyTimeCoefficientOfVariation,
            double vmUtilizationJainFairnessIndex,
            WorkflowModelReference.Reference criticalPathReference,
            double controlledSharedStorageScheduleLengthRatio,
            boolean deadlineObservationEnabled,
            String deadlineObservationOutcome,
            boolean deadlineMet,
            double deadlineSlackSeconds,
            double deadlineTardinessSeconds,
            int exactTaskTimingObservationCount,
            int modeledApproximateTaskTimingObservationCount,
            Map<Integer, Map<Double, Double>> perTierBusySecondsByVm,
            Map<Double, Double> fleetPerTierBusySeconds) {
        this.makespanSeconds = makespanSeconds;
        this.logicalTaskCompletionSeconds = logicalTaskCompletionSeconds;
        this.logicalTaskCompletionStatus = logicalTaskCompletionStatus;
        this.terminalLifecycleTailSeconds = terminalLifecycleTailSeconds;
        this.jobOutcomeCount = jobOutcomeCount;
        this.computeJobOutcomeCount = computeJobOutcomeCount;
        this.stageInJobOutcomeCount = stageInJobOutcomeCount;
        this.successfulJobOutcomeCount = successfulJobOutcomeCount;
        this.failedJobOutcomeCount = failedJobOutcomeCount;
        this.successfulComputeJobOutcomeCount = successfulComputeJobOutcomeCount;
        this.failedComputeJobOutcomeCount = failedComputeJobOutcomeCount;
        this.successfulComputeJobOutcomeRate = successfulComputeJobOutcomeRate;
        this.logicalTaskCount = logicalTaskCount;
        this.successfullyCompletedLogicalTaskCount = successfullyCompletedLogicalTaskCount;
        this.logicalTasksNotYetSuccessfullyCompletedCount = logicalTasksNotYetSuccessfullyCompletedCount;
        this.successfulLogicalTaskCompletionRate = successfulLogicalTaskCompletionRate;
        this.allLogicalTasksCompletedSuccessfully = allLogicalTasksCompletedSuccessfully;
        this.initialComputeJobOutcomeCount = initialComputeJobOutcomeCount;
        this.retryJobCreatedCount = retryJobCreatedCount;
        this.completedRetryComputeJobOutcomeCount = completedRetryComputeJobOutcomeCount;
        this.logicalTaskAttemptCount = logicalTaskAttemptCount;
        this.retriedLogicalTaskCount = retriedLogicalTaskCount;
        this.failedComputeAttemptEnvelopeSeconds = failedComputeAttemptEnvelopeSeconds;
        this.failedComputeAttemptModeledProcessingCost = failedComputeAttemptModeledProcessingCost;
        this.retryComputeAttemptModeledProcessingCost = retryComputeAttemptModeledProcessingCost;
        this.computeJobOutcomeThroughputPerSecond = computeJobOutcomeThroughputPerSecond;
        this.meanComputeJobRunTimeSeconds = meanComputeJobRunTimeSeconds;
        this.meanJobVmQueueWaitingTimeSeconds = meanJobVmQueueWaitingTimeSeconds;
        this.meanJobResponseTimeSeconds = meanJobResponseTimeSeconds;
        this.meanJobVmLevelSlowdown = meanJobVmLevelSlowdown;
        this.vmLevelSlowdownObservationCount = vmLevelSlowdownObservationCount;
        this.meanComputeTotalWaitingTimeSeconds = meanComputeTotalWaitingTimeSeconds;
        this.meanComputeTrueSlowdown = meanComputeTrueSlowdown;
        this.trueSlowdownObservationCount = trueSlowdownObservationCount;
        this.totalWaitingTimeObservationCount = totalWaitingTimeObservationCount;
        this.medianComputeTotalWaitingTimeSeconds = medianComputeTotalWaitingTimeSeconds;
        this.p95ComputeTotalWaitingTimeSeconds = p95ComputeTotalWaitingTimeSeconds;
        this.maxComputeTotalWaitingTimeSeconds = maxComputeTotalWaitingTimeSeconds;
        this.medianComputeTrueSlowdown = medianComputeTrueSlowdown;
        this.p95ComputeTrueSlowdown = p95ComputeTrueSlowdown;
        this.maxComputeTrueSlowdown = maxComputeTrueSlowdown;
        this.successOnlyMeanComputeTotalWaitingTimeSeconds = successOnlyMeanComputeTotalWaitingTimeSeconds;
        this.successOnlyMeanComputeTrueSlowdown = successOnlyMeanComputeTrueSlowdown;
        this.successOnlyWaitingObservationCount = successOnlyWaitingObservationCount;
        this.retryAmplificationRatio = retryAmplificationRatio;
        this.meanComputeReadyToDecisionDelaySeconds = meanComputeReadyToDecisionDelaySeconds;
        this.meanComputeDecisionToStartDelaySeconds = meanComputeDecisionToStartDelaySeconds;
        this.readyToDecisionObservationCount = readyToDecisionObservationCount;
        this.decisionToStartObservationCount = decisionToStartObservationCount;
        this.schedulingCycleCount = schedulingCycleCount;
        this.totalSchedulingDecisionWallClockNanos = totalSchedulingDecisionWallClockNanos;
        this.explicitPlannerDecisionObservationCount = explicitPlannerDecisionObservationCount;
        this.totalPlanningDecisionWallClockNanos = totalPlanningDecisionWallClockNanos;
        this.dataStageInModelObservationCount = dataStageInModelObservationCount;
        this.modeledDataTransferFileCount = modeledDataTransferFileCount;
        this.totalModeledDataTransferSeconds = totalModeledDataTransferSeconds;
        this.totalModeledRequiredInputBytes = totalModeledRequiredInputBytes;
        this.meanModeledDataTransferSeconds = meanModeledDataTransferSeconds;
        this.totalModeledProcessingCost = totalModeledProcessingCost;
        this.totalModeledCpuEnvelopeCost = totalModeledCpuEnvelopeCost;
        this.totalModeledDeclaredFileBandwidthCost = totalModeledDeclaredFileBandwidthCost;
        this.totalModeledDeclaredFileBytes = totalModeledDeclaredFileBytes;
        this.vmMetrics = Collections.unmodifiableMap(new LinkedHashMap<Integer, VmMetrics>(vmMetrics));
        this.totalVmModeledBusyIntervalSeconds = totalVmModeledBusyIntervalSeconds;
        this.meanVmModeledIntervalUtilization = meanVmModeledIntervalUtilization;
        this.vmModeledBusyTimeCoefficientOfVariation = vmModeledBusyTimeCoefficientOfVariation;
        this.vmUtilizationJainFairnessIndex = vmUtilizationJainFairnessIndex;
        this.controlledSharedStorageCriticalPathReferenceAvailable = criticalPathReference.isAvailable();
        this.controlledSharedStorageCriticalPathReferenceScope = criticalPathReference.getScope();
        this.controlledSharedStorageCriticalPathLowerBoundSeconds =
                criticalPathReference.getCriticalPathLowerBoundSeconds();
        this.controlledSharedStorageScheduleLengthRatio = controlledSharedStorageScheduleLengthRatio;
        this.deadlineObservationEnabled = deadlineObservationEnabled;
        this.deadlineObservationTimeBasis = "SIMULATION_END_SECONDS";
        this.deadlineObservationOutcome = deadlineObservationOutcome;
        this.deadlineMet = deadlineMet;
        this.deadlineSlackSeconds = deadlineSlackSeconds;
        this.deadlineTardinessSeconds = deadlineTardinessSeconds;
        this.exactTaskTimingObservationCount = exactTaskTimingObservationCount;
        this.modeledApproximateTaskTimingObservationCount = modeledApproximateTaskTimingObservationCount;
        // BAT-D2 (2026-10-29): freeze the per-tier ledger -- TreeMap key order keeps determinism (AGENTS #9).
        this.perTierBusySecondsByVm =
                Collections.unmodifiableMap(new LinkedHashMap<Integer, Map<Double, Double>>(perTierBusySecondsByVm));
        this.fleetPerTierBusySeconds =
                Collections.unmodifiableMap(new java.util.TreeMap<Double, Double>(fleetPerTierBusySeconds));
    }

    /**
     * Bounded slowdown threshold (seconds).
     * When computing the slowdown ratio, the execution time floor is set to this value so that
     * extremely short jobs cannot make the numbers blow up. Follows the standard practice of
     * Feitelson et al. in scheduling research.
     */
    private static final double BOUNDED_SLOWDOWN_THRESHOLD_SECONDS = 10.0;

    static SimulationMetrics calculate(
            double makespan,
            List<SimulationReport.JobOutcome> jobs,
            Map<Integer, SimulationReport.VmSummary> summaries,
            List<SimulationEvent> events,
            List<SimulationReport.TaskOutcome> taskOutcomes,
            List<Task> sourceTasks,
            SimulationConfig config,
            PlatformProfile platform) {
        // Numerical stability: require a finite makespan so NaN/Infinity cannot silently leak into the metrics
        requireFinite(makespan, "makespan");
        // Validate the finiteness of the time and cost fields of every JobOutcome
        for (SimulationReport.JobOutcome job : jobs) {
            requireFinite(job.getSubmissionTime(), "job " + job.getJobId() + " submissionTime");
            requireFinite(job.getStartTime(), "job " + job.getJobId() + " startTime");
            requireFinite(job.getFinishTime(), "job " + job.getJobId() + " finishTime");
            requireFinite(job.getCpuTime(), "job " + job.getJobId() + " cpuTime");
            requireFinite(job.getModeledCpuEnvelopeCost(), "job " + job.getJobId() + " cpuCost");
            requireFinite(job.getModeledDeclaredFileBandwidthCost(), "job " + job.getJobId() + " bandwidthCost");
            requireFinite(job.getModeledDeclaredFileBytes(), "job " + job.getJobId() + " declaredFileBytes");
        }
        Map<Integer, SimulationReport.JobOutcome> jobsById = jobOutcomesById(jobs);
        Set<Integer> retryJobIds = retryJobIds(events, jobsById);
        EventTiming timing = scanTimingEvents(events);

        // BAT-D2 (2026-10-29): per-VM default-tier MIPS (first table entry) -- a tierless VM does
        // not enter the map and its busy seconds stay out of the per-tier ledger (v1 degenerates
        // exactly); intervals of unassigned Jobs on a tiered VM go to the default tier.
        Map<Integer, Double> defaultTierMipsByVm = new LinkedHashMap<Integer, Double>();
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.hasFrequencyTiers()) {
                defaultTierMipsByVm.put(
                        Integer.valueOf(spec.getId()),
                        Double.valueOf(spec.defaultFrequencyTier().getMips()));
            }
        }
        Map<Integer, MutableVmMetrics> vm = new LinkedHashMap<Integer, MutableVmMetrics>();
        for (Integer vmId : summaries.keySet()) {
            vm.put(vmId, new MutableVmMetrics(vmId, defaultTierMipsByVm.get(vmId)));
        }
        JobAccumulators acc = accumulateJobOutcomes(jobs, retryJobIds, timing, vm);

        return assembleMetrics(
                makespan, jobs, taskOutcomes, sourceTasks, config, platform, jobsById, retryJobIds, timing, vm, acc);
    }

    /** Job settlement scan stage (moved verbatim from calculate(); the compute branch is moved separately). */
    private static JobAccumulators accumulateJobOutcomes(
            List<SimulationReport.JobOutcome> jobs,
            Set<Integer> retryJobIds,
            EventTiming timing,
            Map<Integer, MutableVmMetrics> vm) {
        JobAccumulators acc = new JobAccumulators();
        for (SimulationReport.JobOutcome job : jobs) {
            if (job.getStatus() == Cloudlet.SUCCESS) {
                acc.success++;
            } else if (job.getStatus() == Cloudlet.FAILED) {
                acc.failure++;
            }
            acc.cost += job.getModeledProcessingCost();
            acc.cpuEnvelopeCost += job.getModeledCpuEnvelopeCost();
            acc.declaredFileBandwidthCost += job.getModeledDeclaredFileBandwidthCost();
            acc.declaredFileBytes += job.getModeledDeclaredFileBytes();
            MutableVmMetrics perVm = vm.get(job.getVmId());
            if (perVm != null) {
                perVm.add(job);
            }

            if (job.getClassType() == Parameters.ClassType.COMPUTE.value) {
                accumulateComputeJob(job, acc, retryJobIds, timing);
            } else if (job.getClassType() == Parameters.ClassType.STAGE_IN.value) {
                acc.stageIn++;
            }
        }
        return acc;
    }

    /** Per-compute-Job counting, waiting and slowdown accumulation (moved verbatim from the calculate() branch). */
    private static void accumulateComputeJob(
            SimulationReport.JobOutcome job, JobAccumulators acc, Set<Integer> retryJobIds, EventTiming timing) {
        acc.compute++;
        if (retryJobIds.contains(job.getJobId())) {
            acc.completedRetryComputeJobs++;
            acc.retryComputeCost += job.getModeledProcessingCost();
        }
        if (job.getStatus() == Cloudlet.SUCCESS) {
            acc.computeSuccess++;
        } else if (job.getStatus() == Cloudlet.FAILED) {
            acc.computeFailure++;
            acc.failedComputeEnvelopeSeconds += nonNegative(job.getFinishTime() - job.getStartTime());
            acc.failedComputeCost += job.getModeledProcessingCost();
        }
        acc.computeRunTimeSum += nonNegative(job.getFinishTime() - job.getStartTime());

        // Accumulate waiting time and response time (all jobs, successful and failed)
        acc.jobWaitingTimeSum += job.getWaitingTime();
        acc.jobResponseTimeSum += job.getResponseTime();

        // Accumulate the slowdown ratio (only jobs with execution time > 0.001 seconds, to avoid division by zero)
        if (job.getExecutionTime() > 0.001) {
            double slowdown = job.getResponseTime() / job.getExecutionTime();
            acc.jobSlowdownSum += slowdown;
            acc.jobSlowdownCount++;
        }

        Double ready = timing.readyTimes.get(job.getJobId());
        Double decision = timing.decisionTimes.get(job.getJobId());
        if (ready != null && decision != null && decision.doubleValue() >= ready.doubleValue()) {
            acc.readyToDecisionSum += decision.doubleValue() - ready.doubleValue();
            acc.readyToDecisionCount++;
        }
        if (decision != null && job.getStartTime() >= decision.doubleValue()) {
            acc.decisionToStartSum += job.getStartTime() - decision.doubleValue();
            acc.decisionToStartCount++;
        }
        // Total waiting time (ready to start of execution): covers scheduler queue waiting + dispatch delay
        if (ready != null && job.getStartTime() >= ready.doubleValue()) {
            double trueWait = job.getStartTime() - ready.doubleValue();
            double execTime = nonNegative(job.getFinishTime() - job.getStartTime());
            // True slowdown (bounded slowdown, Feitelson standard):
            // max( (true wait + execution time) / max(execution time, 10s), 1.0 )
            // The 10-second denominator floor prevents numerical blow-up on extremely short jobs;
            // the outer max(1) keeps the slowdown semantics (there is no "speedup")
            double boundedExec = Math.max(execTime, BOUNDED_SLOWDOWN_THRESHOLD_SECONDS);
            double trueSlowdown = Math.max((trueWait + execTime) / boundedExec, 1.0);

            acc.totalWaitingTimeSum += trueWait;
            acc.totalWaitingTimeCount++;
            acc.totalWaitingSamples.add(Double.valueOf(trueWait));
            acc.trueSlowdownSum += trueSlowdown;
            acc.trueSlowdownCount++;
            acc.trueSlowdownSamples.add(Double.valueOf(trueSlowdown));

            // success-only variant: drop failed attempts so means stay unbiased when comparing schemes
            // with different failure rates.
            if (job.getStatus() == Cloudlet.SUCCESS) {
                acc.successOnlyTotalWaitingSum += trueWait;
                acc.successOnlyTrueSlowdownSum += trueSlowdown;
                acc.successOnlyWaitingObservationCount++;
            }
        }
    }

    /** Private move-only structure for the calculate() settlement scope (keeps the original declaration order). */
    private static final class JobAccumulators {
        int compute;
        int stageIn;
        int success;
        int failure;
        int computeSuccess;
        int computeFailure;
        double computeRunTimeSum;
        double jobWaitingTimeSum;
        double jobResponseTimeSum;
        double jobSlowdownSum;
        int jobSlowdownCount;
        double trueSlowdownSum;
        int trueSlowdownCount;
        double readyToDecisionSum;
        int readyToDecisionCount;
        double decisionToStartSum;
        int decisionToStartCount;
        double totalWaitingTimeSum;
        int totalWaitingTimeCount;
        // Distribution samples (median/p95/max) and the success-only variant accumulators.
        final List<Double> totalWaitingSamples = new ArrayList<Double>();
        final List<Double> trueSlowdownSamples = new ArrayList<Double>();
        double successOnlyTotalWaitingSum;
        double successOnlyTrueSlowdownSum;
        int successOnlyWaitingObservationCount;
        double cost;
        double cpuEnvelopeCost;
        double declaredFileBandwidthCost;
        double declaredFileBytes;
        int completedRetryComputeJobs;
        double retryComputeCost;
        double failedComputeEnvelopeSeconds;
        double failedComputeCost;
    }

    /** VM freeze stage (moved verbatim from calculate(); BAT-D2 appends the per-tier ledger freeze). */
    private static FrozenVms freezeVmMetrics(double makespan, Map<Integer, MutableVmMetrics> vm) {
        Map<Integer, VmMetrics> frozenVm = new LinkedHashMap<Integer, VmMetrics>();
        List<Double> busyTimes = new ArrayList<Double>();
        double totalBusyInterval = 0.0;
        // BAT-D2: per-VM per-tier plus fleet total (TreeMap ascending keys -- deterministic traversal).
        Map<Integer, Map<Double, Double>> perTierBusy = new LinkedHashMap<Integer, Map<Double, Double>>();
        java.util.TreeMap<Double, Double> fleetPerTierBusy = new java.util.TreeMap<Double, Double>();
        for (Map.Entry<Integer, MutableVmMetrics> entry : vm.entrySet()) {
            VmMetrics metrics = entry.getValue().freeze(makespan);
            frozenVm.put(entry.getKey(), metrics);
            busyTimes.add(metrics.getModeledBusyIntervalSeconds());
            totalBusyInterval += metrics.getModeledBusyIntervalSeconds();
            if (!entry.getValue().tierBusySeconds.isEmpty()) {
                perTierBusy.put(
                        entry.getKey(),
                        Collections.unmodifiableMap(
                                new java.util.TreeMap<Double, Double>(entry.getValue().tierBusySeconds)));
                for (Map.Entry<Double, Double> tier :
                        entry.getValue().tierBusySeconds.entrySet()) {
                    fleetPerTierBusy.merge(tier.getKey(), tier.getValue(), Double::sum);
                }
            }
        }
        return new FrozenVms(
                frozenVm,
                busyTimes,
                totalBusyInterval,
                Collections.unmodifiableMap(perTierBusy),
                Collections.unmodifiableMap(fleetPerTierBusy));
    }

    /** Private move-only structure for the VM freeze stage of calculate(). */
    private static final class FrozenVms {
        final Map<Integer, VmMetrics> frozenVm;
        final List<Double> busyTimes;
        final double totalBusyInterval;
        /** BAT-D2 (2026-10-29): per-VM per-tier busy seconds + fleet total per-tier busy seconds (immutable). */
        final Map<Integer, Map<Double, Double>> perTierBusySecondsByVm;

        final Map<Double, Double> fleetPerTierBusySeconds;

        FrozenVms(
                Map<Integer, VmMetrics> frozenVm,
                List<Double> busyTimes,
                double totalBusyInterval,
                Map<Integer, Map<Double, Double>> perTierBusySecondsByVm,
                Map<Double, Double> fleetPerTierBusySeconds) {
            this.frozenVm = frozenVm;
            this.busyTimes = busyTimes;
            this.totalBusyInterval = totalBusyInterval;
            this.perTierBusySecondsByVm = perTierBusySecondsByVm;
            this.fleetPerTierBusySeconds = fleetPerTierBusySeconds;
        }
    }

    /** Logical task completion / critical path / schedule length ratio stage (moved verbatim from calculate()). */
    private static LogicalSupport logicalCompletion(
            double makespan,
            List<SimulationReport.JobOutcome> jobs,
            List<SimulationReport.TaskOutcome> taskOutcomes,
            List<Task> sourceTasks,
            Map<Integer, SimulationReport.JobOutcome> jobsById,
            Set<Integer> retryJobIds,
            SimulationConfig config,
            PlatformProfile platform) {
        Set<Integer> logicalTaskIds = logicalTaskIds(sourceTasks, taskOutcomes);
        Map<Integer, Double> earliestSuccessfulJobFinishByLogicalTask =
                earliestSuccessfulJobFinishByLogicalTask(jobs, logicalTaskIds);
        Set<Integer> successfullyCompletedTaskIds =
                new HashSet<Integer>(earliestSuccessfulJobFinishByLogicalTask.keySet());
        int logicalTaskCount = logicalTaskIds.size();
        int logicalSuccesses = successfullyCompletedTaskIds.size();
        String logicalTaskCompletionStatus;
        Double logicalTaskCompletionSeconds;
        Double terminalLifecycleTailSeconds;
        if (logicalTaskCount == 0) {
            logicalTaskCompletionStatus = "NO_LOGICAL_TASKS";
            logicalTaskCompletionSeconds = null;
            terminalLifecycleTailSeconds = null;
        } else if (logicalSuccesses != logicalTaskCount) {
            logicalTaskCompletionStatus = "INCOMPLETE_LOGICAL_TASKS";
            logicalTaskCompletionSeconds = null;
            terminalLifecycleTailSeconds = null;
        } else {
            logicalTaskCompletionStatus = "COMPLETED_SUCCESSFULLY";
            logicalTaskCompletionSeconds = Double.valueOf(latestFinish(earliestSuccessfulJobFinishByLogicalTask));
            terminalLifecycleTailSeconds =
                    Double.valueOf(nonNegative(makespan - logicalTaskCompletionSeconds.doubleValue()));
        }
        int logicalAttempts = logicalTaskAttemptCount(taskOutcomes, jobsById, logicalTaskIds);
        int retriedLogicalTasks = retriedLogicalTaskCount(retryJobIds, jobsById, logicalTaskIds);
        WorkflowModelReference.Reference criticalPathReference =
                WorkflowModelReference.calculate(sourceTasks, config, platform);
        double scheduleLengthRatio =
                criticalPathReference.isAvailable() && criticalPathReference.getCriticalPathLowerBoundSeconds() > 0.0
                        ? makespan / criticalPathReference.getCriticalPathLowerBoundSeconds()
                        : 0.0;
        return new LogicalSupport(
                logicalTaskCount,
                logicalSuccesses,
                logicalTaskCompletionStatus,
                logicalTaskCompletionSeconds,
                terminalLifecycleTailSeconds,
                logicalAttempts,
                retriedLogicalTasks,
                criticalPathReference,
                scheduleLengthRatio);
    }

    /** Private move-only structure for the logical task stage of calculate(). */
    private static final class LogicalSupport {
        final int logicalTaskCount;
        final int logicalSuccesses;
        final String logicalTaskCompletionStatus;
        final Double logicalTaskCompletionSeconds;
        final Double terminalLifecycleTailSeconds;
        final int logicalAttempts;
        final int retriedLogicalTasks;
        final WorkflowModelReference.Reference criticalPathReference;
        final double scheduleLengthRatio;

        LogicalSupport(
                int logicalTaskCount,
                int logicalSuccesses,
                String logicalTaskCompletionStatus,
                Double logicalTaskCompletionSeconds,
                Double terminalLifecycleTailSeconds,
                int logicalAttempts,
                int retriedLogicalTasks,
                WorkflowModelReference.Reference criticalPathReference,
                double scheduleLengthRatio) {
            this.logicalTaskCount = logicalTaskCount;
            this.logicalSuccesses = logicalSuccesses;
            this.logicalTaskCompletionStatus = logicalTaskCompletionStatus;
            this.logicalTaskCompletionSeconds = logicalTaskCompletionSeconds;
            this.terminalLifecycleTailSeconds = terminalLifecycleTailSeconds;
            this.logicalAttempts = logicalAttempts;
            this.retriedLogicalTasks = retriedLogicalTasks;
            this.criticalPathReference = criticalPathReference;
            this.scheduleLengthRatio = scheduleLengthRatio;
        }
    }

    /**
     * deadline evaluation and exact-timing sample count stage (moved verbatim from calculate();
     * the BigDecimal exact-semantics comment stays in place).
     */
    private static DeadlineResults evaluateDeadline(
            SimulationConfig config,
            double makespan,
            List<SimulationReport.TaskOutcome> taskOutcomes,
            String logicalTaskCompletionStatus) {
        boolean workflowCompleted = "COMPLETED_SUCCESSFULLY".equals(logicalTaskCompletionStatus);
        boolean deadlineEnabled = config.getDeadline() > 0L;
        // Preserve the exact long threshold and the exact binary64 clock before subtracting.
        // BigDecimal.valueOf(makespan) would instead use a rounded shortest decimal string:
        // at 2^63 that would turn the true one-second miss of Long.MAX_VALUE into 193 seconds.
        BigDecimal exactDeadlineSlack = deadlineEnabled
                ? BigDecimal.valueOf(config.getDeadline()).subtract(new BigDecimal(makespan))
                : BigDecimal.ZERO;
        String deadlineOutcome = deadlineOutcome(deadlineEnabled, workflowCompleted, exactDeadlineSlack.signum());
        boolean deadlineMet = "MET".equals(deadlineOutcome);
        double deadlineSlack = exactDeadlineSlack.doubleValue();
        double deadlineTardiness = Math.max(0.0, -deadlineSlack);
        int exactTaskTimingCount = 0;
        for (SimulationReport.TaskOutcome outcome : taskOutcomes) {
            if (outcome.hasExactJobTiming()) {
                exactTaskTimingCount++;
            }
        }
        return new DeadlineResults(
                workflowCompleted,
                deadlineEnabled,
                deadlineOutcome,
                deadlineMet,
                deadlineSlack,
                deadlineTardiness,
                exactTaskTimingCount);
    }

    /** Private move-only structure for the deadline stage of calculate(). */
    private static final class DeadlineResults {
        final boolean workflowCompleted;
        final boolean deadlineEnabled;
        final String outcome;
        final boolean met;
        final double slack;
        final double tardiness;
        final int exactTaskTimingCount;

        DeadlineResults(
                boolean workflowCompleted,
                boolean deadlineEnabled,
                String outcome,
                boolean met,
                double slack,
                double tardiness,
                int exactTaskTimingCount) {
            this.workflowCompleted = workflowCompleted;
            this.deadlineEnabled = deadlineEnabled;
            this.outcome = outcome;
            this.met = met;
            this.slack = slack;
            this.tardiness = tardiness;
            this.exactTaskTimingCount = exactTaskTimingCount;
        }
    }

    /**
     * Assembly stage (moved verbatim from calculate(); the 77-argument constructor call is a single
     * atomic statement of the immutable snapshot class and is near the rule limit).
     */
    private static SimulationMetrics assembleMetrics(
            double makespan,
            List<SimulationReport.JobOutcome> jobs,
            List<SimulationReport.TaskOutcome> taskOutcomes,
            List<Task> sourceTasks,
            SimulationConfig config,
            PlatformProfile platform,
            Map<Integer, SimulationReport.JobOutcome> jobsById,
            Set<Integer> retryJobIds,
            EventTiming timing,
            Map<Integer, MutableVmMetrics> vm,
            JobAccumulators acc) {
        FrozenVms frozen = freezeVmMetrics(makespan, vm);
        LogicalSupport logical =
                logicalCompletion(makespan, jobs, taskOutcomes, sourceTasks, jobsById, retryJobIds, config, platform);
        DeadlineResults deadline =
                evaluateDeadline(config, makespan, taskOutcomes, logical.logicalTaskCompletionStatus);
        // -- Metric enhancements: distribution statistics, fairness index, success-only variant, retry amplification --
        // VM utilization Jain fairness index: (sum u)^2 / (n * sum u^2); equal to the CV in this same window.
        double vmUtilizationJainFairnessIndex = jainFairnessIndex(vmUtilizations(frozen.frozenVm));
        // Waiting/slowdown distribution statistics (nearest rank on the sorted samples gives median/p95/max).
        Collections.sort(acc.totalWaitingSamples);
        Collections.sort(acc.trueSlowdownSamples);
        double retryAmplificationRatio =
                logical.logicalTaskCount > 0 ? (double) logical.logicalAttempts / logical.logicalTaskCount : 0.0;
        return new SimulationMetrics(
                makespan,
                logical.logicalTaskCompletionSeconds,
                logical.logicalTaskCompletionStatus,
                logical.terminalLifecycleTailSeconds,
                jobs.size(),
                acc.compute,
                acc.stageIn,
                acc.success,
                acc.failure,
                acc.computeSuccess,
                acc.computeFailure,
                ratio(acc.computeSuccess, acc.compute),
                makespan > 0.0 ? acc.compute / makespan : 0.0,
                logical.logicalTaskCount,
                logical.logicalSuccesses,
                logical.logicalTaskCount - logical.logicalSuccesses,
                ratio(logical.logicalSuccesses, logical.logicalTaskCount),
                deadline.workflowCompleted,
                acc.compute - acc.completedRetryComputeJobs,
                retryJobIds.size(),
                acc.completedRetryComputeJobs,
                logical.logicalAttempts,
                logical.retriedLogicalTasks,
                acc.failedComputeEnvelopeSeconds,
                acc.failedComputeCost,
                acc.retryComputeCost,
                mean(acc.computeRunTimeSum, acc.compute),
                mean(acc.jobWaitingTimeSum, acc.compute),
                mean(acc.jobResponseTimeSum, acc.compute),
                mean(acc.jobSlowdownSum, acc.jobSlowdownCount),
                acc.jobSlowdownCount,
                mean(acc.totalWaitingTimeSum, acc.totalWaitingTimeCount),
                mean(acc.trueSlowdownSum, acc.trueSlowdownCount),
                acc.trueSlowdownCount,
                acc.totalWaitingTimeCount,
                percentile(acc.totalWaitingSamples, 0.5),
                percentile(acc.totalWaitingSamples, 0.95),
                percentile(acc.totalWaitingSamples, 1.0),
                percentile(acc.trueSlowdownSamples, 0.5),
                percentile(acc.trueSlowdownSamples, 0.95),
                percentile(acc.trueSlowdownSamples, 1.0),
                mean(acc.successOnlyTotalWaitingSum, acc.successOnlyWaitingObservationCount),
                mean(acc.successOnlyTrueSlowdownSum, acc.successOnlyWaitingObservationCount),
                acc.successOnlyWaitingObservationCount,
                retryAmplificationRatio,
                mean(acc.readyToDecisionSum, acc.readyToDecisionCount),
                mean(acc.decisionToStartSum, acc.decisionToStartCount),
                acc.readyToDecisionCount,
                acc.decisionToStartCount,
                timing.schedulingCycles,
                timing.totalSchedulingNanos,
                timing.explicitPlannerDecisions,
                timing.totalPlanningNanos,
                timing.dataStageInObservations,
                timing.modeledTransferFiles,
                timing.totalTransferSeconds,
                timing.totalRequiredInputBytes,
                mean(timing.totalTransferSeconds, timing.dataStageInObservations),
                acc.cost,
                acc.cpuEnvelopeCost,
                acc.declaredFileBandwidthCost,
                acc.declaredFileBytes,
                frozen.frozenVm,
                frozen.totalBusyInterval,
                makespan > 0.0 && !frozen.frozenVm.isEmpty()
                        ? frozen.totalBusyInterval / (makespan * frozen.frozenVm.size())
                        : 0.0,
                coefficientOfVariation(frozen.busyTimes),
                vmUtilizationJainFairnessIndex,
                logical.criticalPathReference,
                logical.scheduleLengthRatio,
                deadline.deadlineEnabled,
                deadline.outcome,
                deadline.met,
                deadline.slack,
                deadline.tardiness,
                deadline.exactTaskTimingCount,
                taskOutcomes.size() - deadline.exactTaskTimingCount,
                // BAT-D2 (2026-10-29): per-tier ledger -- empty = a v1 run without tiers (conservation degenerates).
                frozen.perTierBusySecondsByVm,
                frozen.fleetPerTierBusySeconds);
    }

    /** Event timing scan stage (moved verbatim from calculate(); PLAT-16 duplicate-event fail-fast stays in place). */
    private static EventTiming scanTimingEvents(List<SimulationEvent> events) {
        EventTiming timing = new EventTiming();
        for (SimulationEvent event : events) {
            if (event.getType() == SimulationEventType.JOB_READY && event.getJobId() != null) {
                // PLAT-16: each job must have exactly one JOB_READY event (retry jobs use a new jobId).
                // A duplicate means the event stream is corrupted, and a silent overwrite would quietly
                // distort the waiting-time metrics, hence fail-fast.
                Double previousReady = timing.readyTimes.put(event.getJobId(), event.getSimulationTime());
                if (previousReady != null) {
                    throw new IllegalStateException("Duplicate JOB_READY event for job "
                            + event.getJobId() + ": ready time was already recorded at "
                            + previousReady + " but a second event arrived at "
                            + event.getSimulationTime());
                }
            } else if (event.getType() == SimulationEventType.SCHEDULING_DECISION && event.getJobId() != null) {
                // PLAT-16: same as above -- SCHEDULING_DECISION occurs exactly once per job;
                // a duplicate means the event stream is corrupted.
                Double previousDecision = timing.decisionTimes.put(event.getJobId(), event.getSimulationTime());
                if (previousDecision != null) {
                    throw new IllegalStateException("Duplicate SCHEDULING_DECISION event for job "
                            + event.getJobId() + ": decision time was already recorded at "
                            + previousDecision + " but a second event arrived at "
                            + event.getSimulationTime());
                }
            } else if (event.getType() == SimulationEventType.SCHEDULING_CYCLE) {
                timing.schedulingCycles++;
                Object elapsed = event.getAttributes().get("decisionElapsedNanos");
                if (elapsed instanceof Number) {
                    timing.totalSchedulingNanos += ((Number) elapsed).longValue();
                }
            } else if (event.getType() == SimulationEventType.PLANNING_COMPLETED
                    && Boolean.TRUE.equals(event.getAttributes().get("explicitPlannerDecision"))) {
                timing.explicitPlannerDecisions++;
                timing.totalPlanningNanos += requiredNonNegativeLongAttribute(event, "planningDecisionElapsedNanos");
            } else if (event.getType() == SimulationEventType.DATA_STAGE_IN_MODELED) {
                timing.dataStageInObservations++;
                timing.totalTransferSeconds += requiredFiniteNonNegativeAttribute(event, "modeledTransferSeconds");
                timing.totalRequiredInputBytes += requiredFiniteNonNegativeAttribute(event, "requiredFileBytes");
                timing.modeledTransferFiles += requiredNonNegativeIntegerAttribute(event, "modeledTransferFileCount");
            }
        }
        return timing;
    }

    /** Private move-only structure for the event timing scan stage (keeps the original declaration order). */
    private static final class EventTiming {
        final Map<Integer, Double> readyTimes = new LinkedHashMap<Integer, Double>();
        final Map<Integer, Double> decisionTimes = new LinkedHashMap<Integer, Double>();
        long totalSchedulingNanos;
        int schedulingCycles;
        long totalPlanningNanos;
        int explicitPlannerDecisions;
        int dataStageInObservations;
        int modeledTransferFiles;
        double totalTransferSeconds;
        double totalRequiredInputBytes;
    }

    /**
     * Return the CloudSim simulation-end time under the historical-convention scope.
     *
     * <p>The value may include the workflow-engine lifecycle tail after the last Job returns and
     * must not be read unconditionally as the successful completion time of the last logical
     * Task.</p>
     *
     * @return simulation-end time in simulated seconds
     */
    public double getMakespanSeconds() {
        return makespanSeconds;
    }
    /**
     * Return the CloudSim simulation-end time of this run.
     *
     * @return exactly the same simulated-seconds value as {@link #getMakespanSeconds()}
     */
    public double getSimulationEndSeconds() {
        return makespanSeconds;
    }
    /**
     * Return the maximum Job envelope finish instant at which every logical Task is first
     * completed by a successful compute Job.
     *
     * @return completion seconds when all logical Tasks succeed; otherwise {@code null}
     */
    public Double getLogicalTaskCompletionSeconds() {
        return logicalTaskCompletionSeconds;
    }
    /**
     * Return {@code COMPLETED_SUCCESSFULLY}, {@code INCOMPLETE_LOGICAL_TASKS}, or
     * {@code NO_LOGICAL_TASKS}, making explicit whether the logical completion time is available.
     *
     * @return logical Task completion status
     */
    public String getLogicalTaskCompletionStatus() {
        return logicalTaskCompletionStatus;
    }
    /**
     * Return the lifecycle tail between the last successful logical Task completion and the
     * CloudSim simulation end.
     *
     * @return non-negative simulated seconds when the workflow is complete; otherwise {@code null}
     */
    public Double getTerminalLifecycleTailSeconds() {
        return terminalLifecycleTailSeconds;
    }
    /** @return number of completed Job attempts, equal to {@link #getJobOutcomeCount()} */
    public int getCompletedJobAttemptCount() {
        return jobOutcomeCount;
    }

    public int getJobOutcomeCount() {
        return jobOutcomeCount;
    }
    /** @return number of completed compute Job attempts, including retry attempts */
    public int getCompletedComputeAttemptCount() {
        return computeJobOutcomeCount;
    }

    public int getComputeJobOutcomeCount() {
        return computeJobOutcomeCount;
    }

    public int getStageInJobOutcomeCount() {
        return stageInJobOutcomeCount;
    }

    public int getSuccessfulJobOutcomeCount() {
        return successfulJobOutcomeCount;
    }

    public int getFailedJobOutcomeCount() {
        return failedJobOutcomeCount;
    }

    public int getSuccessfulComputeJobOutcomeCount() {
        return successfulComputeJobOutcomeCount;
    }

    public int getFailedComputeJobOutcomeCount() {
        return failedComputeJobOutcomeCount;
    }
    /**
     * Return the fraction of successful compute Jobs among all compute Jobs.
     *
     * @return successful compute Job outcome rate; zero when there is no compute Job
     */
    public double getSuccessfulComputeJobOutcomeRate() {
        return successfulComputeJobOutcomeRate;
    }

    public int getLogicalTaskCount() {
        return logicalTaskCount;
    }

    public int getSuccessfullyCompletedLogicalTaskCount() {
        return successfullyCompletedLogicalTaskCount;
    }

    public int getLogicalTasksNotYetSuccessfullyCompletedCount() {
        return logicalTasksNotYetSuccessfullyCompletedCount;
    }
    /**
     * Return the fraction of logical tasks completed by at least one successful compute Job.
     *
     * @return successful logical task completion rate; zero when there is no logical task
     */
    public double getSuccessfulLogicalTaskCompletionRate() {
        return successfulLogicalTaskCompletionRate;
    }

    public boolean isAllLogicalTasksCompletedSuccessfully() {
        return allLogicalTasksCompletedSuccessfully;
    }
    /**
     * Return the run-level workflow success indicator.
     *
     * <p>It is a completion fact of one run and must not be read on its own as a reliability
     * probability under a failure distribution. The latter can only be estimated at the campaign
     * layer from independent replications.</p>
     *
     * @return whether every logical Task is completed by a successful compute Job
     */
    public boolean isWorkflowCompletedSuccessfully() {
        return allLogicalTasksCompletedSuccessfully;
    }
    /**
     * Return the number of completed compute attempts that do not appear in a
     * {@code RETRY_JOB_CREATED} event.
     *
     * @return initial compute attempt count
     */
    public int getInitialComputeJobOutcomeCount() {
        return initialComputeJobOutcomeCount;
    }
    /** @return number of retry Jobs created in the event stream */
    public int getRetryJobCreatedCount() {
        return retryJobCreatedCount;
    }
    /** @return number of completed compute attempts confirmed by a retry creation event */
    public int getCompletedRetryComputeJobOutcomeCount() {
        return completedRetryComputeJobOutcomeCount;
    }
    /**
     * Return the number of logical Task attempts among completed compute Jobs.
     *
     * <p>A clustered Job can contribute several Task attempts; retries of the same logical Task
     * are counted separately.</p>
     *
     * @return logical Task attempt count
     */
    public int getLogicalTaskAttemptCount() {
        return logicalTaskAttemptCount;
    }
    /** @return number of distinct logical Tasks appearing in at least one retry compute Job */
    public int getRetriedLogicalTaskCount() {
        return retriedLogicalTaskCount;
    }
    /**
     * Return the sum of Job envelope durations of all failed compute attempts.
     *
     * <p>It is the failed-execution evidence of the current fail-after-attempt model and is not
     * automatically equal to the mid-run crash loss or the settleable waste of a real system.</p>
     *
     * @return cumulative envelope seconds of failed compute attempts
     */
    public double getFailedComputeAttemptEnvelopeSeconds() {
        return failedComputeAttemptEnvelopeSeconds;
    }
    /**
     * Return the sum of the modeled processing cost of failed compute attempts.
     *
     * @return abstract cost unit value of failed compute attempts
     */
    public double getFailedComputeAttemptModeledProcessingCost() {
        return failedComputeAttemptModeledProcessingCost;
    }
    /** @return sum of the modeled processing cost of retry compute attempts */
    public double getRetryComputeAttemptModeledProcessingCost() {
        return retryComputeAttemptModeledProcessingCost;
    }
    /**
     * Return the compute Job outcome throughput relative to the makespan.
     *
     * @return compute Job outcomes per simulated second; zero when the makespan is zero
     */
    public double getComputeJobOutcomeThroughputPerSecond() {
        return computeJobOutcomeThroughputPerSecond;
    }
    /**
     * Mean end-to-end duration of the compute Job envelope, including the effective stage-in.
     *
     * <p>The sample is <b>all compute Job attempts</b> (failed and retry attempts included), the
     * same sample scope as {@link #getMeanComputeTotalWaitingTimeSeconds()}.</p>
     *
     * @return mean compute Job envelope duration in simulated seconds
     */
    public double getMeanComputeJobRunTimeSeconds() {
        return meanComputeJobRunTimeSeconds;
    }

    /**
     * Return the mean waiting time of jobs inside the VM queue (arrival at the VM to start of
     * execution).
     * <p><strong>Note:</strong> in WorkflowSim every scheduling algorithm dispatches jobs only to
     * idle VMs, so this value is almost always 0. It measures CloudSim-level VM queueing and does
     * not include scheduler queue waiting. For the true waiting time use
     * {@link #getMeanComputeTotalWaitingTimeSeconds()}.</p>
     *
     * @return mean VM queue waiting time in simulated seconds (usually 0)
     */
    public double getMeanJobVmQueueWaitingTimeSeconds() {
        return meanJobVmQueueWaitingTimeSeconds;
    }

    /**
     * Return the mean job response time (from VM arrival to completion).
     *
     * <p><b>Warning: scope definition</b>: the "submission" here is the CloudSim
     * {@code submissionTime}, that is, the instant the job <strong>arrives at the execution
     * VM</strong> (set by {@code ResCloudlet} when the VM admits it), <b>not</b> the instant the
     * job enters the scheduler ready queue. The metric therefore <b>excludes</b> scheduler queue
     * waiting and dispatch delay.</p>
     * <p>For the end-to-end "ready -> finish" response time use
     * {@code mean total waiting time + mean execution time}; for the pure waiting scope use
     * {@link #getMeanComputeTotalWaitingTimeSeconds()}.</p>
     *
     * @return mean job response time in simulated seconds (VM arrival -> completion)
     */
    public double getMeanJobResponseTimeSeconds() {
        return meanJobResponseTimeSeconds;
    }

    /**
     * Return the mean VM-level slowdown of jobs (response time based on VM arrival / execution
     * time).
     * <p><b>Warning:</b> in WorkflowSim every scheduling algorithm dispatches jobs only to idle
     * VMs, so this value stays at about 1.0 and is <b>not suitable</b> for comparing scheduling
     * algorithms. Use {@link #getMeanComputeTrueSlowdown()} as the real scheduling efficiency
     * metric.</p>
     * <p>Only jobs with execution time > 0.001 seconds are counted, to avoid division by zero.</p>
     *
     * @return dimensionless mean VM-level slowdown (usually about 1.0)
     */
    public double getMeanJobVmLevelSlowdown() {
        return meanJobVmLevelSlowdown;
    }

    /**
     * Return the number of VM-level slowdown observations.
     *
     * <p>Same set as {@link #getMeanJobVmLevelSlowdown()}: only compute jobs with
     * {@code executionTime > 0.001s} are counted (an independent gate against ratio blow-up, with
     * different semantics from the max(exec,10s) denominator clamp of the true slowdown). A count
     * below the mean's denominator means that extremely short or zero-length jobs were
     * excluded.</p>
     *
     * @return observation count
     */
    public int getVmLevelSlowdownObservationCount() {
        return vmLevelSlowdownObservationCount;
    }

    /**
     * Return the mean total waiting time of compute jobs (from ready to start of execution).
     * <p>This is the central waiting-time metric of scheduling research, equal to
     * {@code startTime - readyTime}, covering scheduler waiting and dispatch delay.
     * The pre-execution transfer model also counts data-preparation holding in this waiting; the
     * legacy model puts stage-in into the execution envelope, so comparisons across data movement
     * models must declare these differing inclusion scopes together.
     * Ready times come from {@link SimulationEventType#JOB_READY} events.</p>
     * <p>Relation to {@link #getMeanComputeReadyToDecisionDelaySeconds()} and
     * {@link #getMeanComputeDecisionToStartDelaySeconds()}:
     * total waiting ~= ready-to-decision delay + decision-to-start delay.</p>
     *
     * @return mean total waiting time in simulated seconds (ready to start)
     */
    public double getMeanComputeTotalWaitingTimeSeconds() {
        return meanComputeTotalWaitingTimeSeconds;
    }

    /**
     * Return the mean true slowdown of compute jobs (ready-based, bounded slowdown).
     * <p>Definition (Feitelson standard): for each job compute
     * {@code max((startTime - readyTime + executionTime) / max(executionTime, 10.0), 1.0)},
     * then take the arithmetic mean. Two boundary treatments:</p>
     * <ul>
     *   <li>10-second denominator floor: prevents numerical blow-up on extremely short jobs
     *   (bounded slowdown)</li>
     *   <li>result floor 1.0: guarantees no modeled slowdown below one</li>
     * </ul>
     * <p>The waiting of short tasks can be masked by the 10-second denominator threshold and by
     * the floor, so 1.0 is not equivalent to no waiting. When comparing, report the waiting time,
     * the execution time distribution, and the sample count together.</p>
     * <p>Difference from {@link #getMeanJobVmLevelSlowdown()}: the latter is based on VM arrival
     * time and stays 1.0 in WorkflowSim; this metric is based on job ready time and genuinely
     * reflects the scheduling delay.</p>
     *
     * @return dimensionless mean true slowdown (bounded, 10s denominator floor, result floor 1.0)
     */
    public double getMeanComputeTrueSlowdown() {
        return meanComputeTrueSlowdown;
    }

    /**
     * Return the number of jobs taking part in the true slowdown computation.
     * <p>Only jobs satisfying both of the following conditions take part:</p>
     * <ul>
     *   <li>a JOB_READY event is recorded (readyTime is not null)</li>
     *   <li>startTime &gt;= readyTime (valid timestamps)</li>
     * </ul>
     *
     * @return observation count
     */
    public int getTrueSlowdownObservationCount() {
        return trueSlowdownObservationCount;
    }

    /**
     * Return the number of total-waiting-time observations (the same set as the true slowdown
     * samples).
     *
     * @return observation count
     */
    public int getTotalWaitingTimeObservationCount() {
        return totalWaitingTimeObservationCount;
    }

    /** @return median total waiting time (seconds); 0.0 with no observations */
    public double getMedianComputeTotalWaitingTimeSeconds() {
        return medianComputeTotalWaitingTimeSeconds;
    }

    /** @return total waiting time P95 (seconds, nearest-rank method); 0.0 with no observations */
    public double getP95ComputeTotalWaitingTimeSeconds() {
        return p95ComputeTotalWaitingTimeSeconds;
    }

    /** @return maximum total waiting time (seconds); 0.0 with no observations */
    public double getMaxComputeTotalWaitingTimeSeconds() {
        return maxComputeTotalWaitingTimeSeconds;
    }

    /** @return median true slowdown; 0.0 with no observations */
    public double getMedianComputeTrueSlowdown() {
        return medianComputeTrueSlowdown;
    }

    /** @return true slowdown P95 (nearest-rank method); 0.0 with no observations */
    public double getP95ComputeTrueSlowdown() {
        return p95ComputeTrueSlowdown;
    }

    /** @return maximum true slowdown; 0.0 with no observations */
    public double getMaxComputeTrueSlowdown() {
        return maxComputeTrueSlowdown;
    }

    /**
     * Return the mean total waiting time of successful jobs only (seconds).
     *
     * <p>Conditioned on successful attempts only, with no claim that selection bias is removed:
     * counting the waiting time of failed attempts in the overall mean distorts comparisons across
     * schemes with different failure rates, so this variant keeps only samples of successfully
     * completed jobs.</p>
     *
     * @return success-only mean total waiting time in simulated seconds; 0.0 with no observations
     */
    public double getSuccessOnlyMeanComputeTotalWaitingTimeSeconds() {
        return successOnlyMeanComputeTotalWaitingTimeSeconds;
    }

    /**
     * Return the mean true slowdown of successful jobs only.
     *
     * @return success-only mean true slowdown; 0.0 with no observations
     */
    public double getSuccessOnlyMeanComputeTrueSlowdown() {
        return successOnlyMeanComputeTrueSlowdown;
    }

    /** @return number of observations of the success-only waiting/slowdown variant */
    public int getSuccessOnlyWaitingObservationCount() {
        return successOnlyWaitingObservationCount;
    }

    /**
     * Return the retry amplification ratio = total logical task attempts / logical task count.
     *
     * <p>1.0 with no failures; 0.0 with no logical tasks (this branch carries no SLA meaning; the
     * consumer must consult {@link #getLogicalTaskCount()}). Measures the total execution overhead
     * amplification caused by failure recovery.</p>
     *
     * @return retry amplification ratio; >=1.0 in the normal closed loop, 0.0 with no logical tasks
     */
    public double getRetryAmplificationRatio() {
        return retryAmplificationRatio;
    }

    /**
     * Return the mean delay from a compute Job becoming ready to the scheduler making a decision.
     *
     * @return mean ready-to-decision delay in simulated seconds
     */
    public double getMeanComputeReadyToDecisionDelaySeconds() {
        return meanComputeReadyToDecisionDelaySeconds;
    }
    /**
     * Return the mean delay from the scheduler decision to the start of execution of a compute Job.
     *
     * @return mean decision-to-start delay in simulated seconds
     */
    public double getMeanComputeDecisionToStartDelaySeconds() {
        return meanComputeDecisionToStartDelaySeconds;
    }

    public int getReadyToDecisionObservationCount() {
        return readyToDecisionObservationCount;
    }

    public int getDecisionToStartObservationCount() {
        return decisionToStartObservationCount;
    }

    public int getSchedulingCycleCount() {
        return schedulingCycleCount;
    }
    /**
     * Return the total local JVM wall-clock time consumed by scheduling decisions.
     *
     * <p>It is a local-cost observation and must not be used for cross-machine performance
     * comparison.</p>
     *
     * @return scheduling decision wall-clock cost in nanoseconds
     */
    public long getTotalSchedulingDecisionWallClockNanos() {
        return totalSchedulingDecisionWallClockNanos;
    }
    /**
     * Number of explicit static-planner decisions observed in the event record.
     *
     * @return explicit planner decision observation count
     */
    public int getExplicitPlannerDecisionObservationCount() {
        return explicitPlannerDecisionObservationCount;
    }
    /**
     * JVM wall-clock time consumed by explicit planner decisions. This is local algorithm cost
     * evidence, not simulated time, and cannot serve as a cross-machine performance baseline.
     *
     * @return local wall-clock nanoseconds consumed by explicit planner decisions
     */
    public long getTotalPlanningDecisionWallClockNanos() {
        return totalPlanningDecisionWallClockNanos;
    }
    /**
     * Number of compute Job data stage-in estimates observed in this run.
     *
     * @return data stage-in model observation count
     */
    public int getDataStageInModelObservationCount() {
        return dataStageInModelObservationCount;
    }
    /**
     * Number of logical input demand files observed by the configured data movement model.
     *
     * <p>A local replica hit can make the modeled stage-in time zero, so this is not the number of
     * files actually transferred over the network.</p>
     *
     * @return number of file transfers counted
     */
    public int getModeledDataTransferFileCount() {
        return modeledDataTransferFileCount;
    }
    /** @return the same logical input demand file count as {@link #getModeledDataTransferFileCount()} */
    public int getModeledRequiredInputDemandFileCount() {
        return modeledDataTransferFileCount;
    }
    /**
     * Sum of the nominal stage-in estimate seconds recorded by DATA_STAGE_IN_MODELED.
     *
     * <p>Under the pre-execution model the parent group can run in parallel, and the contention
     * model still records the contention-free nominal estimate here; the sum is therefore neither
     * the wall-clock time of the whole network nor the sum of actual contention holding durations.
     * The effect of actual data preparation on a job appears in the ready-to-start waiting and in
     * the completion instant.</p>
     *
     * @return total nominal stage-in estimate in simulated seconds
     */
    public double getTotalModeledDataTransferSeconds() {
        return totalModeledDataTransferSeconds;
    }
    /**
     * Total logical input demand bytes counted by the transfer observations above.
     *
     * <p>This is not the number of bytes moved over physical links; the current data movement
     * models keep no such link-level ledger.</p>
     *
     * @return total data bytes taken into the model
     */
    public double getTotalModeledRequiredInputBytes() {
        return totalModeledRequiredInputBytes;
    }
    /** @return the same logical input demand bytes as {@link #getTotalModeledRequiredInputBytes()} */
    public double getTotalModeledRequiredInputDemandBytes() {
        return totalModeledRequiredInputBytes;
    }
    /**
     * Mean nominal stage-in estimate per compute Job data-model observation.
     *
     * @return mean nominal estimate in simulated seconds, not the mean actual contention holding
     * time
     */
    public double getMeanModeledDataTransferSeconds() {
        return meanModeledDataTransferSeconds;
    }
    /**
     * Sum of the modeled processing cost of all completed Job attempts; it may include the integer
     * MI representation of stage-in and also covers failed and retry attempts, so it is not a cloud
     * provider price replay.
     *
     * @return aggregated modeled processing cost
     */
    public double getTotalModeledProcessingCost() {
        return totalModeledProcessingCost;
    }
    /**
     * Return the CPU envelope abstract cost component of all Job attempts.
     *
     * @return sum of CPU envelope cost, possibly including the effective stage-in MI
     */
    public double getTotalModeledCpuEnvelopeCost() {
        return totalModeledCpuEnvelopeCost;
    }
    /**
     * Return the bandwidth abstract cost component of all Job attempts, priced continuously by
     * declared file bytes.
     *
     * @return sum of declared file bandwidth cost
     */
    public double getTotalModeledDeclaredFileBandwidthCost() {
        return totalModeledDeclaredFileBandwidthCost;
    }
    /**
     * Return the total byte equivalent entering the declared-file bandwidth abstract cost.
     *
     * @return sum of declared bytes in all Job file tables, not the bytes actually transferred
     */
    public double getTotalModeledDeclaredFileBytes() {
        return totalModeledDeclaredFileBytes;
    }
    /**
     * Return the immutable aggregate metrics indexed by VM identifier.
     *
     * @return mapping from VM ID to per-VM metrics
     */
    public Map<Integer, VmMetrics> getVmMetrics() {
        return vmMetrics;
    }

    public double getTotalVmModeledBusyIntervalSeconds() {
        return totalVmModeledBusyIntervalSeconds;
    }
    /**
     * Return the mean utilization over all VMs, computed from the union of modeled Job intervals.
     *
     * @return mean modeled interval utilization normalized by the makespan
     */
    public double getMeanVmModeledIntervalUtilization() {
        return meanVmModeledIntervalUtilization;
    }
    /**
     * Return the coefficient of variation (CV) of modeled busy time across VMs.
     *
     * <p>Formula: population standard deviation (/n) / mean; 0.0 for a zero mean or an empty set.
     * Because utilization and busy time are both divided by the same constant makespan,
     * CV(busy time) == CV(utilization), and the console label "utilization coefficient of
     * variation" is numerically equivalent. <b>Note</b>: this uses the population standard
     * deviation (/n), while the campaign-layer {@code MetricSummary} uses the sample standard
     * deviation (/(n-1)); mind the convention difference when comparing dispersion across
     * layers.</p>
     *
     * @return busy-time coefficient of variation; 0.0 with no VM or all-zero busy time
     */
    public double getVmModeledBusyTimeCoefficientOfVariation() {
        return vmModeledBusyTimeCoefficientOfVariation;
    }
    /**
     * Return the Jain fairness index of VM utilization in (0,1].
     *
     * <p>For the same VM set and statistical window, with a nonzero mean,
     * Jain = 1 / (1 + CV^2), where CV uses the population standard deviation. The metric provides
     * no load-shape information independent of the CV. With no VM or all-zero utilization it
     * returns the sentinel 1.0, from which no actual resource efficiency may be inferred.</p>
     *
     * @return Jain fairness index
     */
    public double getVmUtilizationJainFairnessIndex() {
        return vmUtilizationJainFairnessIndex;
    }
    /**
     * BAT-D2 (2026-10-29): return the per-VM per-tier busy-seconds ledger
     * {@code vmId -> (tier MIPS -> busy seconds)}.
     *
     * <p>Each Job attempt's execution interval is attributed to its assigned tier (an unassigned
     * attempt goes to the VM's default tier); total busy seconds use the same interval scope as
     * {@code VmMetrics.getModeledBusyIntervalSeconds()}. An empty map = a v1 run without frequency
     * tiers (per-tier conservation degenerates to D1's all-default-tier scope).</p>
     *
     * @return immutable per-VM per-tier busy-seconds mapping; empty for a run without tiers
     */
    public Map<Integer, Map<Double, Double>> getPerTierBusySecondsByVm() {
        return perTierBusySecondsByVm;
    }
    /**
     * BAT-D2 (2026-10-29): return the fleet total per-tier busy seconds
     * {@code tier MIPS -> busy seconds}.
     *
     * <p>Tiers are merged across VMs by MIPS value (a tier is unique by MIPS, so the power pair can
     * be recovered by matching against the platform frequencyTiers); the TreeMap ascending key
     * order keeps determinism. An empty map = a v1 run without tiers.</p>
     *
     * @return immutable fleet per-tier busy-seconds mapping; empty for a run without tiers
     */
    public Map<Double, Double> getFleetPerTierBusySeconds() {
        return fleetPerTierBusySeconds;
    }
    /**
     * Return whether the controlled shared-storage critical path reference applies to the model
     * scope of this run.
     *
     * <p>It requires NONE clustering, no failures, no overhead, and SPACE_SHARED VMs. For data
     * movement only the historical serial-input model or the fixed-endpoint contention-free model
     * is accepted; the latter uses the optimistic reference after removing the extra access
     * latency and bandwidth caps. The pre-execution transfer model can overlap parent inputs, so
     * this reference is unavailable, but those models are not barred from running.</p>
     *
     * @return whether the critical path lower bound reference is available
     */
    public boolean isControlledSharedStorageCriticalPathReferenceAvailable() {
        return controlledSharedStorageCriticalPathReferenceAvailable;
    }
    /**
     * Return the scope of the controlled shared-storage critical path reference, or the reason it
     * is unavailable.
     *
     * @return machine-readable reference scope status
     */
    public String getControlledSharedStorageCriticalPathReferenceScope() {
        return controlledSharedStorageCriticalPathReferenceScope;
    }
    /**
     * Return the critical path makespan lower bound under the controlled shared-storage model.
     *
     * @return lower bound in simulated seconds; zero when the reference is unavailable
     */
    public double getControlledSharedStorageCriticalPathLowerBoundSeconds() {
        return controlledSharedStorageCriticalPathLowerBoundSeconds;
    }
    /**
     * Return the ratio of the makespan to the available critical path lower bound.
     *
     * @return schedule length ratio; zero when the reference is unavailable or the bound is zero
     */
    public double getControlledSharedStorageScheduleLengthRatio() {
        return controlledSharedStorageScheduleLengthRatio;
    }
    /**
     * {@code true} only when the configuration supplies a positive observation threshold.
     *
     * @return whether deadline observation was requested
     */
    public boolean isDeadlineObservationEnabled() {
        return deadlineObservationEnabled;
    }
    /**
     * Return the time axis used by the deadline comparison.
     *
     * @return fixed to {@code SIMULATION_END_SECONDS}, the time axis of the historical makespan
     */
    public String getDeadlineObservationTimeBasis() {
        return deadlineObservationTimeBasis;
    }
    /**
     * Return {@code NOT_REQUESTED}, {@code MET}, {@code MISSED_LATE}, or
     * {@code MISSED_INCOMPLETE_WORKFLOW}.
     *
     * @return machine-readable outcome of the deadline observation
     */
    public String getDeadlineObservationOutcome() {
        return deadlineObservationOutcome;
    }
    /**
     * Reports the SLA observation only and never affects scheduling decisions.
     *
     * @return whether the workflow met the requested deadline
     */
    public boolean isDeadlineMet() {
        return deadlineMet;
    }
    /**
     * {@code deadline - makespan} when enabled, zero when no deadline was requested.
     *
     * <p>The exact long threshold is first subtracted from the exact binary value of the simulation
     * clock, and only the final difference is rounded to the double metric. A large integer
     * threshold is never converted to double first, and the clock's shortest decimal string is
     * never substituted for its exact value; the deadline outcome uses the sign of the unrounded
     * difference.</p>
     *
     * @return deadline slack in simulated seconds
     */
    public double getDeadlineSlackSeconds() {
        return deadlineSlackSeconds;
    }
    /**
     * {@code max(0, makespan - deadline)} when enabled, zero when not requested.
     *
     * <p>Uses the same exact threshold difference as {@link #getDeadlineSlackSeconds()} and does
     * not drop the low bits of the long.</p>
     *
     * @return deadline tardiness in simulated seconds
     */
    public double getDeadlineTardinessSeconds() {
        return deadlineTardinessSeconds;
    }
    /**
     * Number of single-Task computation windows exactly equal to their Job envelope.
     *
     * @return exact task timing observation count
     */
    public int getExactTaskTimingObservationCount() {
        return exactTaskTimingObservationCount;
    }
    /**
     * Number of Task windows that differ from their Job envelope.
     *
     * @return approximate task timing observation count
     */
    public int getModeledApproximateTaskTimingObservationCount() {
        return modeledApproximateTaskTimingObservationCount;
    }

    /**
     * Each VM's utilization is based on the union of its modeled Job intervals.
     *
     * <p>The busy intervals include the start-to-finish envelope of <b>failed attempts</b> (a
     * failed execution also occupies the VM's modeled resource window); overlapping intervals are
     * merged by union and not counted twice.</p>
     */
    public static final class VmMetrics {
        private final int vmId;
        private final int jobOutcomeCount;
        private final double reportedCpuTimeSeconds;
        private final double modeledBusyIntervalSeconds;
        private final double modeledIntervalUtilization;
        private final double reportedCpuTimeOverMakespan;

        private VmMetrics(
                int vmId,
                int jobOutcomeCount,
                double reportedCpuTimeSeconds,
                double modeledBusyIntervalSeconds,
                double modeledIntervalUtilization,
                double reportedCpuTimeOverMakespan) {
            this.vmId = vmId;
            this.jobOutcomeCount = jobOutcomeCount;
            this.reportedCpuTimeSeconds = reportedCpuTimeSeconds;
            this.modeledBusyIntervalSeconds = modeledBusyIntervalSeconds;
            this.modeledIntervalUtilization = modeledIntervalUtilization;
            this.reportedCpuTimeOverMakespan = reportedCpuTimeOverMakespan;
        }

        public int getVmId() {
            return vmId;
        }

        public int getJobOutcomeCount() {
            return jobOutcomeCount;
        }

        public double getReportedCpuTimeSeconds() {
            return reportedCpuTimeSeconds;
        }

        public double getModeledBusyIntervalSeconds() {
            return modeledBusyIntervalSeconds;
        }

        public double getModeledIntervalUtilization() {
            return modeledIntervalUtilization;
        }

        public double getReportedCpuTimeOverMakespan() {
            return reportedCpuTimeOverMakespan;
        }
    }

    private static final class MutableVmMetrics {
        private final int vmId;
        private final List<Interval> intervals = new ArrayList<Interval>();
        private int jobs;
        private double cpuTime;
        /**
         * BAT-D2 (2026-10-29): the VM's default-tier MIPS (first table entry); null = a v1 VM
         * without tiers (the whole VM stays out of the per-tier ledger). Resolved at construction
         * time from the platform declaration, read-only afterwards.
         */
        private final Double defaultTierMips;
        /** BAT-D2: tier MIPS -> busy seconds (TreeMap ascending keys -- deterministic traversal). */
        private final java.util.TreeMap<Double, Double> tierBusySeconds = new java.util.TreeMap<Double, Double>();

        private MutableVmMetrics(int vmId, Double defaultTierMips) {
            this.vmId = vmId;
            this.defaultTierMips = defaultTierMips;
        }

        private void add(SimulationReport.JobOutcome job) {
            jobs++;
            cpuTime += job.getCpuTime();
            intervals.add(new Interval(job.getStartTime(), job.getFinishTime()));
            // BAT-D2: per-tier attribution -- a Job without an assigned tier (a non-compute attempt)
            // uses the VM default tier; busy seconds share their source with the busy union (under a
            // single-Job SPACE_SHARED sequence the per-tier sum equals the union total busy seconds).
            if (defaultTierMips != null) {
                double tier =
                        job.getTierMips() != null ? job.getTierMips().doubleValue() : defaultTierMips.doubleValue();
                tierBusySeconds.merge(Double.valueOf(tier), Double.valueOf(job.getExecutionTime()), Double::sum);
            }
        }

        private VmMetrics freeze(double makespan) {
            double busy = unionLength(intervals);
            return new VmMetrics(
                    vmId,
                    jobs,
                    cpuTime,
                    busy,
                    makespan > 0.0 ? busy / makespan : 0.0,
                    makespan > 0.0 ? cpuTime / makespan : 0.0);
        }
    }

    private static final class Interval {
        private final double start;
        private final double finish;

        private Interval(double start, double finish) {
            this.start = start;
            this.finish = finish;
        }
    }

    private static double unionLength(List<Interval> intervals) {
        if (intervals.isEmpty()) {
            return 0.0;
        }
        List<Interval> sorted = new ArrayList<Interval>(intervals);
        Collections.sort(sorted, new Comparator<Interval>() {
            @Override
            public int compare(Interval left, Interval right) {
                return Double.compare(left.start, right.start);
            }
        });
        double start = sorted.get(0).start;
        double finish = Math.max(start, sorted.get(0).finish);
        double result = 0.0;
        for (int index = 1; index < sorted.size(); index++) {
            Interval interval = sorted.get(index);
            if (interval.start > finish) {
                result += nonNegative(finish - start);
                start = interval.start;
                finish = Math.max(interval.start, interval.finish);
            } else {
                finish = Math.max(finish, interval.finish);
            }
        }
        return result + nonNegative(finish - start);
    }

    private static double coefficientOfVariation(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Double value : values) {
            sum += value.doubleValue();
        }
        double average = sum / values.size();
        if (average == 0.0) {
            return 0.0;
        }
        double squared = 0.0;
        for (Double value : values) {
            double difference = value.doubleValue() - average;
            squared += difference * difference;
        }
        return Math.sqrt(squared / values.size()) / average;
    }

    /**
     * Nearest-rank percentile.
     *
     * <p>The samples must already be sorted ascending; rank = ceil(fraction x n), and
     * fraction=1.0 gives the maximum. An empty sample returns 0.0 (consistent with the empty-set
     * convention of the other metrics).</p>
     *
     * @param sortedSamples samples sorted in ascending order
     * @param fraction target quantile (0.5 median / 0.95 P95 / 1.0 maximum)
     * @return the corresponding quantile value
     */
    private static double percentile(List<Double> sortedSamples, double fraction) {
        if (sortedSamples.isEmpty()) {
            return 0.0;
        }
        int rank = (int) Math.ceil(fraction * sortedSamples.size());
        return sortedSamples.get(Math.max(1, rank) - 1).doubleValue();
    }

    /**
     * Jain fairness index: (sum u)^2 / (n x sum u^2).
     *
     * <p>Range (0,1]; 1.0 means all VM utilizations are equal. For the current identical
     * observation window, identical VM sample, and nonzero mean load, Jain = 1 / (1 + CV^2); the
     * two are not independent distribution-shape information. An empty list or all-zero
     * utilization returns 1.0 (a zero-load convention that does not mean the resources are fully
     * utilized).</p>
     *
     * @param utilizations utilization sample of each VM (>=0)
     * @return Jain fairness index
     */
    private static double jainFairnessIndex(List<Double> utilizations) {
        if (utilizations.isEmpty()) {
            return 1.0;
        }
        double sum = 0.0;
        double sumSquared = 0.0;
        for (Double value : utilizations) {
            double u = value.doubleValue();
            sum += u;
            sumSquared += u * u;
        }
        if (sumSquared <= 0.0) {
            return 1.0;
        }
        return (sum * sum) / (utilizations.size() * sumSquared);
    }

    /** Extract the modeled interval utilization of each VM for the fairness index computation. */
    private static List<Double> vmUtilizations(Map<Integer, VmMetrics> frozenVm) {
        List<Double> result = new ArrayList<Double>();
        for (VmMetrics metrics : frozenVm.values()) {
            result.add(Double.valueOf(metrics.getModeledIntervalUtilization()));
        }
        return result;
    }

    private static Set<Integer> logicalTaskIds(List<Task> sourceTasks, List<SimulationReport.TaskOutcome> outcomes) {
        Set<Integer> result = new HashSet<Integer>();
        if (sourceTasks != null) {
            for (Task task : sourceTasks) {
                if (task != null) {
                    result.add(task.getCloudletId());
                }
            }
        }
        if (result.isEmpty() && outcomes != null) {
            for (SimulationReport.TaskOutcome outcome : outcomes) {
                result.add(outcome.getTaskId());
            }
        }
        return result;
    }

    /** Build a unique ID index of Job outcomes so downstream metrics cannot silently overwrite attempts. */
    private static Map<Integer, SimulationReport.JobOutcome> jobOutcomesById(List<SimulationReport.JobOutcome> jobs) {
        Map<Integer, SimulationReport.JobOutcome> result = new LinkedHashMap<Integer, SimulationReport.JobOutcome>();
        if (jobs == null) {
            throw new IllegalArgumentException("Job outcomes cannot be null");
        }
        for (SimulationReport.JobOutcome job : jobs) {
            if (job == null) {
                throw new IllegalStateException("Simulation metrics received a null Job outcome");
            }
            if (result.put(job.getJobId(), job) != null) {
                throw new IllegalStateException(
                        "Simulation metrics received duplicate Job outcome ID " + job.getJobId());
            }
        }
        return result;
    }

    /**
     * Recover and validate the evidence relation between retry Jobs and their failed parent
     * attempt from retry creation events.
     *
     * <p>All completed Jobs are available when the report is captured, so any retry event without
     * a matching Job outcome means the evidence package is incomplete, not a partial observation
     * that may be ignored.</p>
     */
    private static Set<Integer> retryJobIds(
            List<SimulationEvent> events, Map<Integer, SimulationReport.JobOutcome> jobsById) {
        Set<Integer> result = new HashSet<Integer>();
        if (events == null) {
            throw new IllegalArgumentException("Simulation events cannot be null");
        }
        for (SimulationEvent event : events) {
            if (event == null) {
                throw new IllegalStateException("Simulation metrics received a null event");
            }
            if (event.getType() != SimulationEventType.RETRY_JOB_CREATED) {
                continue;
            }
            Integer retryJobId = event.getJobId();
            if (retryJobId == null) {
                throw new IllegalStateException("Retry creation event is missing its retry Job ID");
            }
            if (!result.add(retryJobId)) {
                throw new IllegalStateException("Retry creation events reuse Job ID " + retryJobId);
            }
            SimulationReport.JobOutcome retry = jobsById.get(retryJobId);
            if (retry == null) {
                throw new IllegalStateException("Retry creation event has no completed Job outcome for " + retryJobId);
            }
            if (retry.getClassType() != Parameters.ClassType.COMPUTE.value) {
                throw new IllegalStateException("Retry Job " + retryJobId + " is not a compute Job outcome");
            }
            int failedJobId = requiredIntegerAttribute(event, "failedJobId", "Retry creation event");
            if (failedJobId == retryJobId.intValue()) {
                throw new IllegalStateException("Retry Job " + retryJobId + " cannot be its own failed parent");
            }
            SimulationReport.JobOutcome failed = jobsById.get(Integer.valueOf(failedJobId));
            if (failed == null) {
                throw new IllegalStateException(
                        "Retry Job " + retryJobId + " references missing failed Job " + failedJobId);
            }
            if (failed.getStatus() != Cloudlet.FAILED) {
                throw new IllegalStateException(
                        "Retry Job " + retryJobId + " references a parent that is not failed: " + failedJobId);
            }
        }
        return result;
    }

    /** Use the completion time of a successful compute Job as the available completion boundary of a logical Task. */
    private static Map<Integer, Double> earliestSuccessfulJobFinishByLogicalTask(
            List<SimulationReport.JobOutcome> jobs, Set<Integer> logicalTaskIds) {
        Map<Integer, Double> result = new LinkedHashMap<Integer, Double>();
        for (SimulationReport.JobOutcome job : jobs) {
            if (job.getClassType() != Parameters.ClassType.COMPUTE.value || job.getStatus() != Cloudlet.SUCCESS) {
                continue;
            }
            for (Integer taskId : job.getTaskIds()) {
                if (!logicalTaskIds.contains(taskId)) {
                    continue;
                }
                Double previous = result.get(taskId);
                if (previous == null || job.getFinishTime() < previous.doubleValue()) {
                    result.put(taskId, Double.valueOf(job.getFinishTime()));
                }
            }
        }
        return result;
    }

    private static double latestFinish(Map<Integer, Double> finishes) {
        if (finishes.isEmpty()) {
            throw new IllegalStateException("Cannot calculate logical completion from no successful Tasks");
        }
        double result = Double.NEGATIVE_INFINITY;
        for (Double finish : finishes.values()) {
            if (finish == null || Double.isNaN(finish.doubleValue()) || Double.isInfinite(finish.doubleValue())) {
                throw new IllegalStateException("Logical Task completion evidence has invalid Job finish time");
            }
            result = Math.max(result, finish.doubleValue());
        }
        return result;
    }

    private static int logicalTaskAttemptCount(
            List<SimulationReport.TaskOutcome> outcomes,
            Map<Integer, SimulationReport.JobOutcome> jobsById,
            Set<Integer> logicalTaskIds) {
        int result = 0;
        if (outcomes == null) {
            throw new IllegalArgumentException("Task outcomes cannot be null");
        }
        for (SimulationReport.TaskOutcome outcome : outcomes) {
            if (outcome == null) {
                throw new IllegalStateException("Simulation metrics received a null Task outcome");
            }
            SimulationReport.JobOutcome job = jobsById.get(Integer.valueOf(outcome.getJobId()));
            if (job == null) {
                throw new IllegalStateException("Task outcome " + outcome.getTaskId()
                        + " references missing Job outcome " + outcome.getJobId());
            }
            if (job.getClassType() == Parameters.ClassType.COMPUTE.value
                    && logicalTaskIds.contains(Integer.valueOf(outcome.getTaskId()))) {
                result++;
            }
        }
        return result;
    }

    private static int retriedLogicalTaskCount(
            Set<Integer> retryJobIds, Map<Integer, SimulationReport.JobOutcome> jobsById, Set<Integer> logicalTaskIds) {
        Set<Integer> result = new HashSet<Integer>();
        for (Integer retryJobId : retryJobIds) {
            SimulationReport.JobOutcome retry = jobsById.get(retryJobId);
            for (Integer taskId : retry.getTaskIds()) {
                if (logicalTaskIds.contains(taskId)) {
                    result.add(taskId);
                }
            }
        }
        return result.size();
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : ((double) numerator) / denominator;
    }

    private static double requiredFiniteNonNegativeAttribute(SimulationEvent event, String key) {
        Object value = event.getAttributes().get(key);
        if (!(value instanceof Number)) {
            throw new IllegalStateException("Data-stage-in event is missing numeric " + key);
        }
        double number = ((Number) value).doubleValue();
        if (number < 0.0 || Double.isNaN(number) || Double.isInfinite(number)) {
            throw new IllegalStateException("Data-stage-in event has invalid " + key);
        }
        return number;
    }

    private static int requiredNonNegativeIntegerAttribute(SimulationEvent event, String key) {
        double value = requiredFiniteNonNegativeAttribute(event, key);
        if (value > Integer.MAX_VALUE || Math.rint(value) != value) {
            throw new IllegalStateException("Data-stage-in event has invalid integer " + key);
        }
        return (int) value;
    }

    private static int requiredIntegerAttribute(SimulationEvent event, String key, String subject) {
        Object value = event.getAttributes().get(key);
        if (!(value instanceof Number)) {
            throw new IllegalStateException(subject + " is missing numeric " + key);
        }
        double number = ((Number) value).doubleValue();
        if (number < Integer.MIN_VALUE
                || number > Integer.MAX_VALUE
                || Math.rint(number) != number
                || Double.isNaN(number)
                || Double.isInfinite(number)) {
            throw new IllegalStateException(subject + " has invalid integer " + key);
        }
        return (int) number;
    }

    private static long requiredNonNegativeLongAttribute(SimulationEvent event, String key) {
        Object value = event.getAttributes().get(key);
        if (!(value instanceof Number)) {
            throw new IllegalStateException("Planning event is missing numeric " + key);
        }
        double number = ((Number) value).doubleValue();
        if (number < 0.0
                || number > Long.MAX_VALUE
                || Math.rint(number) != number
                || Double.isNaN(number)
                || Double.isInfinite(number)) {
            throw new IllegalStateException("Planning event has invalid " + key);
        }
        return ((Number) value).longValue();
    }

    private static double mean(double sum, int count) {
        return count == 0 ? 0.0 : sum / count;
    }

    private static String deadlineOutcome(boolean enabled, boolean workflowCompleted, int exactSlackSign) {
        if (!enabled) {
            return "NOT_REQUESTED";
        }
        if (!workflowCompleted) {
            return "MISSED_INCOMPLETE_WORKFLOW";
        }
        return exactSlackSign >= 0 ? "MET" : "MISSED_LATE";
    }

    private static double nonNegative(double value) {
        return value < 0.0 ? 0.0 : value;
    }

    /**
     * Require the value to be finite (not NaN, not infinite), otherwise throw
     * IllegalStateException.
     *
     * @param value the number to check
     * @param description description of the value, used in the error message
     * @throws IllegalStateException when the value is NaN or infinite
     */
    private static void requireFinite(double value, String description) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalStateException(description + " must be finite, was: " + value);
        }
    }
}
