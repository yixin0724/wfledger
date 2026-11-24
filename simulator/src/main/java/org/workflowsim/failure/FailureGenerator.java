/**
 * Copyright 2012-2013 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.workflowsim.failure;

import java.util.IdentityHashMap;
import java.util.Map;

import org.apache.commons.math3.distribution.GammaDistribution;
import org.apache.commons.math3.distribution.LogNormalDistribution;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.RealDistribution;
import org.apache.commons.math3.distribution.WeibullDistribution;
import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.SimulationRandom;

public class FailureGenerator {

    private static final int MAX_FAILURE_SIZE_EXTENSION = 50;

    private static final Map<DistributionGenerator, Integer> FAILURE_SIZE_EXTENSIONS =
            new IdentityHashMap<DistributionGenerator, Integer>();

    private static final boolean HAS_CHANGE_TIME = false;

    protected static RealDistribution getDistribution(double alpha, double beta) {
        RealDistribution distribution = null;
        switch (FailureParameters.getFailureDistribution()) {
            case LOGNORMAL:
                distribution = new LogNormalDistribution(
                        SimulationRandom.newApacheRandom("failure.distribution"),
                        1.0 / alpha,
                        beta,
                        LogNormalDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            case WEIBULL:
                distribution = new WeibullDistribution(
                        SimulationRandom.newApacheRandom("failure.distribution"),
                        beta,
                        1.0 / alpha,
                        WeibullDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            case GAMMA:
                distribution = new GammaDistribution(
                        SimulationRandom.newApacheRandom("failure.distribution"),
                        beta,
                        1.0 / alpha,
                        GammaDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            case NORMAL:
                distribution = new NormalDistribution(
                        SimulationRandom.newApacheRandom("failure.distribution"),
                        1.0 / alpha,
                        beta,
                        NormalDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            default:
                break;
        }
        return distribution;
    }

    protected static void initFailureSamples() {}

    public static void init() {
        FAILURE_SIZE_EXTENSIONS.clear();
        initFailureSamples();
    }

    public static void reset() {
        FAILURE_SIZE_EXTENSIONS.clear();
    }

    protected static boolean checkFailureStatus(Task task, int vmId) throws Exception {
        if (FailureParameters.getFailureGeneratorMode() == FailureParameters.FTCFailure.FAILURE_NONE) {
            return false;
        }
        return !Double.isNaN(failureInstant(task, vmId, task.getExecStartTime(), task.getTaskFinishTime()));
    }

    /**
     * Returns the failure arrival that ends a compute window, consuming the scope's arrival samples.
     *
     * <p>FGCS-R5 (2026-11-24): root cause - the outcome was decided at the attempt's completion
     * boundary, so a failed attempt always ran its full length. The datacenter now calls this when it
     * submits the attempt, with the modeled compute window {@code [start, finish)}, and ends the attempt
     * at the returned instant. The consumption rule is unchanged: every arrival before {@code start} is
     * consumed, the first arrival in the window is consumed and is the failure, and later arrivals stay
     * unconsumed. The window is half-open: an arrival exactly at {@code finish} does not fail the attempt.
     * Samples are now consumed in submission (compute-start) order instead of completion order; the two
     * orders coincide on VM-keyed scopes because one VM runs one attempt at a time.
     *
     * @param task logical Task whose depth selects the generator
     * @param vmId VM whose ID selects the generator
     * @param start compute-window start
     * @param finish compute-window finish, at least {@code start}
     * @return the failure instant in {@code [start, finish)}, or NaN when the window survives
     * @throws Exception when the arrival cache cannot be extended to cover {@code start}
     */
    public static double failureInstant(Task task, int vmId, double start, double finish) throws Exception {
        if (FailureParameters.getFailureGeneratorMode() == FailureParameters.FTCFailure.FAILURE_NONE) {
            return Double.NaN;
        }
        if (!Double.isFinite(start) || !Double.isFinite(finish) || finish < start) {
            throw new IllegalStateException("Task " + task.getCloudletId()
                    + " has an invalid failure-observation window [" + start + ", " + finish + ")");
        }
        DistributionGenerator generator = FailureParameters.getGeneratorForFailureScope(vmId, task.getDepth());
        ensureUnconsumedArrivalCoverage(generator, start);
        double[] samples = generator.getCumulativeSamples();
        for (int sampleId = generator.getConsumedSampleCount(); sampleId < samples.length; sampleId++) {
            if (samples[sampleId] < start) {
                generator.consumeSamplesThrough(sampleId);
                continue;
            }
            if (samples[sampleId] < finish) {
                generator.consumeSamplesThrough(sampleId);
                return samples[sampleId];
            }
            return Double.NaN;
        }
        throw new IllegalStateException("Failure arrival cache has no unconsumed sample after coverage");
    }

    private static void ensureUnconsumedArrivalCoverage(DistributionGenerator generator, double start)
            throws Exception {
        double[] samples = generator.getCumulativeSamples();
        while (generator.getConsumedSampleCount() >= samples.length || samples[samples.length - 1] < start) {
            generator.extendSamples();
            samples = generator.getCumulativeSamples();
            int extensions = extensionCount(generator) + 1;
            FAILURE_SIZE_EXTENSIONS.put(generator, extensions);
            if (extensions >= MAX_FAILURE_SIZE_EXTENSION) {
                throw new Exception("Failure arrival sample extension limit " + MAX_FAILURE_SIZE_EXTENSION
                        + " exceeded while covering task start " + start);
            }
        }
    }

    private static int extensionCount(DistributionGenerator generator) {
        Integer value = FAILURE_SIZE_EXTENSIONS.get(generator);
        return value == null ? 0 : value.intValue();
    }

    /**
     * Fixes the outcome of a returned compute attempt.
     *
     * <p>FGCS-R5 (2026-11-24): the failure decision is taken at submission
     * ({@link #failureInstant(Task, int, double, double)}); a returned attempt fails exactly when the
     * datacenter recorded a failure instant for it, and every Task of a failed attempt is failed.
     *
     * @param job returned compute attempt
     * @return whether the attempt failed
     */
    public static boolean applySubmittedOutcome(Job job) {
        boolean failed = job.hasFailureInstant();
        if (failed && FailureParameters.getFailureGeneratorMode() == FailureParameters.FTCFailure.FAILURE_NONE) {
            throw new IllegalStateException("Job " + job.getCloudletId() + " carries a failure instant although "
                    + "the failure model is disabled");
        }
        try {
            for (Task task : job.getTaskList()) {
                task.setCloudletStatus(failed ? Cloudlet.FAILED : Cloudlet.SUCCESS);
                if (FailureParameters.getFailureGeneratorMode() != FailureParameters.FTCFailure.FAILURE_NONE) {
                    FailureMonitor.postFailureRecord(new FailureRecord(
                            0,
                            failed ? 1 : 0,
                            task.getDepth(),
                            1,
                            job.getVmId(),
                            task.getCloudletId(),
                            job.getUserId()));
                }
            }
            job.setCloudletStatus(failed ? Cloudlet.FAILED : Cloudlet.SUCCESS);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not fix the outcome of Job " + job.getCloudletId() + " on VM " + job.getVmId(), exception);
        }
        return failed;
    }

    public static boolean generate(Job job) {
        boolean jobFailed = false;
        if (FailureParameters.getFailureGeneratorMode() == FailureParameters.FTCFailure.FAILURE_NONE) {

            try {
                for (Task task : job.getTaskList()) {
                    task.setCloudletStatus(Cloudlet.SUCCESS);
                }
                job.setCloudletStatus(Cloudlet.SUCCESS);
                return false;
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "Could not mark failure-disabled job " + job.getCloudletId() + " as successful", exception);
            }
        }
        try {

            for (Task task : job.getTaskList()) {
                int failedTaskSum = 0;
                if (checkFailureStatus(task, job.getVmId())) {

                    jobFailed = true;
                    failedTaskSum++;
                    task.setCloudletStatus(Cloudlet.FAILED);
                } else {
                    task.setCloudletStatus(Cloudlet.SUCCESS);
                }
                FailureRecord record = new FailureRecord(
                        0, failedTaskSum, task.getDepth(), 1, job.getVmId(), task.getCloudletId(), job.getUserId());
                FailureMonitor.postFailureRecord(record);
            }

            if (jobFailed) {
                job.setCloudletStatus(Cloudlet.FAILED);
            } else {
                job.setCloudletStatus(Cloudlet.SUCCESS);
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failure generation failed for job " + job.getCloudletId() + " on VM " + job.getVmId(), exception);
        }
        return jobFailed;
    }
}
