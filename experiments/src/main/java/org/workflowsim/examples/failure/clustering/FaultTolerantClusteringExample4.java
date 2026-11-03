/**
 * Copyright 2013-2014 University Of Southern California
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
package org.workflowsim.examples.failure.clustering;

import java.io.File;
import java.util.Calendar;
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.examples.ExampleCliSupport;
import org.workflowsim.failure.FailureGenerator;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * Historical fault-tolerant clustering teaching example that models task failures by VM index.
 *
 * <p>It contrasts with {@code FaultTolerantClusteringExample1} and shows a VM-dimension failure
 * generation assumption. It is not a standard research experiment entry point, and it must not
 * be used to claim real VM failure rates.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Mar 2, 2014
 */
public class FaultTolerantClusteringExample4 extends FaultTolerantClusteringExample1 {

    // ======================== Static example entry point ========================
    /**
     * Run the VM-dimension failure scenario with a single datacenter and a single storage.
     */
    public static void main(String[] args) {

        try {
            ExampleCliSupport.begin("fault-clustering-vm");
            // Step 1: configure the historical WorkflowSim static parameters.
            /**
             * Number of requested VMs; fewer may actually be created when resources are
             * insufficient.
             */
            int vmNum = 20; // Number of VMs.
            /** Adjust the DAX path to the dataset location on this machine. */
            String daxPath = ExampleCliSupport.singleDax(args, ExampleCliSupport.DEFAULT_DAX_PATH);
            File daxFile = new File(daxPath);
            if (!daxFile.exists()) {
                Log.printLine("Warning: Please replace daxPath with the physical path in your working environment!");
                return;
            }
            /* Fault-tolerance parameters. */
            /**
             * MONITOR_JOB records by task depth, MONITOR_VM records by VM identifier, MONITOR_ALL
             * aggregates every record, and MONITOR_NONE records no failure.
             */
            FailureParameters.FTCMonitor ftc_monitor = FailureParameters.FTCMonitor.MONITOR_VM;
            /** FTCFailure decides the granularity at which the generator selects the failure arrival distribution. */
            FailureParameters.FTCFailure ftc_failure = FailureParameters.FTCFailure.FAILURE_VM;
            /** This historical example selects the estimator-based dynamic fault-tolerant reclustering strategy. */
            FailureParameters.FTCluteringAlgorithm ftc_method = FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_DR;
            /** The generator matrix is built per VM and per workflow level. */
            int maxLevel = 11;
            DistributionGenerator[][] failureGenerators = new DistributionGenerator[vmNum][maxLevel];
            /** Model the failure arrival process per VM; each VM holds its own distribution instance. */
            for (int vmId = 0; vmId < vmNum; vmId++) {
                DistributionGenerator generator = new DistributionGenerator(
                        DistributionGenerator.DistributionFamily.WEIBULL,
                        ExampleCliSupport.LEGACY_CLUSTERING_DEMO_FAILURE_INTERVAL_SCALE,
                        1.0,
                        30,
                        300,
                        0.78);
                for (int level = 0; level < maxLevel; level++) {
                    failureGenerators[vmId][level] = generator;
                }
            }
            /** The mapping is decided by the MINMIN scheduler in this scenario, so the planner is disabled. */
            Parameters.SchedulingAlgorithm sch_method = Parameters.SchedulingAlgorithm.MINMIN;
            Parameters.PlanningAlgorithm pln_method = Parameters.PlanningAlgorithm.INVALID;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.SHARED;

            /** No additional overhead is injected. */
            OverheadParameters op = new OverheadParameters(0, null, null, null, null, 0);

            /**
             * Regular task clustering is disabled; the fault-tolerant recovery strategy is handled
             * separately after a failure.
             */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.NONE;
            ClusteringParameters cp = new ClusteringParameters(0, 0, method, null);

            /** Initialize the legacy failure state, static parameters, and file catalog. */
            FailureParameters.init(ftc_method, ftc_monitor, ftc_failure, failureGenerators);
            Parameters.init(vmNum, daxPath, null, null, op, cp, sch_method, pln_method, null, 0);
            ReplicaCatalog.init(file_system);

            FailureMonitor.init();
            FailureGenerator.init();

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
            throw ExampleCliSupport.failure("fault-clustering-vm", e);
        } finally {
            ExampleCliSupport.end();
        }
    }
}
