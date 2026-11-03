package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.cloudbus.cloudsim.Cloudlet;
import org.junit.jupiter.api.Test;
import org.workflowsim.utils.Parameters;

/**
 * Verifies that the job VM queue waiting time / response time / slowdown ratio metrics are computed
 * correctly.
 *
 * <p>Timeline model (VM queue waiting):
 * <pre>
 * submissionTime -> startTime -> finishTime
 *       |&lt;-vmQueue-&gt;|&lt;-execution-&gt;|
 *       |&lt;------response--------&gt;|
 * </pre>
 * <p><strong>Note:</strong> this test verifies the VM queue waiting time
 * ({@code getMeanJobVmQueueWaitingTimeSeconds()}), which is usually 0 in WorkflowSim.
 * The real scheduling waiting time is measured by {@code getMeanComputeTotalWaitingTimeSeconds()}
 * and requires JOB_READY event support.
 */
class JobWaitingTimeMetricsTest {

    @Test
    void jobOutcomeDerivesWaitingExecutionAndResponseTimes() {
        // submission=2.0, start=5.0, finish=15.0
        SimulationReport.JobOutcome job = job(1, 2.0, 5.0, 15.0);

        assertEquals(2.0, job.getSubmissionTime(), 1e-9);
        assertEquals(3.0, job.getWaitingTime(), 1e-9); // 5 - 2
        assertEquals(10.0, job.getExecutionTime(), 1e-9); // 15 - 5
        assertEquals(13.0, job.getResponseTime(), 1e-9); // 15 - 2
    }

    @Test
    void negativeIntervalsAreClampedToZeroForIncompleteJobs() {
        // Anomalous timestamps (e.g. a failed job with finishTime < startTime) must not yield negatives
        SimulationReport.JobOutcome job = job(1, 10.0, 5.0, 3.0);

        assertEquals(0.0, job.getWaitingTime(), 1e-9);
        assertEquals(0.0, job.getExecutionTime(), 1e-9);
        assertEquals(0.0, job.getResponseTime(), 1e-9);
    }

    @Test
    void zeroSubmissionTimeYieldsWaitingEqualToStartTime() {
        // submissionTime = 0 (submitted at simulation start)
        SimulationReport.JobOutcome job = job(1, 0.0, 4.0, 9.0);

        assertEquals(4.0, job.getWaitingTime(), 1e-9);
        assertEquals(9.0, job.getResponseTime(), 1e-9);
    }

    @Test
    void metricsAggregateMeanWaitingResponseAndSlowdown() {
        // Job A: submission 0, start 2, finish 6 -> waiting=2, response=6, exec=4, slowdown=1.5
        // Job B: submission 1, start 5, finish 9 -> waiting=4, response=8, exec=4, slowdown=2.0
        java.util.List<SimulationReport.JobOutcome> jobs =
                java.util.Arrays.asList(job(10, 0.0, 2.0, 6.0), job(11, 1.0, 5.0, 9.0));
        java.util.List<SimulationReport.TaskOutcome> tasks =
                java.util.Arrays.asList(task(1, 10, 0.0, 6.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Arrays.asList(new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics = SimulationMetrics.calculate(
                9.0,
                jobs,
                summaries(),
                Collections.<SimulationEvent>emptyList(),
                tasks,
                sourceTasks,
                config(),
                platform());

        assertEquals(3.0, metrics.getMeanJobVmQueueWaitingTimeSeconds(), 1e-9); // (2+4)/2
        assertEquals(7.0, metrics.getMeanJobResponseTimeSeconds(), 1e-9); // (6+8)/2
        assertEquals(1.75, metrics.getMeanJobVmLevelSlowdown(), 1e-9); // (1.5+2.0)/2
    }

    @Test
    void slowdownExcludesJobsWithNearZeroExecutionTime() {
        // Job A: exec=4, slowdown=1.5; job B: exec=0 (excluded)
        java.util.List<SimulationReport.JobOutcome> jobs =
                java.util.Arrays.asList(job(10, 0.0, 2.0, 6.0), job(11, 1.0, 5.0, 5.0)); // exec = 0
        java.util.List<SimulationReport.TaskOutcome> tasks =
                java.util.Arrays.asList(task(1, 10, 0.0, 6.0), task(2, 11, 0.0, 5.0));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Arrays.asList(new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics = SimulationMetrics.calculate(
                6.0,
                jobs,
                summaries(),
                Collections.<SimulationEvent>emptyList(),
                tasks,
                sourceTasks,
                config(),
                platform());

        assertEquals(1.5, metrics.getMeanJobVmLevelSlowdown(), 1e-9); // job A only
    }

    @Test
    void trueSlowdownUsesReadyTimeFromEvents() {
        // Job A: ready=0, start=3, finish=13 -> wait=3, exec=10, boundedExec=10, trueSlowdown=(3+10)/10=1.3
        // Job B: ready=1, start=5, finish=9 -> wait=4, exec=4, boundedExec=10, trueSlowdown=(4+4)/10=0.8
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Arrays.asList(
                job(10, 2.0, 3.0, 13.0), // submission=2 (does not affect trueSlowdown)
                job(11, 4.0, 5.0, 9.0));
        java.util.List<SimulationEvent> events = java.util.Arrays.asList(
                event(SimulationEventType.JOB_READY, 0.0, 10), // job 10 ready=0
                event(SimulationEventType.JOB_READY, 1.0, 11)); // job 11 ready=1
        java.util.List<SimulationReport.TaskOutcome> tasks =
                java.util.Arrays.asList(task(1, 10, 0.0, 13.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Arrays.asList(new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics =
                SimulationMetrics.calculate(13.0, jobs, summaries(), events, tasks, sourceTasks, config(), platform());

        // Job A: wait=3, exec=10, bounded=10 -> (3+10)/10=1.3 -> max(1.3, 1.0)=1.3
        // Job B: wait=4, exec=4, bounded=10 -> (4+4)/10=0.8 -> max(0.8, 1.0)=1.0 (lower-bound clamp)
        assertEquals(1.15, metrics.getMeanComputeTrueSlowdown(), 1e-9); // (1.3 + 1.0)/2
        assertEquals(2, metrics.getTrueSlowdownObservationCount());
    }

    @Test
    void trueSlowdownBoundsExecutionTimeToTenSeconds() {
        // Very short job: ready=0, start=0.5, finish=0.6 -> wait=0.5, exec=0.1, boundedExec=10
        // trueSlowdown = (0.5 + 0.1) / 10 = 0.06 (instead of 6.0, which prevents an explosion)
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Collections.singletonList(job(10, 0.4, 0.5, 0.6));
        java.util.List<SimulationEvent> events =
                java.util.Collections.singletonList(event(SimulationEventType.JOB_READY, 0.0, 10));
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Collections.singletonList(task(1, 10, 0.0, 0.6));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Collections.singletonList(new org.workflowsim.Task(1, 100));

        SimulationMetrics metrics =
                SimulationMetrics.calculate(0.6, jobs, summaries(), events, tasks, sourceTasks, config(), platform());

        // wait=0.5, exec=0.1, bounded=10 -> (0.5+0.1)/10=0.06 -> max(0.06, 1.0)=1.0 (lower-bound clamp)
        assertEquals(1.0, metrics.getMeanComputeTrueSlowdown(), 1e-9);
        assertEquals(1, metrics.getTrueSlowdownObservationCount());
    }

    @Test
    void trueSlowdownExcludesJobsWithoutReadyEvent() {
        // Job A has a ready event, job B does not (excluded)
        java.util.List<SimulationReport.JobOutcome> jobs =
                java.util.Arrays.asList(job(10, 0.0, 2.0, 6.0), job(11, 1.0, 5.0, 9.0));
        java.util.List<SimulationEvent> events =
                java.util.Collections.singletonList(event(SimulationEventType.JOB_READY, 0.0, 10)); // job 10 only
        java.util.List<SimulationReport.TaskOutcome> tasks =
                java.util.Arrays.asList(task(1, 10, 0.0, 6.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Arrays.asList(new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics =
                SimulationMetrics.calculate(9.0, jobs, summaries(), events, tasks, sourceTasks, config(), platform());

        // Job A: wait=2, exec=4, bounded=10 -> (2+4)/10=0.6 -> max(0.6, 1.0)=1.0 (lower-bound clamp)
        assertEquals(1.0, metrics.getMeanComputeTrueSlowdown(), 1e-9);
        assertEquals(1, metrics.getTrueSlowdownObservationCount());
    }

    @Test
    void duplicateSchedulingDecisionEventFailsExplicitly() {
        // Contract L154 (CONTRACTS.md): "duplicate key events fail explicitly" -- the chain-B
        // audit ratchet (S-B1: previously declared without a test lock). A second SCHEDULING_DECISION
        // event for the same Job must fail explicitly at the metrics entry point and must never
        // silently take the first or the last decision time.
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Arrays.asList(job(10, 0.0, 2.0, 6.0));
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Arrays.asList(task(1, 10, 0.0, 6.0));
        java.util.List<org.workflowsim.Task> sourceTasks = java.util.Arrays.asList(new org.workflowsim.Task(1, 1000));
        java.util.List<SimulationEvent> events = java.util.Arrays.asList(
                event(1, 0.5, SimulationEventType.JOB_READY, 10),
                event(2, 0.6, SimulationEventType.SCHEDULING_DECISION, 10),
                event(3, 0.7, SimulationEventType.SCHEDULING_DECISION, 10));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> SimulationMetrics.calculate(
                        6.0, jobs, summaries(), events, tasks, sourceTasks, config(), platform()));
        assertTrue(
                failure.getMessage().contains("Duplicate SCHEDULING_DECISION"),
                "the error message must name the duplicate decision event, actual: " + failure.getMessage());
    }

    @Test
    void duplicateReadyEventAlsoFailsExplicitly() {
        // Adjacent position under the same contract semantics: a duplicate JOB_READY for the same
        // Job must not stay silent either.
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Arrays.asList(job(10, 0.0, 2.0, 6.0));
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Arrays.asList(task(1, 10, 0.0, 6.0));
        java.util.List<org.workflowsim.Task> sourceTasks = java.util.Arrays.asList(new org.workflowsim.Task(1, 1000));
        java.util.List<SimulationEvent> events = java.util.Arrays.asList(
                event(1, 0.5, SimulationEventType.JOB_READY, 10), event(2, 1.5, SimulationEventType.JOB_READY, 10));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> SimulationMetrics.calculate(
                        6.0, jobs, summaries(), events, tasks, sourceTasks, config(), platform()));
        assertTrue(
                failure.getMessage() != null && !failure.getMessage().isEmpty(),
                "a duplicate ready event must fail explicitly: " + failure.getMessage());
    }

    private static SimulationEvent event(SimulationEventType type, double time, Integer jobId) {
        return event(1, time, type, jobId);
    }

    @Test
    void totalWaitingTimeIsComputedFromJobReadyEvents() {
        // Job A: ready=0.5, start=2.0 -> totalWaiting=1.5
        // Job B: ready=1.0, start=5.0 -> totalWaiting=4.0
        // Mean total waiting time = (1.5 + 4.0) / 2 = 2.75
        java.util.List<SimulationReport.JobOutcome> jobs =
                java.util.Arrays.asList(job(10, 0.0, 2.0, 6.0), job(11, 1.0, 5.0, 9.0));
        java.util.List<SimulationReport.TaskOutcome> tasks =
                java.util.Arrays.asList(task(1, 10, 0.0, 6.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Arrays.asList(new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));
        java.util.List<SimulationEvent> events = java.util.Arrays.asList(
                event(1, 0.5, SimulationEventType.JOB_READY, 10), event(2, 1.0, SimulationEventType.JOB_READY, 11));

        SimulationMetrics metrics =
                SimulationMetrics.calculate(9.0, jobs, summaries(), events, tasks, sourceTasks, config(), platform());

        assertEquals(2.75, metrics.getMeanComputeTotalWaitingTimeSeconds(), 1e-9);
    }

    @Test
    void totalWaitingTimeIgnoresJobsWithoutReadyEvent() {
        // Job A has a ready event, job B does not -> job A only
        java.util.List<SimulationReport.JobOutcome> jobs =
                java.util.Arrays.asList(job(10, 0.0, 2.0, 6.0), job(11, 1.0, 5.0, 9.0));
        java.util.List<SimulationReport.TaskOutcome> tasks =
                java.util.Arrays.asList(task(1, 10, 0.0, 6.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Arrays.asList(new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));
        java.util.List<SimulationEvent> events =
                java.util.Arrays.asList(event(1, 0.5, SimulationEventType.JOB_READY, 10)); // job 10 only

        SimulationMetrics metrics =
                SimulationMetrics.calculate(9.0, jobs, summaries(), events, tasks, sourceTasks, config(), platform());

        assertEquals(1.5, metrics.getMeanComputeTotalWaitingTimeSeconds(), 1e-9); // 2.0 - 0.5
    }

    // -- metric extensions: distribution statistics / success-only / Jain fairness / retry
    //    amplification --

    @Test
    void distributionStatsTrackWaitingAndSlowdownSamples() {
        // Job A: ready=0.5, start=2.0, finish=12.0 -> wait=1.5, exec=10 -> slowdown=(1.5+10)/10=1.15
        // Job B: ready=1.0, start=5.0, finish=9.0 -> wait=4.0, exec=4 -> slowdown=(4+4)/10=0.8->clamp1.0
        // Job C: ready=2.0, start=3.0, finish=23.0 -> wait=1.0, exec=20 -> slowdown=(1+20)/20=1.05
        java.util.List<SimulationReport.JobOutcome> jobs =
                java.util.Arrays.asList(job(10, 0.0, 2.0, 12.0), job(11, 1.0, 5.0, 9.0), job(12, 1.5, 3.0, 23.0));
        java.util.List<SimulationEvent> events = java.util.Arrays.asList(
                event(1, 0.5, SimulationEventType.JOB_READY, 10),
                event(2, 1.0, SimulationEventType.JOB_READY, 11),
                event(3, 2.0, SimulationEventType.JOB_READY, 12));
        java.util.List<SimulationReport.TaskOutcome> tasks =
                java.util.Arrays.asList(task(1, 10, 0.0, 12.0), task(2, 11, 0.0, 9.0), task(3, 12, 0.0, 23.0));
        java.util.List<org.workflowsim.Task> sourceTasks = java.util.Arrays.asList(
                new org.workflowsim.Task(1, 1000),
                new org.workflowsim.Task(2, 1000),
                new org.workflowsim.Task(3, 1000));

        SimulationMetrics metrics =
                SimulationMetrics.calculate(23.0, jobs, summaries(), events, tasks, sourceTasks, config(), platform());

        // Waiting samples sorted [1.0, 1.5, 4.0]: median rank=ceil(0.5*3)=2 -> 1.5; P95 rank=3 -> 4.0
        assertEquals(3, metrics.getTotalWaitingTimeObservationCount());
        assertEquals(1.5, metrics.getMedianComputeTotalWaitingTimeSeconds(), 1e-9);
        assertEquals(4.0, metrics.getP95ComputeTotalWaitingTimeSeconds(), 1e-9);
        assertEquals(4.0, metrics.getMaxComputeTotalWaitingTimeSeconds(), 1e-9);
        // Slowdown samples sorted [1.0, 1.05, 1.15]: median 1.05; P95/max 1.15
        assertEquals(1.05, metrics.getMedianComputeTrueSlowdown(), 1e-9);
        assertEquals(1.15, metrics.getP95ComputeTrueSlowdown(), 1e-9);
        assertEquals(1.15, metrics.getMaxComputeTrueSlowdown(), 1e-9);
    }

    @Test
    void distributionStatsAreZeroWhenNoObservationsExist() {
        // No JOB_READY event -> observation count 0, quantiles 0.0 (consistent with the empty-set convention)
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Collections.singletonList(job(10, 0.0, 2.0, 6.0));
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Collections.singletonList(task(1, 10, 0.0, 6.0));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Collections.singletonList(new org.workflowsim.Task(1, 1000));

        SimulationMetrics metrics = SimulationMetrics.calculate(
                6.0,
                jobs,
                summaries(),
                Collections.<SimulationEvent>emptyList(),
                tasks,
                sourceTasks,
                config(),
                platform());

        assertEquals(0, metrics.getTotalWaitingTimeObservationCount());
        assertEquals(0.0, metrics.getMedianComputeTotalWaitingTimeSeconds(), 1e-9);
        assertEquals(0.0, metrics.getP95ComputeTotalWaitingTimeSeconds(), 1e-9);
        assertEquals(0.0, metrics.getMaxComputeTotalWaitingTimeSeconds(), 1e-9);
        assertEquals(0.0, metrics.getMedianComputeTrueSlowdown(), 1e-9);
        assertEquals(0.0, metrics.getP95ComputeTrueSlowdown(), 1e-9);
        assertEquals(0.0, metrics.getMaxComputeTrueSlowdown(), 1e-9);
        assertEquals(0, metrics.getSuccessOnlyWaitingObservationCount());
    }

    @Test
    void successOnlyVariantsExcludeFailedAttempts() {
        // Job A succeeded with wait=1.5, job B failed with wait=4.0.
        // Overall mean including failed attempts = (1.5+4.0)/2 = 2.75; success-only = 1.5 (n=1).
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Arrays.asList(
                jobWithStatus(10, Cloudlet.SUCCESS, 0.0, 2.0, 12.0), jobWithStatus(11, Cloudlet.FAILED, 1.0, 5.0, 9.0));
        java.util.List<SimulationEvent> events = java.util.Arrays.asList(
                event(1, 0.5, SimulationEventType.JOB_READY, 10), event(2, 1.0, SimulationEventType.JOB_READY, 11));
        java.util.List<SimulationReport.TaskOutcome> tasks =
                java.util.Arrays.asList(task(1, 10, 0.0, 12.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Arrays.asList(new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics =
                SimulationMetrics.calculate(12.0, jobs, summaries(), events, tasks, sourceTasks, config(), platform());

        assertEquals(2.75, metrics.getMeanComputeTotalWaitingTimeSeconds(), 1e-9);
        assertEquals(1.5, metrics.getSuccessOnlyMeanComputeTotalWaitingTimeSeconds(), 1e-9);
        assertEquals(1, metrics.getSuccessOnlyWaitingObservationCount());
        // success-only slowdown covers job A only: (1.5+10)/10 = 1.15
        assertEquals(1.15, metrics.getSuccessOnlyMeanComputeTrueSlowdown(), 1e-9);
    }

    @Test
    void jainFairnessIndexCapturesVmUtilizationImbalance() {
        // VM0 busy [0,10] (utilization 1.0), VM1 busy [0,5] (utilization 0.5), makespan=10.
        // Jain = (1.0+0.5)^2 / (2*(1.0^2+0.5^2)) = 2.25/2.5 = 0.9
        java.util.Map<Integer, SimulationReport.VmSummary> summaries =
                new java.util.LinkedHashMap<Integer, SimulationReport.VmSummary>();
        summaries.put(0, new SimulationReport.VmSummary(0, 1, 1, 0, 10.0, 10.0));
        summaries.put(1, new SimulationReport.VmSummary(1, 1, 1, 0, 5.0, 5.0));
        java.util.List<SimulationReport.JobOutcome> jobs =
                java.util.Arrays.asList(jobOnVm(10, 0, 0.0, 10.0), jobOnVm(11, 1, 0.0, 5.0));
        java.util.List<SimulationReport.TaskOutcome> tasks =
                java.util.Arrays.asList(task(1, 10, 0.0, 10.0), task(2, 11, 0.0, 5.0));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Arrays.asList(new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics = SimulationMetrics.calculate(
                10.0,
                jobs,
                summaries,
                Collections.<SimulationEvent>emptyList(),
                tasks,
                sourceTasks,
                config(),
                platform());

        assertEquals(0.9, metrics.getVmUtilizationJainFairnessIndex(), 1e-9);
        assertEquals(0.75, metrics.getMeanVmModeledIntervalUtilization(), 1e-9);
    }

    @Test
    void retryAmplificationRatioCountsAllAttemptsPerLogicalTask() {
        // Logical task 1: first attempt failed (job 10) + retry (job 11 succeeded) = 2 attempts;
        // logical task 2: one success (job 12). Amplification = 3/2 = 1.5.
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Arrays.asList(
                jobWithStatus(10, Cloudlet.FAILED, 0.0, 1.0, 3.0),
                jobWithStatus(11, Cloudlet.SUCCESS, 3.0, 4.0, 6.0),
                jobWithStatus(12, Cloudlet.SUCCESS, 0.0, 2.0, 5.0));
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Arrays.asList(
                new SimulationReport.TaskOutcome(1, 10, 0, Cloudlet.FAILED, Cloudlet.FAILED, 0, 1000L, 1.0, 3.0),
                new SimulationReport.TaskOutcome(1, 11, 0, Cloudlet.SUCCESS, Cloudlet.SUCCESS, 0, 1000L, 4.0, 6.0),
                new SimulationReport.TaskOutcome(2, 12, 0, Cloudlet.SUCCESS, Cloudlet.SUCCESS, 0, 1000L, 2.0, 5.0));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Arrays.asList(new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics = SimulationMetrics.calculate(
                6.0,
                jobs,
                summaries(),
                Collections.<SimulationEvent>emptyList(),
                tasks,
                sourceTasks,
                config(),
                platform());

        assertEquals(1.5, metrics.getRetryAmplificationRatio(), 1e-9);
    }

    @Test
    void retryAmplificationRatioIsOneWithoutFailures() {
        java.util.List<SimulationReport.JobOutcome> jobs =
                java.util.Arrays.asList(job(10, 0.0, 2.0, 6.0), job(11, 1.0, 5.0, 9.0));
        java.util.List<SimulationReport.TaskOutcome> tasks =
                java.util.Arrays.asList(task(1, 10, 0.0, 6.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Arrays.asList(new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics = SimulationMetrics.calculate(
                9.0,
                jobs,
                summaries(),
                Collections.<SimulationEvent>emptyList(),
                tasks,
                sourceTasks,
                config(),
                platform());

        assertEquals(1.0, metrics.getRetryAmplificationRatio(), 1e-9);
    }

    // -- test helpers --

    private static SimulationReport.JobOutcome jobWithStatus(
            int id, int status, double submission, double start, double finish) {
        return new SimulationReport.JobOutcome(
                id,
                0,
                status,
                Parameters.ClassType.COMPUTE.value,
                submission,
                start,
                finish,
                Math.max(0.0, finish - start),
                1.0,
                1,
                Collections.singletonList(id * 100 + 1));
    }

    private static SimulationReport.JobOutcome jobOnVm(int id, int vmId, double start, double finish) {
        return new SimulationReport.JobOutcome(
                id,
                vmId,
                Cloudlet.SUCCESS,
                Parameters.ClassType.COMPUTE.value,
                0.0,
                start,
                finish,
                Math.max(0.0, finish - start),
                1.0,
                1,
                Collections.singletonList(id * 100 + 1));
    }

    private static SimulationEvent event(int seq, double time, SimulationEventType type, Integer jobId) {
        return new SimulationEvent(
                seq,
                time,
                type,
                jobId,
                null,
                null,
                Collections.<Integer>emptyList(),
                Collections.<String, Object>emptyMap());
    }

    private static SimulationReport.JobOutcome job(int id, double submission, double start, double finish) {
        return new SimulationReport.JobOutcome(
                id,
                0,
                Cloudlet.SUCCESS,
                Parameters.ClassType.COMPUTE.value,
                submission,
                start,
                finish,
                Math.max(0.0, finish - start),
                1.0,
                1,
                Collections.singletonList(id * 100 + 1));
    }

    private static SimulationReport.TaskOutcome task(int taskId, int jobId, double start, double finish) {
        return new SimulationReport.TaskOutcome(
                taskId, jobId, 0, Cloudlet.SUCCESS, Cloudlet.SUCCESS, 0, 1000L, start, finish);
    }

    private static java.util.Map<Integer, SimulationReport.VmSummary> summaries() {
        return Collections.emptyMap();
    }

    private static org.workflowsim.utils.SimulationConfig config() {
        return org.workflowsim.utils.SimulationConfig.builder("test.dax", 1).build();
    }

    private static org.workflowsim.platform.PlatformProfile platform() {
        return org.workflowsim.platform.PlatformProfiles.homogeneousLocal("test", 1);
    }
}
