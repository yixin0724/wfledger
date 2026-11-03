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
 * Historical vertical clustering teaching example.
 *
 * <p>Unlike horizontal clustering, vertical clustering merges tasks along the same
 * dependency branch. The capability is retained for compatibility and teaching and is not
 * one of the supported clustering tracks of the current standard research runner.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Dec 29, 2013
 */
public class VerticalClusteringExample1 extends HorizontalClusteringExample1 {

    // ======================== Static example entry point ========================
    /**
     * Run the historical vertical clustering scenario on a single datacenter and a single
     * storage.
     */
    public static void main(String[] args) {

        try {
            ExampleCliSupport.begin("vertical-clustering");
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
             * Vertical clustering requires a clustering delay mapping for every level; when
             * this overhead is not under study, use a zero-valued distribution instead of
             * passing {@code null}.
             */
            Map<Integer, DistributionGenerator> clusteringDelay = new HashMap();
            /** This Montage example assumes at most 11 horizontal levels. */
            int maxLevel = 11;
            for (int level = 0; level < maxLevel; level++) {
                DistributionGenerator cluster_delay =
                        new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL, 1.0, 1.0);
                clusteringDelay.put(level, cluster_delay); // Install the same clustering delay distribution per level.
            }
            // Install the clustering delays into the overhead parameters.
            OverheadParameters op = new OverheadParameters(0, null, null, null, clusteringDelay, 0);

            /** Select vertical clustering. */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.VERTICAL;
            ClusteringParameters cp = new ClusteringParameters(0, 0, method, null);

            /**
             * Initialize the historical static parameters. The reducer is set to
             * {@code montage} here to remove duplicate dependencies in the Montage workflow;
             * it is only a performance optimization and not a requirement of vertical
             * clustering.
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
            throw ExampleCliSupport.failure("vertical-clustering", e);
        } finally {
            ExampleCliSupport.end();
        }
    }
}
