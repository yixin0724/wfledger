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
import org.workflowsim.examples.WorkflowSimBasicExample1;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * Historical horizontal clustering teaching example that specifies the clustering strength
 * through {@code clusters.size}.
 *
 * <p>It contrasts with {@code HorizontalClusteringExample2}, which configures
 * {@code clusters.num}, and serves to explain the legacy horizontal clustering parameters.
 * Task clustering is outside the scope supported by the current standard research runner,
 * so this class is not a standard research experiment entry point.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Dec 29, 2013
 */
public class HorizontalClusteringExample1 extends WorkflowSimBasicExample1 {

    // ======================== Static example entry point ========================
    /**
     * Run the historical horizontal clustering scenario on a single datacenter and a single
     * storage.
     */
    public static void main(String[] args) {

        try {
            ExampleCliSupport.begin("horizontal-clustering-size");
            // Step 1: configure the historical static WorkflowSim parameters.
            /**
             * Number of VMs requested; if the datacenter or Host resources are insufficient,
             * fewer VMs than requested may actually be created.
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

            /**
             * Horizontal clustering requires a clustering delay mapping for every level; when
             * this overhead is not under study, use a zero-valued distribution instead of
             * passing {@code null}.
             */
            Map<Integer, DistributionGenerator> clusteringDelay = new HashMap();
            /**
             * This Montage example assumes at most 11 horizontal levels.
             */
            int maxLevel = 11;
            for (int level = 0; level < maxLevel; level++) {
                DistributionGenerator cluster_delay =
                        new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL, 10.0, 1.0);
                clusteringDelay.put(level, cluster_delay); // Install the same clustering delay distribution per level.
            }
            // Install the clustering delays into the overhead parameters.
            OverheadParameters op = new OverheadParameters(0, null, null, null, clusteringDelay, 0);

            /**
             * Select horizontal clustering.
             */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.HORIZONTAL;
            /**
             * Only one of {@code clusters.num} and {@code clusters.size} may be specified:
             * the former is the number of clustered jobs per level, the latter the number of
             * tasks per clustered job; their product is the total task count per level.
             * Here {@code clusters.size = 2} is specified, so each job holds two tasks.
             */
            ClusteringParameters cp = new ClusteringParameters(0, 2, method, null);

            /**
             * Initialize the historical static parameters and the file replica catalog.
             */
            Parameters.init(vmNum, daxPath, null, null, op, cp, sch_method, pln_method, null, 0);
            ReplicaCatalog.init(file_system);

            // Initialize CloudSim before creating any simulated entity.
            int num_user = 1; // Number of simulated users.
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false; // Whether to record the CloudSim event trace.

            // Initialize the CloudSim event engine.
            CloudSim.init(num_user, calendar, trace_flag);

            WorkflowDatacenter datacenter0 = createDatacenter("Datacenter_0");

            /** Create a workflow planner that holds a single scheduler. */
            WorkflowPlanner wfPlanner = new WorkflowPlanner("planner_0", 1);
            /** Obtain the workflow engine associated with the planner. */
            WorkflowEngine wfEngine = wfPlanner.getWorkflowEngine();
            /** Create the VM list; the userId of a VM is the id of its scheduler. */
            List<CondorVM> vmlist0 = createVM(wfEngine.getSchedulerId(0), Parameters.getVmNum());

            /** Submit the VM list to the workflow engine. */
            wfEngine.submitVmList(vmlist0, 0);

            /** Bind the datacenter to that scheduler. */
            wfEngine.bindSchedulerDatacenter(datacenter0.getId(), 0);

            CloudSim.startSimulation();
            List<Job> outputList0 = wfEngine.getJobsReceivedList();
            CloudSim.stopSimulation();
            printJobList(outputList0);
            ExampleCliSupport.reportCompletion(outputList0);
        } catch (Exception e) {
            throw ExampleCliSupport.failure("horizontal-clustering-size", e);
        } finally {
            ExampleCliSupport.end();
        }
    }
}
