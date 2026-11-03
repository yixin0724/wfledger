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

/**
 * Decides whether a Job fails from the configured failure-arrival time samples within the
 * task execution window.
 *
 * <p>This class depends on the state installed by a single simulation session in
 * {@link FailureParameters}; it implements an abstract failure model and does not imply
 * calibration against real cloud-platform failure data.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class FailureGenerator {

    /**
     * Extends the distribution samples on demand while the existing samples do not yet cover
     * the task start time; the number of extensions is capped so that an extremely high
     * failure rate cannot cause unbounded sampling.
     */
    private static final int MAX_FAILURE_SIZE_EXTENSION = 50;
    /**
     * Sample-cache extension count recorded per generator, so that multiple failure streams
     * do not consume each other's safety allowance.
     */
    private static final Map<DistributionGenerator, Integer> FAILURE_SIZE_EXTENSIONS =
            new IdentityHashMap<DistributionGenerator, Integer>();

    private static final boolean HAS_CHANGE_TIME = false;
    /**
     * Creates a random distribution for the currently configured distribution family.
     *
     * @param alpha distribution parameter alpha
     * @param beta distribution parameter beta
     * @return corresponding real-valued distribution
     */
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
                // For the NORMAL distribution beta is the standard deviation and 1.0 / alpha
                // is the mean.
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

    /**
     * Initializes the failure-generation counters of one simulation run.
     */
    public static void init() {
        FAILURE_SIZE_EXTENSIONS.clear();
        initFailureSamples();
    }

    /** Clears the failure-generation counters held by a finished simulation run. */
    public static void reset() {
        FAILURE_SIZE_EXTENSIONS.clear();
    }

    protected static boolean checkFailureStatus(Task task, int vmId) throws Exception {

        if (FailureParameters.getFailureGeneratorMode() == FailureParameters.FTCFailure.FAILURE_NONE) {
            return false;
        }
        // Failure verdicts and DR reclustering share one coordinate interpretation, so
        // the mode name is not reinterpreted by different paths.
        DistributionGenerator generator = FailureParameters.getGeneratorForFailureScope(vmId, task.getDepth());

        double start = task.getExecStartTime();
        double end = task.getTaskFinishTime();

        if (Double.isNaN(start)
                || Double.isInfinite(start)
                || Double.isNaN(end)
                || Double.isInfinite(end)
                || end < start) {
            throw new IllegalStateException("Task " + task.getCloudletId()
                    + " has an invalid failure-observation window [" + start + ", " + end + "]");
        }

        ensureUnconsumedArrivalCoverage(generator, start);
        double[] samples = generator.getCumulativeSamples();
        for (int sampleId = generator.getConsumedSampleCount(); sampleId < samples.length; sampleId++) {
            if (samples[sampleId] < start) {
                // An arrival already expired during an idle period must still be consumed
                // exactly once.
                generator.consumeSamplesThrough(sampleId);
                continue;
            }
            if (end < samples[sampleId]) {
                // The next failure arrival lies after the end of this task, so this task
                // does not fail.
                return false;
            }
            if (start <= samples[sampleId]) {
                // The failure arrival falls inside the execution window of this task.
                // Consume the cumulative arrival that matched, not another raw interval at
                // the cursor.
                generator.consumeSamplesThrough(sampleId);
                return true;
            }
        }

        throw new IllegalStateException("Failure arrival cache has no unconsumed sample after coverage");
    }

    /** Extends one failure stream to cover both the current time and the unconsumed cursor. */
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
     * Decides failure task by task within the Job and updates the status of the tasks and of
     * the Job in step.
     *
     * @param job
     * @return {@code true} when at least one task failed
     */
    public static boolean generate(Job job) {
        boolean jobFailed = false;
        if (FailureParameters.getFailureGeneratorMode() == FailureParameters.FTCFailure.FAILURE_NONE) {
            // Even when no failure arrival stream is enabled, a completed compute Job
            // must establish an explicit SUCCESS status for its logical Tasks. The
            // datacenter uses it to submit output replicas per Task, so an unset status
            // is not mistaken for a failure.
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
                    // The execution window of the current task contains one failure arrival.
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
