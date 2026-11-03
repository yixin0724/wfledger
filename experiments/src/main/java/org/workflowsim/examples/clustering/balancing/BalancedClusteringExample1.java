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
package org.workflowsim.examples.clustering.balancing;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.examples.ExampleCliSupport;
import org.workflowsim.examples.clustering.HorizontalClusteringExample1;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * Historical balanced horizontal clustering teaching example that uses the runtime balancing method.
 *
 * <p>The example shows how to select a legacy balancing strategy and pass it to the clusterer.
 * It does not represent a calibrated resource balancing model, and it is not an experiment entry
 * point of the current standard research runner.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Dec 29, 2013
 */
public class BalancedClusteringExample1 extends HorizontalClusteringExample1 {

    // ======================== Static example entry point ========================
    /**
     * Run the historical balanced clustering scenario with a single datacenter and a single storage.
     */
    public static void main(String[] args) {

        try {
            ExampleCliSupport.begin("balanced-clustering");
            Map<String, String> options =
                    ExampleCliSupport.parseValueOptions(args, "-c", "-d", "-l", "-q", "-e", "-p", "-i");
            String code = ExampleCliSupport.allowedCharacters(
                    "-c", ExampleCliSupport.optionOrDefault(options, "-c", "i"), "vcridh");
            String daxPath = ExampleCliSupport.requireDax(
                    ExampleCliSupport.optionOrDefault(options, "-d", ExampleCliSupport.DEFAULT_DAX_PATH));
            double c_delay =
                    ExampleCliSupport.nonNegativeFinite("-l", ExampleCliSupport.optionOrDefault(options, "-l", "0"));
            double q_delay =
                    ExampleCliSupport.nonNegativeFinite("-q", ExampleCliSupport.optionOrDefault(options, "-q", "0"));
            double e_delay =
                    ExampleCliSupport.nonNegativeFinite("-e", ExampleCliSupport.optionOrDefault(options, "-e", "0"));
            double p_delay =
                    ExampleCliSupport.nonNegativeFinite("-p", ExampleCliSupport.optionOrDefault(options, "-p", "0"));
            int interval =
                    ExampleCliSupport.nonNegativeInt("-i", ExampleCliSupport.optionOrDefault(options, "-i", "0"));

            // Step 1: initialize the historical WorkflowSim static parameters.
            /**
             * Number of requested VMs; fewer may actually be created when datacenter or host
             * resources are insufficient.
             */
            int vmNum = 20; // Number of VMs.
            /**
             * The scheduler decides the mapping in this scenario, so the planner is disabled to keep
             * it from overwriting the scheduling result.
             */
            Parameters.SchedulingAlgorithm sch_method = Parameters.SchedulingAlgorithm.DATA;
            Parameters.PlanningAlgorithm pln_method = Parameters.PlanningAlgorithm.INVALID;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.LOCAL;

            /**
             * Clustering, queue, postscript, and engine delays are passed per level; an overhead that
             * is not studied is expressed as zero.
             */
            Map<Integer, DistributionGenerator> clusteringDelay = new HashMap();
            Map<Integer, DistributionGenerator> queueDelay = new HashMap();
            Map<Integer, DistributionGenerator> postscriptDelay = new HashMap();
            Map<Integer, DistributionGenerator> engineDelay = new HashMap();
            /** This example application assumes at most 11 horizontal levels. */
            int maxLevel = 11;
            for (int level = 0; level < maxLevel; level++) {
                if (c_delay != 0.0) {
                    DistributionGenerator cluster_delay =
                            new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL, c_delay, 1.0);
                    clusteringDelay.put(level, cluster_delay);
                }
                if (q_delay != 0.0) {
                    DistributionGenerator queue_delay =
                            new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL, q_delay, 1.0);
                    queueDelay.put(level, queue_delay);
                }
                if (p_delay != 0.0) {
                    DistributionGenerator postscript_delay =
                            new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL, p_delay, 1.0);
                    postscriptDelay.put(level, postscript_delay);
                }
                if (e_delay != 0.0) {
                    DistributionGenerator engine_delay =
                            new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL, e_delay, 1.0);
                    engineDelay.put(level, engine_delay);
                }
            }

            OverheadParameters op =
                    new OverheadParameters(interval, engineDelay, queueDelay, postscriptDelay, clusteringDelay, 0);

            /** Select balanced clustering. */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.BALANCED;
            /**
             * Accepted compatibility values of {@code code}: {@code r} is horizontal runtime
             * balancing, {@code d} is horizontal distance balancing, {@code i} is horizontal impact
             * factor balancing, and {@code h} is horizontal random balancing; any other value keeps
             * the historical plain horizontal clustering path.
             */
            ClusteringParameters cp = new ClusteringParameters(20, 0, method, code);

            /** Initialize the historical static parameters and the file replica catalog. */
            Parameters.init(vmNum, daxPath, null, null, op, cp, sch_method, pln_method, null, 0);
            ReplicaCatalog.init(file_system);

            // Initialize CloudSim before creating simulation entities.
            int num_user = 1; // Number of simulated users.
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false; // Whether to record the CloudSim event trace.

            // Initialize the CloudSim event engine.
            CloudSim.init(num_user, calendar, trace_flag);

            WorkflowDatacenter datacenter0 = createDatacenter("Datacenter_0");

            /** Create a workflow planner that contains a single scheduler. */
            WorkflowPlanner wfPlanner = new WorkflowPlanner("planner_0", 1);
            /** Obtain the corresponding workflow execution engine. */
            WorkflowEngine wfEngine = wfPlanner.getWorkflowEngine();
            /**
             * Create the VM list; the VM userId corresponds to the identifier of the scheduler that
             * manages it.
             */
            List<CondorVM> vmlist0 = createVM(wfEngine.getSchedulerId(0), Parameters.getVmNum());

            /** Submit the VM list to the workflow execution engine. */
            wfEngine.submitVmList(vmlist0, 0);

            /** Bind the datacenter to that scheduler. */
            wfEngine.bindSchedulerDatacenter(datacenter0.getId(), 0);

            CloudSim.startSimulation();
            List<Job> outputList0 = wfEngine.getJobsReceivedList();
            CloudSim.stopSimulation();
            printJobList(outputList0);
            ExampleCliSupport.reportCompletion(outputList0);
        } catch (Exception e) {
            throw ExampleCliSupport.failure("balanced-clustering", e);
        } finally {
            ExampleCliSupport.end();
        }
    }
}
