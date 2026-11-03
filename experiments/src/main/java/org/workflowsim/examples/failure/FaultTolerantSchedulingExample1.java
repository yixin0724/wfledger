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
package org.workflowsim.examples.failure;

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
import org.workflowsim.examples.WorkflowSimBasicExample1;
import org.workflowsim.failure.FailureGenerator;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * Historical teaching example that uses the legacy failure generation and retry machinery.
 *
 * <p>It illustrates the legacy assembly of failure monitoring, distribution generation, and
 * retry. The distribution parameters in this example are scenario assumptions and do not
 * represent calibration against a real platform; this class is not a standard research
 * experiment entry point.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Dec 31, 2013
 */
public class FaultTolerantSchedulingExample1 extends WorkflowSimBasicExample1 {

    // ======================== Static example entry point ========================
    /**
     * Run the historical fault-tolerant scheduling scenario on a single datacenter and a
     * single storage.
     */
    public static void main(String[] args) {

        try {
            ExampleCliSupport.begin("fault-tolerant-scheduling");
            // Step 1: configure the historical static WorkflowSim parameters.
            /**
             * Number of VMs requested; if resources are insufficient, fewer VMs than
             * requested may actually be created.
             */
            int vmNum = 20; // Number of VMs.
            /** Adjust the DAX path to the dataset location on this machine. */
            String daxPath = ExampleCliSupport.singleDax(args, ExampleCliSupport.DEFAULT_DAX_PATH);
            File daxFile = new File(daxPath);
            if (!daxFile.exists()) {
                Log.printLine("Warning: Please replace daxPath with the physical path in your working environment!");
                return;
            }
            /* Fault tolerance parameters. */
            /**
             * MONITOR_JOB groups by task depth, MONITOR_VM groups by VM identifier,
             * MONITOR_ALL aggregates everything, and MONITOR_NONE records nothing.
             */
            FailureParameters.FTCMonitor ftc_monitor = FailureParameters.FTCMonitor.MONITOR_ALL;
            /**
             * FTCFailure decides the granularity at which the generator selects a failure
             * arrival distribution.
             */
            FailureParameters.FTCFailure ftc_failure = FailureParameters.FTCFailure.FAILURE_ALL;
            /**
             * This example disables fault-tolerant clustering; after a failure it follows the
             * NOOP per-task retry compatibility path.
             */
            FailureParameters.FTCluteringAlgorithm ftc_method =
                    FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP;
            /**
             * A single abstract failure arrival distribution; its parameters are example
             * assumptions, not the result of platform calibration.
             */
            DistributionGenerator[][] failureGenerators = new DistributionGenerator[1][1];
            failureGenerators[0][0] = new DistributionGenerator(
                    DistributionGenerator.DistributionFamily.WEIBULL,
                    ExampleCliSupport.LEGACY_DEMO_FAILURE_INTERVAL_SCALE,
                    1.0,
                    30,
                    300,
                    0.78);

            /** The MINMIN scheduler decides the mapping in this scenario, so the planner is disabled. */
            Parameters.SchedulingAlgorithm sch_method = Parameters.SchedulingAlgorithm.MINMIN;
            Parameters.PlanningAlgorithm pln_method = Parameters.PlanningAlgorithm.INVALID;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.SHARED;

            /** Inject no additional overhead. */
            OverheadParameters op = new OverheadParameters(0, null, null, null, null, 0);

            /** Perform no regular task clustering. */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.NONE;
            ClusteringParameters cp = new ClusteringParameters(0, 0, method, null);

            /** Initialize the legacy failure state, static parameters, and file directories. */
            FailureParameters.init(ftc_method, ftc_monitor, ftc_failure, failureGenerators);
            Parameters.init(vmNum, daxPath, null, null, op, cp, sch_method, pln_method, null, 0);
            ReplicaCatalog.init(file_system);

            FailureMonitor.init();
            FailureGenerator.init();

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
            throw ExampleCliSupport.failure("fault-tolerant-scheduling", e);
        } finally {
            ExampleCliSupport.end();
        }
    }
}
