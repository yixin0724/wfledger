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

                generator.consumeSamplesThrough(sampleId);
                continue;
            }
            if (end < samples[sampleId]) {

                return false;
            }
            if (start <= samples[sampleId]) {

                generator.consumeSamplesThrough(sampleId);
                return true;
            }
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
