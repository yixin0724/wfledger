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
    /**
     * Mean VM submission-to-start wait of compute Jobs.
     *
     * <p>FGCS-R6 (2026-11-25): identically 0.0 under the idle-only dispatch invariant (a Job is submitted only
     * to an idle VM, and {@code WorkflowDatacenter} fails fast otherwise). A {@code queueDelays} overhead delays
     * the submit event itself, so it is visible in decision-to-start and ready-to-start, never here. Kept for
     * schema stability; {@code meanComputeTotalWaitingTimeSeconds} (ready to start) is the queueing measure.
     */
    private final double meanJobVmQueueWaitingTimeSeconds;

    /**
     * Mean VM submission-to-finish time of compute Jobs.
     *
     * <p>FGCS-R6 (2026-11-25): equals {@code meanComputeJobRunTimeSeconds} under the idle-only dispatch
     * invariant (see {@code meanJobVmQueueWaitingTimeSeconds}); kept for schema stability, not a response
     * measure that includes queueing.
     */
    private final double meanJobResponseTimeSeconds;

    private final double meanJobVmLevelSlowdown;

    private final int vmLevelSlowdownObservationCount;

    private final double meanComputeTotalWaitingTimeSeconds;
    private final double meanComputeTrueSlowdown;
    private final int trueSlowdownObservationCount;

    /**
     * Bounded-slowdown floor {@code tau} applied to every {@code meanComputeTrueSlowdown}-family sample.
     *
     * <p>FGCS-R6 (2026-11-25): root cause - the floor was a hidden constant of 10 s (Feitelson 1997), while
     * 48-76% of Pegasus Tasks run shorter than 10 s, so the metric degenerated to {@code wait / 10} for them
     * and algorithm rankings flipped with the floor. The floor is now the declared configuration value.
     */
    private final double boundedSlowdownThresholdSeconds;

    /** FGCS-R6 (2026-11-25): slowdown samples whose execution envelope is shorter than the floor. */
    private final int boundedSlowdownFloorClampedObservationCount;

    /**
     * FGCS-R6 (2026-11-25): {@code boundedSlowdownFloorClampedObservationCount / trueSlowdownObservationCount},
     * 0.0 when there is no sample. A high value means the slowdown family mostly reports {@code wait / tau}.
     */
    private final double boundedSlowdownFloorClampedFraction;

    private final int totalWaitingTimeObservationCount;

    private final double medianComputeTotalWaitingTimeSeconds;

    private final double p95ComputeTotalWaitingTimeSeconds;

    private final double maxComputeTotalWaitingTimeSeconds;

    private final double medianComputeTrueSlowdown;

    private final double p95ComputeTrueSlowdown;

    private final double maxComputeTrueSlowdown;

    private final double successOnlyMeanComputeTotalWaitingTimeSeconds;

    private final double successOnlyMeanComputeTrueSlowdown;

    private final int successOnlyWaitingObservationCount;

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

    private final double vmUtilizationJainFairnessIndex;

    /**
     * FGCS-R6 (2026-11-25): seconds of input reading folded into Job execution envelopes, summed over every
     * Job outcome that has a {@code JOB_EXECUTION_STARTED} event ({@code effectiveDataStageSeconds}).
     *
     * <p>Root cause: legacy/fixed-endpoint models add the input-read seconds to the Job length, so busy
     * time, utilisation and the linear energy model charge them at {@code P_max} as CPU-active time (about
     * 89% of the busy time of CyberShake_50 under SHARED storage). The energy formula is unchanged; this
     * field exposes the I/O part of the busy time so that the share can be reported.
     */
    private final double totalInputReadSecondsInEnvelope;

    /** FGCS-R6 (2026-11-25): {@code totalVmModeledBusyIntervalSeconds - totalInputReadSecondsInEnvelope}. */
    private final double totalComputeOnlyBusySeconds;

    /** FGCS-R6 (2026-11-25): {@code totalInputReadSecondsInEnvelope / totalVmModeledBusyIntervalSeconds}. */
    private final double inputReadShareOfBusyTime;

    /**
     * FGCS-R6 (2026-11-25): Job outcomes covered by a {@code JOB_EXECUTION_STARTED} event. When it is below
     * {@code jobOutcomeCount} (event recording disabled) the read decomposition is unavailable, not zero.
     */
    private final int inputReadInEnvelopeObservationCount;

    private final transient Map<Integer, Map<Double, Double>> perTierBusySecondsByVm;

    private final transient Map<Double, Double> fleetPerTierBusySeconds;

    private final boolean controlledSharedStorageCriticalPathReferenceAvailable;
    private final String controlledSharedStorageCriticalPathReferenceScope;
    private final double controlledSharedStorageCriticalPathLowerBoundSeconds;
    /**
     * Makespan divided by the I/O-inclusive critical-path lower bound.
     *
     * <p>FGCS-R6 (2026-11-25): the denominator includes the model-generated stage-in Job and each Task's
     * SHARED external-input read seconds (see {@link WorkflowModelReference}), so this is an "I/O-inclusive
     * CP lower-bound SLR", not the compute-only SLR of Topcuoglu et al. (TPDS 2002). The field name is kept
     * for schema stability.
     */
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
            Map<Double, Double> fleetPerTierBusySeconds,
            R6Observations r6) {
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
        this.boundedSlowdownThresholdSeconds = r6.boundedSlowdownThresholdSeconds;
        this.totalInputReadSecondsInEnvelope = r6.totalInputReadSeconds;
        this.totalComputeOnlyBusySeconds = totalVmModeledBusyIntervalSeconds - r6.totalInputReadSeconds;
        this.inputReadShareOfBusyTime = totalVmModeledBusyIntervalSeconds > 0.0
                ? r6.totalInputReadSeconds / totalVmModeledBusyIntervalSeconds
                : 0.0;
        this.inputReadInEnvelopeObservationCount = r6.inputReadObservationCount;
        this.boundedSlowdownFloorClampedObservationCount = r6.clampedSlowdownCount;
        this.boundedSlowdownFloorClampedFraction = ratio(r6.clampedSlowdownCount, trueSlowdownObservationCount);
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

        this.perTierBusySecondsByVm =
                Collections.unmodifiableMap(new LinkedHashMap<Integer, Map<Double, Double>>(perTierBusySecondsByVm));
        this.fleetPerTierBusySeconds =
                Collections.unmodifiableMap(new java.util.TreeMap<Double, Double>(fleetPerTierBusySeconds));
    }

    /**
     * FGCS-R6 (2026-11-25): additive observations introduced by the R6 metric audit, grouped so that the
     * positional constructor does not grow one argument per field.
     */
    private static final class R6Observations {
        double boundedSlowdownThresholdSeconds;
        int clampedSlowdownCount;
        double totalInputReadSeconds;
        int inputReadObservationCount;
    }

    static SimulationMetrics calculate(
            double makespan,
            List<SimulationReport.JobOutcome> jobs,
            Map<Integer, SimulationReport.VmSummary> summaries,
            List<SimulationEvent> events,
            List<SimulationReport.TaskOutcome> taskOutcomes,
            List<Task> sourceTasks,
            SimulationConfig config,
            PlatformProfile platform) {

        requireFinite(makespan, "makespan");

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
        JobAccumulators acc =
                accumulateJobOutcomes(jobs, retryJobIds, timing, vm, config.getBoundedSlowdownThresholdSeconds());

        return assembleMetrics(
                makespan, jobs, taskOutcomes, sourceTasks, config, platform, jobsById, retryJobIds, timing, vm, acc);
    }

    private static JobAccumulators accumulateJobOutcomes(
            List<SimulationReport.JobOutcome> jobs,
            Set<Integer> retryJobIds,
            EventTiming timing,
            Map<Integer, MutableVmMetrics> vm,
            double boundedSlowdownThresholdSeconds) {
        JobAccumulators acc = new JobAccumulators();
        acc.boundedSlowdownThresholdSeconds = boundedSlowdownThresholdSeconds;
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
            Double readSeconds = timing.envelopeInputReadSeconds.get(job.getJobId());
            if (readSeconds != null) {
                acc.inputReadObservationCount++;
                acc.totalInputReadSeconds += readSeconds.doubleValue();
            }
            if (perVm != null) {
                perVm.add(job, readSeconds == null ? 0.0 : readSeconds.doubleValue());
            }

            if (job.getClassType() == Parameters.ClassType.COMPUTE.value) {
                accumulateComputeJob(job, acc, retryJobIds, timing);
            } else if (job.getClassType() == Parameters.ClassType.STAGE_IN.value) {
                acc.stageIn++;
            }
        }
        return acc;
    }

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

        acc.jobWaitingTimeSum += job.getWaitingTime();
        acc.jobResponseTimeSum += job.getResponseTime();

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

        if (ready != null && job.getStartTime() >= ready.doubleValue()) {
            double trueWait = job.getStartTime() - ready.doubleValue();
            double execTime = nonNegative(job.getFinishTime() - job.getStartTime());

            double boundedExec = Math.max(execTime, acc.boundedSlowdownThresholdSeconds);
            double trueSlowdown = Math.max((trueWait + execTime) / boundedExec, 1.0);
            if (execTime < acc.boundedSlowdownThresholdSeconds) {
                acc.clampedSlowdownCount++;
            }

            acc.totalWaitingTimeSum += trueWait;
            acc.totalWaitingTimeCount++;
            acc.totalWaitingSamples.add(Double.valueOf(trueWait));
            acc.trueSlowdownSum += trueSlowdown;
            acc.trueSlowdownCount++;
            acc.trueSlowdownSamples.add(Double.valueOf(trueSlowdown));

            if (job.getStatus() == Cloudlet.SUCCESS) {
                acc.successOnlyTotalWaitingSum += trueWait;
                acc.successOnlyTrueSlowdownSum += trueSlowdown;
                acc.successOnlyWaitingObservationCount++;
            }
        }
    }

    private static final class JobAccumulators {
        double boundedSlowdownThresholdSeconds;
        int clampedSlowdownCount;
        double totalInputReadSeconds;
        int inputReadObservationCount;
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

    private static FrozenVms freezeVmMetrics(double makespan, Map<Integer, MutableVmMetrics> vm) {
        Map<Integer, VmMetrics> frozenVm = new LinkedHashMap<Integer, VmMetrics>();
        List<Double> busyTimes = new ArrayList<Double>();
        double totalBusyInterval = 0.0;

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

    private static final class FrozenVms {
        final Map<Integer, VmMetrics> frozenVm;
        final List<Double> busyTimes;
        final double totalBusyInterval;

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

    private static DeadlineResults evaluateDeadline(
            SimulationConfig config,
            double makespan,
            List<SimulationReport.TaskOutcome> taskOutcomes,
            String logicalTaskCompletionStatus) {
        boolean workflowCompleted = "COMPLETED_SUCCESSFULLY".equals(logicalTaskCompletionStatus);
        boolean deadlineEnabled = config.getDeadline() > 0L;

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

        double vmUtilizationJainFairnessIndex = jainFairnessIndex(vmUtilizations(frozen.frozenVm));

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
                frozen.perTierBusySecondsByVm,
                frozen.fleetPerTierBusySeconds,
                r6Observations(acc));
    }

    private static R6Observations r6Observations(JobAccumulators acc) {
        R6Observations r6 = new R6Observations();
        r6.boundedSlowdownThresholdSeconds = acc.boundedSlowdownThresholdSeconds;
        r6.clampedSlowdownCount = acc.clampedSlowdownCount;
        r6.totalInputReadSeconds = acc.totalInputReadSeconds;
        r6.inputReadObservationCount = acc.inputReadObservationCount;
        return r6;
    }

    private static EventTiming scanTimingEvents(List<SimulationEvent> events) {
        EventTiming timing = new EventTiming();
        for (SimulationEvent event : events) {
            if (event.getType() == SimulationEventType.JOB_READY && event.getJobId() != null) {

                Double previousReady = timing.readyTimes.put(event.getJobId(), event.getSimulationTime());
                if (previousReady != null) {
                    throw new IllegalStateException("Duplicate JOB_READY event for job "
                            + event.getJobId() + ": ready time was already recorded at "
                            + previousReady + " but a second event arrived at "
                            + event.getSimulationTime());
                }
            } else if (event.getType() == SimulationEventType.SCHEDULING_DECISION && event.getJobId() != null) {

                Double previousDecision = timing.decisionTimes.put(event.getJobId(), event.getSimulationTime());
                if (previousDecision != null) {
                    throw new IllegalStateException("Duplicate SCHEDULING_DECISION event for job "
                            + event.getJobId() + ": decision time was already recorded at "
                            + previousDecision + " but a second event arrived at "
                            + event.getSimulationTime());
                }
            } else if (event.getType() == SimulationEventType.JOB_EXECUTION_STARTED && event.getJobId() != null) {
                double read = requiredFiniteNonNegativeAttribute(event, "effectiveDataStageSeconds");
                if (timing.envelopeInputReadSeconds.put(event.getJobId(), Double.valueOf(read)) != null) {
                    throw new IllegalStateException(
                            "Duplicate JOB_EXECUTION_STARTED event for job " + event.getJobId());
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

    private static final class EventTiming {
        final Map<Integer, Double> readyTimes = new LinkedHashMap<Integer, Double>();
        final Map<Integer, Double> decisionTimes = new LinkedHashMap<Integer, Double>();
        final Map<Integer, Double> envelopeInputReadSeconds = new LinkedHashMap<Integer, Double>();
        long totalSchedulingNanos;
        int schedulingCycles;
        long totalPlanningNanos;
        int explicitPlannerDecisions;
        int dataStageInObservations;
        int modeledTransferFiles;
        double totalTransferSeconds;
        double totalRequiredInputBytes;
    }

    public double getMakespanSeconds() {
        return makespanSeconds;
    }

    public double getSimulationEndSeconds() {
        return makespanSeconds;
    }

    public Double getLogicalTaskCompletionSeconds() {
        return logicalTaskCompletionSeconds;
    }

    public String getLogicalTaskCompletionStatus() {
        return logicalTaskCompletionStatus;
    }

    public Double getTerminalLifecycleTailSeconds() {
        return terminalLifecycleTailSeconds;
    }

    public int getCompletedJobAttemptCount() {
        return jobOutcomeCount;
    }

    public int getJobOutcomeCount() {
        return jobOutcomeCount;
    }

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

    public double getSuccessfulLogicalTaskCompletionRate() {
        return successfulLogicalTaskCompletionRate;
    }

    public boolean isAllLogicalTasksCompletedSuccessfully() {
        return allLogicalTasksCompletedSuccessfully;
    }

    public boolean isWorkflowCompletedSuccessfully() {
        return allLogicalTasksCompletedSuccessfully;
    }

    public int getInitialComputeJobOutcomeCount() {
        return initialComputeJobOutcomeCount;
    }

    public int getRetryJobCreatedCount() {
        return retryJobCreatedCount;
    }

    public int getCompletedRetryComputeJobOutcomeCount() {
        return completedRetryComputeJobOutcomeCount;
    }

    public int getLogicalTaskAttemptCount() {
        return logicalTaskAttemptCount;
    }

    public int getRetriedLogicalTaskCount() {
        return retriedLogicalTaskCount;
    }

    public double getFailedComputeAttemptEnvelopeSeconds() {
        return failedComputeAttemptEnvelopeSeconds;
    }

    public double getFailedComputeAttemptModeledProcessingCost() {
        return failedComputeAttemptModeledProcessingCost;
    }

    public double getRetryComputeAttemptModeledProcessingCost() {
        return retryComputeAttemptModeledProcessingCost;
    }

    public double getComputeJobOutcomeThroughputPerSecond() {
        return computeJobOutcomeThroughputPerSecond;
    }

    public double getMeanComputeJobRunTimeSeconds() {
        return meanComputeJobRunTimeSeconds;
    }

    public double getMeanJobVmQueueWaitingTimeSeconds() {
        return meanJobVmQueueWaitingTimeSeconds;
    }

    public double getMeanJobResponseTimeSeconds() {
        return meanJobResponseTimeSeconds;
    }

    public double getMeanJobVmLevelSlowdown() {
        return meanJobVmLevelSlowdown;
    }

    public int getVmLevelSlowdownObservationCount() {
        return vmLevelSlowdownObservationCount;
    }

    public double getMeanComputeTotalWaitingTimeSeconds() {
        return meanComputeTotalWaitingTimeSeconds;
    }

    public double getMeanComputeTrueSlowdown() {
        return meanComputeTrueSlowdown;
    }

    public int getTrueSlowdownObservationCount() {
        return trueSlowdownObservationCount;
    }

    public double getBoundedSlowdownThresholdSeconds() {
        return boundedSlowdownThresholdSeconds;
    }

    public int getBoundedSlowdownFloorClampedObservationCount() {
        return boundedSlowdownFloorClampedObservationCount;
    }

    public double getBoundedSlowdownFloorClampedFraction() {
        return boundedSlowdownFloorClampedFraction;
    }

    public int getTotalWaitingTimeObservationCount() {
        return totalWaitingTimeObservationCount;
    }

    public double getMedianComputeTotalWaitingTimeSeconds() {
        return medianComputeTotalWaitingTimeSeconds;
    }

    public double getP95ComputeTotalWaitingTimeSeconds() {
        return p95ComputeTotalWaitingTimeSeconds;
    }

    public double getMaxComputeTotalWaitingTimeSeconds() {
        return maxComputeTotalWaitingTimeSeconds;
    }

    public double getMedianComputeTrueSlowdown() {
        return medianComputeTrueSlowdown;
    }

    public double getP95ComputeTrueSlowdown() {
        return p95ComputeTrueSlowdown;
    }

    public double getMaxComputeTrueSlowdown() {
        return maxComputeTrueSlowdown;
    }

    public double getSuccessOnlyMeanComputeTotalWaitingTimeSeconds() {
        return successOnlyMeanComputeTotalWaitingTimeSeconds;
    }

    public double getSuccessOnlyMeanComputeTrueSlowdown() {
        return successOnlyMeanComputeTrueSlowdown;
    }

    public int getSuccessOnlyWaitingObservationCount() {
        return successOnlyWaitingObservationCount;
    }

    public double getRetryAmplificationRatio() {
        return retryAmplificationRatio;
    }

    public double getMeanComputeReadyToDecisionDelaySeconds() {
        return meanComputeReadyToDecisionDelaySeconds;
    }

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

    public long getTotalSchedulingDecisionWallClockNanos() {
        return totalSchedulingDecisionWallClockNanos;
    }

    public int getExplicitPlannerDecisionObservationCount() {
        return explicitPlannerDecisionObservationCount;
    }

    public long getTotalPlanningDecisionWallClockNanos() {
        return totalPlanningDecisionWallClockNanos;
    }

    public int getDataStageInModelObservationCount() {
        return dataStageInModelObservationCount;
    }

    public int getModeledDataTransferFileCount() {
        return modeledDataTransferFileCount;
    }

    public int getModeledRequiredInputDemandFileCount() {
        return modeledDataTransferFileCount;
    }

    public double getTotalModeledDataTransferSeconds() {
        return totalModeledDataTransferSeconds;
    }

    public double getTotalModeledRequiredInputBytes() {
        return totalModeledRequiredInputBytes;
    }

    public double getTotalModeledRequiredInputDemandBytes() {
        return totalModeledRequiredInputBytes;
    }

    public double getMeanModeledDataTransferSeconds() {
        return meanModeledDataTransferSeconds;
    }

    public double getTotalModeledProcessingCost() {
        return totalModeledProcessingCost;
    }

    public double getTotalModeledCpuEnvelopeCost() {
        return totalModeledCpuEnvelopeCost;
    }

    public double getTotalModeledDeclaredFileBandwidthCost() {
        return totalModeledDeclaredFileBandwidthCost;
    }

    public double getTotalModeledDeclaredFileBytes() {
        return totalModeledDeclaredFileBytes;
    }

    public Map<Integer, VmMetrics> getVmMetrics() {
        return vmMetrics;
    }

    public double getTotalVmModeledBusyIntervalSeconds() {
        return totalVmModeledBusyIntervalSeconds;
    }

    public double getMeanVmModeledIntervalUtilization() {
        return meanVmModeledIntervalUtilization;
    }

    public double getVmModeledBusyTimeCoefficientOfVariation() {
        return vmModeledBusyTimeCoefficientOfVariation;
    }

    public double getVmUtilizationJainFairnessIndex() {
        return vmUtilizationJainFairnessIndex;
    }

    public double getTotalInputReadSecondsInEnvelope() {
        return totalInputReadSecondsInEnvelope;
    }

    public double getTotalComputeOnlyBusySeconds() {
        return totalComputeOnlyBusySeconds;
    }

    public double getInputReadShareOfBusyTime() {
        return inputReadShareOfBusyTime;
    }

    public int getInputReadInEnvelopeObservationCount() {
        return inputReadInEnvelopeObservationCount;
    }

    public Map<Integer, Map<Double, Double>> getPerTierBusySecondsByVm() {
        return perTierBusySecondsByVm;
    }

    public Map<Double, Double> getFleetPerTierBusySeconds() {
        return fleetPerTierBusySeconds;
    }

    public boolean isControlledSharedStorageCriticalPathReferenceAvailable() {
        return controlledSharedStorageCriticalPathReferenceAvailable;
    }

    public String getControlledSharedStorageCriticalPathReferenceScope() {
        return controlledSharedStorageCriticalPathReferenceScope;
    }

    public double getControlledSharedStorageCriticalPathLowerBoundSeconds() {
        return controlledSharedStorageCriticalPathLowerBoundSeconds;
    }

    public double getControlledSharedStorageScheduleLengthRatio() {
        return controlledSharedStorageScheduleLengthRatio;
    }

    public boolean isDeadlineObservationEnabled() {
        return deadlineObservationEnabled;
    }

    public String getDeadlineObservationTimeBasis() {
        return deadlineObservationTimeBasis;
    }

    public String getDeadlineObservationOutcome() {
        return deadlineObservationOutcome;
    }

    public boolean isDeadlineMet() {
        return deadlineMet;
    }

    public double getDeadlineSlackSeconds() {
        return deadlineSlackSeconds;
    }

    public double getDeadlineTardinessSeconds() {
        return deadlineTardinessSeconds;
    }

    public int getExactTaskTimingObservationCount() {
        return exactTaskTimingObservationCount;
    }

    public int getModeledApproximateTaskTimingObservationCount() {
        return modeledApproximateTaskTimingObservationCount;
    }

    public static final class VmMetrics {
        private final int vmId;
        private final int jobOutcomeCount;
        private final double reportedCpuTimeSeconds;
        private final double modeledBusyIntervalSeconds;
        private final double modeledIntervalUtilization;
        private final double reportedCpuTimeOverMakespan;

        /** FGCS-R6 (2026-11-25): input-read seconds folded into this VM's Job envelopes. */
        private final double inputReadSecondsInEnvelope;

        /** FGCS-R6 (2026-11-25): {@code modeledBusyIntervalSeconds - inputReadSecondsInEnvelope}. */
        private final double computeOnlyBusySeconds;

        private VmMetrics(
                int vmId,
                int jobOutcomeCount,
                double reportedCpuTimeSeconds,
                double modeledBusyIntervalSeconds,
                double modeledIntervalUtilization,
                double reportedCpuTimeOverMakespan,
                double inputReadSecondsInEnvelope) {
            this.vmId = vmId;
            this.jobOutcomeCount = jobOutcomeCount;
            this.reportedCpuTimeSeconds = reportedCpuTimeSeconds;
            this.modeledBusyIntervalSeconds = modeledBusyIntervalSeconds;
            this.modeledIntervalUtilization = modeledIntervalUtilization;
            this.reportedCpuTimeOverMakespan = reportedCpuTimeOverMakespan;
            this.inputReadSecondsInEnvelope = inputReadSecondsInEnvelope;
            this.computeOnlyBusySeconds = modeledBusyIntervalSeconds - inputReadSecondsInEnvelope;
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

        public double getInputReadSecondsInEnvelope() {
            return inputReadSecondsInEnvelope;
        }

        public double getComputeOnlyBusySeconds() {
            return computeOnlyBusySeconds;
        }
    }

    private static final class MutableVmMetrics {
        private final int vmId;
        private final List<Interval> intervals = new ArrayList<Interval>();
        private int jobs;
        private double cpuTime;
        private double inputReadSeconds;

        private final Double defaultTierMips;

        private final java.util.TreeMap<Double, Double> tierBusySeconds = new java.util.TreeMap<Double, Double>();

        private MutableVmMetrics(int vmId, Double defaultTierMips) {
            this.vmId = vmId;
            this.defaultTierMips = defaultTierMips;
        }

        private void add(SimulationReport.JobOutcome job, double readSeconds) {
            jobs++;
            inputReadSeconds += readSeconds;
            cpuTime += job.getCpuTime();
            intervals.add(new Interval(job.getStartTime(), job.getFinishTime()));

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
                    makespan > 0.0 ? cpuTime / makespan : 0.0,
                    inputReadSeconds);
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

    private static double percentile(List<Double> sortedSamples, double fraction) {
        if (sortedSamples.isEmpty()) {
            return 0.0;
        }
        int rank = (int) Math.ceil(fraction * sortedSamples.size());
        return sortedSamples.get(Math.max(1, rank) - 1).doubleValue();
    }

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

    private static void requireFinite(double value, String description) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalStateException(description + " must be finite, was: " + value);
        }
    }
}
