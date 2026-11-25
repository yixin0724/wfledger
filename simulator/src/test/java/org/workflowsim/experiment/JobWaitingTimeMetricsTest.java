package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.cloudbus.cloudsim.Cloudlet;
import org.junit.jupiter.api.Test;
import org.workflowsim.utils.Parameters;

class JobWaitingTimeMetricsTest {

    @Test
    void jobOutcomeDerivesWaitingExecutionAndResponseTimes() {

        SimulationReport.JobOutcome job = job(1, 2.0, 5.0, 15.0);

        assertEquals(2.0, job.getSubmissionTime(), 1e-9);
        assertEquals(3.0, job.getWaitingTime(), 1e-9);
        assertEquals(10.0, job.getExecutionTime(), 1e-9);
        assertEquals(13.0, job.getResponseTime(), 1e-9);
    }

    @Test
    void negativeIntervalsAreClampedToZeroForIncompleteJobs() {

        SimulationReport.JobOutcome job = job(1, 10.0, 5.0, 3.0);

        assertEquals(0.0, job.getWaitingTime(), 1e-9);
        assertEquals(0.0, job.getExecutionTime(), 1e-9);
        assertEquals(0.0, job.getResponseTime(), 1e-9);
    }

    @Test
    void zeroSubmissionTimeYieldsWaitingEqualToStartTime() {

        SimulationReport.JobOutcome job = job(1, 0.0, 4.0, 9.0);

        assertEquals(4.0, job.getWaitingTime(), 1e-9);
        assertEquals(9.0, job.getResponseTime(), 1e-9);
    }

    @Test
    void metricsAggregateMeanWaitingResponseAndSlowdown() {

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

        assertEquals(3.0, metrics.getMeanJobVmQueueWaitingTimeSeconds(), 1e-9);
        assertEquals(7.0, metrics.getMeanJobResponseTimeSeconds(), 1e-9);
        assertEquals(1.75, metrics.getMeanJobVmLevelSlowdown(), 1e-9);
    }

    @Test
    void slowdownExcludesJobsWithNearZeroExecutionTime() {

        java.util.List<SimulationReport.JobOutcome> jobs =
                java.util.Arrays.asList(job(10, 0.0, 2.0, 6.0), job(11, 1.0, 5.0, 5.0));
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

        assertEquals(1.5, metrics.getMeanJobVmLevelSlowdown(), 1e-9);
    }

    @Test
    void trueSlowdownUsesReadyTimeFromEvents() {

        java.util.List<SimulationReport.JobOutcome> jobs =
                java.util.Arrays.asList(job(10, 2.0, 3.0, 13.0), job(11, 4.0, 5.0, 9.0));
        java.util.List<SimulationEvent> events = java.util.Arrays.asList(
                event(SimulationEventType.JOB_READY, 0.0, 10), event(SimulationEventType.JOB_READY, 1.0, 11));
        java.util.List<SimulationReport.TaskOutcome> tasks =
                java.util.Arrays.asList(task(1, 10, 0.0, 13.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Arrays.asList(new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics =
                SimulationMetrics.calculate(13.0, jobs, summaries(), events, tasks, sourceTasks, config(), platform());

        assertEquals(1.15, metrics.getMeanComputeTrueSlowdown(), 1e-9);
        assertEquals(2, metrics.getTrueSlowdownObservationCount());
    }

    @Test
    void trueSlowdownBoundsExecutionTimeToTenSeconds() {

        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Collections.singletonList(job(10, 0.4, 0.5, 0.6));
        java.util.List<SimulationEvent> events =
                java.util.Collections.singletonList(event(SimulationEventType.JOB_READY, 0.0, 10));
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Collections.singletonList(task(1, 10, 0.0, 0.6));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Collections.singletonList(new org.workflowsim.Task(1, 100));

        SimulationMetrics metrics =
                SimulationMetrics.calculate(0.6, jobs, summaries(), events, tasks, sourceTasks, config(), platform());

        assertEquals(1.0, metrics.getMeanComputeTrueSlowdown(), 1e-9);
        assertEquals(1, metrics.getTrueSlowdownObservationCount());
    }

    /**
     * FGCS-R6 (2026-11-25): the bounded-slowdown floor is a declared configuration parameter.
     *
     * <p>Hand derivation (Feitelson 1997 bounded slowdown {@code max((w + e) / max(e, tau), 1)}):
     * Job 10 is ready at 0, starts at 2 and finishes at 3 (w = 2, e = 1); Job 11 is ready at 0, starts
     * at 1 and finishes at 21 (w = 1, e = 20). At tau = 10: Job 10 gives max(3 / 10, 1) = 1.0 (clamped,
     * e = 1 &lt; 10) and Job 11 gives 21 / 20 = 1.05, so the mean is 1.025 and the clamped fraction 1/2.
     * At tau = 0.1: Job 10 gives 3 / 1 = 3.0 and Job 11 still 1.05, so the mean is 2.025 and no sample is
     * clamped. The ranking-relevant value of the short Job changes threefold with the floor.
     */
    @Test
    void boundedSlowdownThresholdIsDeclaredAndClampedSamplesAreCounted() {
        java.util.List<SimulationReport.JobOutcome> jobs =
                java.util.Arrays.asList(job(10, 0.0, 2.0, 3.0), job(11, 0.0, 1.0, 21.0));
        java.util.List<SimulationEvent> events = java.util.Arrays.asList(
                event(1, 0.0, SimulationEventType.JOB_READY, 10), event(2, 0.0, SimulationEventType.JOB_READY, 11));
        java.util.List<SimulationReport.TaskOutcome> tasks =
                java.util.Arrays.asList(task(1, 10, 2.0, 3.0), task(2, 11, 1.0, 21.0));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Arrays.asList(new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics defaultFloor =
                SimulationMetrics.calculate(21.0, jobs, summaries(), events, tasks, sourceTasks, config(), platform());
        assertEquals(10.0, defaultFloor.getBoundedSlowdownThresholdSeconds(), 0.0);
        assertEquals(1.025, defaultFloor.getMeanComputeTrueSlowdown(), 1e-12);
        assertEquals(1, defaultFloor.getBoundedSlowdownFloorClampedObservationCount());
        assertEquals(0.5, defaultFloor.getBoundedSlowdownFloorClampedFraction(), 0.0);

        org.workflowsim.utils.SimulationConfig tenthSecond = org.workflowsim.utils.SimulationConfig.builder(
                        "test.dax", 1)
                .boundedSlowdownThresholdSeconds(0.1)
                .build();
        SimulationMetrics shortFloor = SimulationMetrics.calculate(
                21.0, jobs, summaries(), events, tasks, sourceTasks, tenthSecond, platform());
        assertEquals(0.1, shortFloor.getBoundedSlowdownThresholdSeconds(), 0.0);
        assertEquals(2.025, shortFloor.getMeanComputeTrueSlowdown(), 1e-12);
        assertEquals(3.0, shortFloor.getMaxComputeTrueSlowdown(), 1e-12);
        assertEquals(0, shortFloor.getBoundedSlowdownFloorClampedObservationCount());
        assertEquals(0.0, shortFloor.getBoundedSlowdownFloorClampedFraction(), 0.0);
    }

    @Test
    void boundedSlowdownThresholdMustBeFiniteAndPositive() {
        for (double invalid : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(
                    IllegalArgumentException.class, () -> org.workflowsim.utils.SimulationConfig.builder("test.dax", 1)
                            .boundedSlowdownThresholdSeconds(invalid)
                            .build());
        }
        org.workflowsim.utils.SimulationConfig declared = org.workflowsim.utils.SimulationConfig.builder("test.dax", 1)
                .boundedSlowdownThresholdSeconds(1.0)
                .build();
        assertEquals(1.0, declared.withRandomSeed(5L).getBoundedSlowdownThresholdSeconds(), 0.0);
    }

    /**
     * FGCS-R6 (2026-11-25): busy time is decomposed into envelope input reading and compute.
     *
     * <p>Hand derivation: VM 0 runs Job 10 over [0, 10) whose JOB_EXECUTION_STARTED declares 6 s of
     * effective data staging, and Job 11 over [10, 14) with 1 s of staging. Busy = 14 s (union), read =
     * 6 + 1 = 7 s, compute-only = 14 - 7 = 7 s and the I/O share is 7 / 14 = 0.5. Both Jobs are covered by a
     * start event, so the observation count is 2. The energy law is unchanged: it still charges all 14 s.
     */
    @Test
    void busyTimeExposesTheEnvelopeInputReadDecomposition() {
        java.util.Map<Integer, SimulationReport.VmSummary> summaries =
                new java.util.LinkedHashMap<Integer, SimulationReport.VmSummary>();
        summaries.put(0, new SimulationReport.VmSummary(0, 2, 2, 0, 14.0, 14.0));
        java.util.List<SimulationReport.JobOutcome> jobs =
                java.util.Arrays.asList(jobOnVm(10, 0, 0.0, 10.0), jobOnVm(11, 0, 10.0, 14.0));
        java.util.List<SimulationEvent> events =
                java.util.Arrays.asList(started(1, 0.0, 10, 6.0), started(2, 10.0, 11, 1.0));
        java.util.List<SimulationReport.TaskOutcome> tasks =
                java.util.Arrays.asList(task(1, 10, 6.0, 10.0), task(2, 11, 11.0, 14.0));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Arrays.asList(new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics =
                SimulationMetrics.calculate(14.0, jobs, summaries, events, tasks, sourceTasks, config(), platform());

        assertEquals(14.0, metrics.getTotalVmModeledBusyIntervalSeconds(), 1e-12);
        assertEquals(7.0, metrics.getTotalInputReadSecondsInEnvelope(), 1e-12);
        assertEquals(7.0, metrics.getTotalComputeOnlyBusySeconds(), 1e-12);
        assertEquals(0.5, metrics.getInputReadShareOfBusyTime(), 1e-12);
        assertEquals(2, metrics.getInputReadInEnvelopeObservationCount());
        assertEquals(7.0, metrics.getVmMetrics().get(0).getInputReadSecondsInEnvelope(), 1e-12);
        assertEquals(7.0, metrics.getVmMetrics().get(0).getComputeOnlyBusySeconds(), 1e-12);
    }

    private static SimulationEvent started(int seq, double time, int jobId, double staged) {
        java.util.Map<String, Object> attributes = new java.util.LinkedHashMap<String, Object>();
        attributes.put("jobEnvelopeStartSeconds", time);
        attributes.put("effectiveDataStageSeconds", staged);
        attributes.put("requestedDataStageSeconds", staged);
        attributes.put("modeledJobStartSeconds", time + staged);
        return new SimulationEvent(
                seq,
                time,
                SimulationEventType.JOB_EXECUTION_STARTED,
                jobId,
                null,
                null,
                Collections.<Integer>emptyList(),
                attributes);
    }

    @Test
    void trueSlowdownExcludesJobsWithoutReadyEvent() {

        java.util.List<SimulationReport.JobOutcome> jobs =
                java.util.Arrays.asList(job(10, 0.0, 2.0, 6.0), job(11, 1.0, 5.0, 9.0));
        java.util.List<SimulationEvent> events =
                java.util.Collections.singletonList(event(SimulationEventType.JOB_READY, 0.0, 10));
        java.util.List<SimulationReport.TaskOutcome> tasks =
                java.util.Arrays.asList(task(1, 10, 0.0, 6.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Arrays.asList(new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics =
                SimulationMetrics.calculate(9.0, jobs, summaries(), events, tasks, sourceTasks, config(), platform());

        assertEquals(1.0, metrics.getMeanComputeTrueSlowdown(), 1e-9);
        assertEquals(1, metrics.getTrueSlowdownObservationCount());
    }

    @Test
    void duplicateSchedulingDecisionEventFailsExplicitly() {

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

        java.util.List<SimulationReport.JobOutcome> jobs =
                java.util.Arrays.asList(job(10, 0.0, 2.0, 6.0), job(11, 1.0, 5.0, 9.0));
        java.util.List<SimulationReport.TaskOutcome> tasks =
                java.util.Arrays.asList(task(1, 10, 0.0, 6.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks =
                java.util.Arrays.asList(new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));
        java.util.List<SimulationEvent> events =
                java.util.Arrays.asList(event(1, 0.5, SimulationEventType.JOB_READY, 10));

        SimulationMetrics metrics =
                SimulationMetrics.calculate(9.0, jobs, summaries(), events, tasks, sourceTasks, config(), platform());

        assertEquals(1.5, metrics.getMeanComputeTotalWaitingTimeSeconds(), 1e-9);
    }

    @Test
    void distributionStatsTrackWaitingAndSlowdownSamples() {

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

        assertEquals(3, metrics.getTotalWaitingTimeObservationCount());
        assertEquals(1.5, metrics.getMedianComputeTotalWaitingTimeSeconds(), 1e-9);
        assertEquals(4.0, metrics.getP95ComputeTotalWaitingTimeSeconds(), 1e-9);
        assertEquals(4.0, metrics.getMaxComputeTotalWaitingTimeSeconds(), 1e-9);

        assertEquals(1.05, metrics.getMedianComputeTrueSlowdown(), 1e-9);
        assertEquals(1.15, metrics.getP95ComputeTrueSlowdown(), 1e-9);
        assertEquals(1.15, metrics.getMaxComputeTrueSlowdown(), 1e-9);
    }

    @Test
    void distributionStatsAreZeroWhenNoObservationsExist() {

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

        assertEquals(1.15, metrics.getSuccessOnlyMeanComputeTrueSlowdown(), 1e-9);
    }

    @Test
    void jainFairnessIndexCapturesVmUtilizationImbalance() {

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
