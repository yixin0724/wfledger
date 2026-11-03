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
package org.workflowsim.examples.clustering;

import java.io.File;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.examples.ExampleCliSupport;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * Historical horizontal clustering teaching example with scheduling overhead.
 *
 * <p>It builds on {@code HorizontalClusteringExample2} and additionally configures workflow
 * engine, queue, and post-processing delays to observe model behavior under these declarative
 * overheads. It is neither a calibrated performance prediction nor a current standard research
 * experiment entry point.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Dec 29, 2013
 */
public class HorizontalClusteringExample3 extends HorizontalClusteringExample1 {

    // ======================== Static example entry point ========================
    /**
     * Run the historical horizontal clustering scenario with overheads.
     */
    public static void main(String[] args) {

        try {
            ExampleCliSupport.begin("horizontal-clustering-overhead");
            // Step 1: configure the historical WorkflowSim static parameters.
            /**
             * Number of requested VMs; fewer may actually be created when the datacenter or
             * host resources are insufficient.
             */
            int vmNum = 20; // Number of VMs.
            /**
             * Adjust the DAX path to the dataset location on this machine.
             */
            String daxPath = ExampleCliSupport.singleDax(args, ExampleCliSupport.DEFAULT_DAX_PATH);
            File daxFile = new File(daxPath);
            if (!daxFile.exists()) {
                Log.printLine("Warning: Please replace daxPath with the physical path in your working environment!");
                return;
            }

            /**
             * The MINMIN scheduler decides the mapping in this scenario, so the planner is
             * disabled to keep it from overriding the scheduling result.
             */
            Parameters.SchedulingAlgorithm sch_method = Parameters.SchedulingAlgorithm.MINMIN;
            Parameters.PlanningAlgorithm pln_method = Parameters.PlanningAlgorithm.INVALID;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.SHARED;
            /** This Montage example assumes at most 11 horizontal levels. */
            int maxLevel = 11;
            /**
             * Clustering, queue, post-processing, and engine delays are all provided per DAG
             * level; use a zero value for overhead that is not under study, and do not pass
             * {@code null} in place of a required mapping.
             */
            Map<Integer, DistributionGenerator> clusteringDelay = new HashMap();
            Map<Integer, DistributionGenerator> queueDelay = new HashMap();
            Map<Integer, DistributionGenerator> postscriptDelay = new HashMap();
            Map<Integer, DistributionGenerator> engineDelay = new HashMap();
            /**
             * The workflow engine forms release cycles by interval. This example uses 5, meaning
             * release in batches of five jobs with one engine delay counted per cycle; this is a
             * scenario assumption, not a platform measurement.
             */
            int interval = 5;
            for (int level = 0; level < maxLevel; level++) {
                DistributionGenerator cluster_delay =
                        new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL, 1.0, 1.0);
                clusteringDelay.put(level, cluster_delay);
                DistributionGenerator queue_delay =
                        new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL, 10.0, 1.0);
                queueDelay.put(level, queue_delay);
                DistributionGenerator postscript_delay =
                        new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL, 10.0, 1.0);
                postscriptDelay.put(level, postscript_delay);
                DistributionGenerator engine_delay =
                        new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL, 50.0, 1.0);
                engineDelay.put(level, engine_delay);
            }
            // Install the per-level overhead maps into the overhead parameters.
            OverheadParameters op =
                    new OverheadParameters(interval, engineDelay, queueDelay, postscriptDelay, clusteringDelay, 0);

            /** Select horizontal clustering. */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.HORIZONTAL;
            /**
             * Only one of {@code clusters.num} and {@code clusters.size} may be specified: the
             * former is the number of clustered jobs per level, and the latter is the number of
             * tasks per clustered job; here every level creates 20 clustered jobs.
             */
            ClusteringParameters cp = new ClusteringParameters(20, 0, method, null);

            /** Initialize the historical static parameters and the file replica catalog. */
            Parameters.init(vmNum, daxPath, null, null, op, cp, sch_method, pln_method, null, 0);
            ReplicaCatalog.init(file_system);

            // Initialize CloudSim before creating any simulated entity.
            int num_user = 1; // Number of simulated users.
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false; // Whether to record the CloudSim event trace.

            // Initialize the CloudSim event engine.
            CloudSim.init(num_user, calendar, trace_flag);

            WorkflowDatacenter datacenter0 = createDatacenter("Datacenter_0");

            /** Create a workflow planner with a single scheduler. */
            WorkflowPlanner wfPlanner = new WorkflowPlanner("planner_0", 1);
            /** Obtain the corresponding workflow execution engine. */
            WorkflowEngine wfEngine = wfPlanner.getWorkflowEngine();
            /** Create the VM list; a VM's userId is the identifier of the scheduler that manages it. */
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
            throw ExampleCliSupport.failure("horizontal-clustering-overhead", e);
        } finally {
            ExampleCliSupport.end();
        }
    }
}
