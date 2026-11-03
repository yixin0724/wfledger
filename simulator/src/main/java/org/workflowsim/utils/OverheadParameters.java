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
package org.workflowsim.utils;

import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.Job;

/**
 * Mutable overhead parameter set used by the historical execution path.
 *
 * <p>New code should prefer the immutable {@link OverheadModelConfig} in
 * {@link SimulationConfig}; the session creates this class from the root seed. Each delay
 * map is keyed by task or job depth, and key {@code 0} is the default used when no explicit
 * depth entry exists.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class OverheadParameters {

    /** Maximum number of jobs per WED sub-batch (0 means the whole batch is not split). */
    private final int WED_INTERVAL;
    /** Logical bandwidth used by the legacy overhead path. */
    private final double bandwidth;
    /** Map from task depth to the workflow engine delay generator. */
    private final Map<Integer, DistributionGenerator> WED_DELAY;
    /** Map from task depth to the queue delay generator. */
    private final Map<Integer, DistributionGenerator> QUEUE_DELAY;
    /** Map from task depth to the post-processing delay generator. */
    private final Map<Integer, DistributionGenerator> POST_DELAY;
    /** Map from task depth to the clustering delay generator. */
    private final Map<Integer, DistributionGenerator> CLUST_DELAY;

    /**
     * Creates the legacy overhead parameters.
     *
     * @param wed_interval maximum number of jobs per WED sub-batch (0 means the whole batch
     *        is not split)
     * @param wed_delay workflow engine delay generator
     * @param queue_delay queue delay generator
     * @param post_delay post-processing delay generator
     * @param cluster_delay clustering delay generator
     * @param bandwidth logical bandwidth
     */
    public OverheadParameters(
            int wed_interval,
            Map<Integer, DistributionGenerator> wed_delay,
            Map<Integer, DistributionGenerator> queue_delay,
            Map<Integer, DistributionGenerator> post_delay,
            Map<Integer, DistributionGenerator> cluster_delay,
            double bandwidth) {
        this.WED_INTERVAL = wed_interval;
        this.WED_DELAY = wed_delay;
        this.QUEUE_DELAY = queue_delay;
        this.POST_DELAY = post_delay;
        this.CLUST_DELAY = cluster_delay;
        this.bandwidth = bandwidth;
    }

    /** @return logical bandwidth */
    public double getBandwidth() {
        return this.bandwidth;
    }

    /** @return maximum number of jobs per WED sub-batch (0 means the whole batch is not split) */
    public int getWEDInterval() {
        return this.WED_INTERVAL;
    }

    /** @return map from task depth to the queue delay generator */
    public Map<Integer, DistributionGenerator> getQueueDelay() {
        return this.QUEUE_DELAY;
    }

    /** @return map from task depth to the post-processing delay generator */
    public Map<Integer, DistributionGenerator> getPostDelay() {
        return this.POST_DELAY;
    }

    /** @return map from task depth to the workflow engine delay generator */
    public Map<Integer, DistributionGenerator> getWEDDelay() {
        return this.WED_DELAY;
    }

    /** @return map from task depth to the clustering delay generator */
    public Map<Integer, DistributionGenerator> getClustDelay() {
        return this.CLUST_DELAY;
    }

    /**
     * Samples the clustering delay by job depth.
     *
     * @param cl clustered job cloudlet
     * @return delay for the matching depth; the depth-0 default or {@code 0.0} when no
     *         dedicated entry exists
     */
    public double getClustDelay(Cloudlet cl) {
        double delay = 0.0;
        if (this.CLUST_DELAY == null) {
            return delay;
        }
        if (cl != null) {
            Job job = (Job) cl;

            if (this.CLUST_DELAY.containsKey(job.getDepth())) {
                delay = this.CLUST_DELAY.get(job.getDepth()).getNextSample();
            } else if (this.CLUST_DELAY.containsKey(0)) {
                delay = this.CLUST_DELAY.get(0).getNextSample();
            } else {
                delay = 0.0;
            }

        } else {
            Log.printLine("Not yet supported");
        }
        return delay;
    }

    /**
     * Samples the queue delay by job depth.
     *
     * @param cl clustered job cloudlet
     * @return delay for the matching depth; the depth-0 default or {@code 0.0} when no
     *         dedicated entry exists
     */
    public double getQueueDelay(Cloudlet cl) {
        double delay = 0.0;

        if (this.QUEUE_DELAY == null) {
            return delay;
        }
        if (cl != null) {
            Job job = (Job) cl;

            if (this.QUEUE_DELAY.containsKey(job.getDepth())) {
                delay = this.QUEUE_DELAY.get(job.getDepth()).getNextSample();
            } else if (this.QUEUE_DELAY.containsKey(0)) {
                delay = this.QUEUE_DELAY.get(0).getNextSample();
            } else {
                delay = 0.0;
            }

        } else {
            Log.printLine("Not yet supported");
        }
        return delay;
    }

    /**
     * Samples the post-processing delay by job depth.
     *
     * @param job clustered job
     * @return delay for the matching depth; the depth-0 default or {@code 0.0} when no
     *         dedicated entry exists
     */
    public double getPostDelay(Job job) {
        double delay = 0.0;

        if (this.POST_DELAY == null) {
            return delay;
        }
        if (job != null) {

            if (this.POST_DELAY.containsKey(job.getDepth())) {
                delay = this.POST_DELAY.get(job.getDepth()).getNextSample();
            } else if (this.POST_DELAY.containsKey(0)) {
                // Depth 0 is the default delay entry in the historical configuration.
                delay = this.POST_DELAY.get(0).getNextSample();
            } else {
                delay = 0.0;
            }

        } else {
            Log.printLine("Not yet supported");
        }
        return delay;
    }

    /**
     * Samples the workflow engine delay from the depth of the first job in the batch.
     *
     * @param list jobs to submit
     * @return delay for the matching depth; {@code 0.0} for an empty list or when no delay
     *         is configured
     */
    public double getWEDDelay(List list) {
        double delay = 0.0;

        if (this.WED_DELAY == null) {
            return delay;
        }
        if (!list.isEmpty()) {
            Job job = (Job) list.get(0);
            if (this.WED_DELAY.containsKey(job.getDepth())) {
                delay = this.WED_DELAY.get(job.getDepth()).getNextSample();
            } else if (this.WED_DELAY.containsKey(0)) {
                delay = this.WED_DELAY.get(0).getNextSample();
            } else {
                delay = 0.0;
            }

        } else {
            // An empty batch has no job from which to select a depth; the historical
            // semantics is zero delay.
        }
        return delay;
    }
}
