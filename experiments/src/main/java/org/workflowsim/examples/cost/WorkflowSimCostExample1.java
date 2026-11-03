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
package org.workflowsim.examples.cost;

import java.io.File;
import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
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
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * Historical cost model teaching example that outputs the result computed per datacenter.
 *
 * <p>The values shown by this example combine transferred data bytes, task runtime, and an
 * abstract unit cost. They serve to explain the CloudSim cost fields; they are not a replay of
 * cloud provider billing, and this class is not a standard research experiment entry point.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Feb 14, 2014
 */
public class WorkflowSimCostExample1 extends WorkflowSimBasicExample1 {

    // ======================== Static example entry point ========================
    /**
     * Run the historical cost scenario with a single datacenter and a single storage.
     */
    public static void main(String[] args) {

        try {
            ExampleCliSupport.begin("cost-datacenter");
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
            /**
             * The mapping is decided by the MINMIN scheduler in this scenario, so the planner is
             * disabled. Planning and scheduling are not two independently optimizable mapping stages
             * on this historical path.
             */
            Parameters.SchedulingAlgorithm sch_method = Parameters.SchedulingAlgorithm.MINMIN;
            Parameters.PlanningAlgorithm pln_method = Parameters.PlanningAlgorithm.INVALID;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.LOCAL;

            /** No additional overhead is injected. */
            OverheadParameters op = new OverheadParameters(0, null, null, null, null, 0);

            /** Perform no task clustering. */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.NONE;
            ClusteringParameters cp = new ClusteringParameters(0, 0, method, null);

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
            throw ExampleCliSupport.failure("cost-datacenter", e);
        } finally {
            ExampleCliSupport.end();
        }
    }

    /**
     * Print the Job summary together with its abstract processing cost.
     *
     * @param list list of completed Jobs
     */
    protected static void printJobList(List<Job> list) {
        int size = list.size();
        Job job;

        String indent = "    ";
        Log.printLine();
        Log.printLine("========== OUTPUT ==========");
        Log.printLine("Cloudlet ID" + indent + "STATUS" + indent
                + "Data center ID" + indent + "VM ID" + indent + indent + "Time" + indent
                + "Start Time" + indent + "Finish Time" + indent + "Depth" + indent + "Cost");

        DecimalFormat dft = new DecimalFormat("###.##");
        double cost = 0.0;
        for (int i = 0; i < size; i++) {
            job = list.get(i);
            Log.print(indent + job.getCloudletId() + indent + indent);

            cost += job.getProcessingCost();
            if (job.getCloudletStatus() == Cloudlet.SUCCESS) {
                Log.print("SUCCESS");
                Log.printLine(indent
                        + indent
                        + job.getResourceId()
                        + indent
                        + indent
                        + indent
                        + job.getVmId()
                        + indent
                        + indent
                        + indent
                        + dft.format(job.getActualCPUTime())
                        + indent
                        + indent
                        + dft.format(job.getExecStartTime())
                        + indent
                        + indent
                        + indent
                        + dft.format(job.getFinishTime())
                        + indent
                        + indent
                        + indent
                        + job.getDepth()
                        + indent
                        + indent
                        + indent
                        + dft.format(job.getProcessingCost()));
            } else if (job.getCloudletStatus() == Cloudlet.FAILED) {
                Log.print("FAILED");
                Log.printLine(indent
                        + indent
                        + job.getResourceId()
                        + indent
                        + indent
                        + indent
                        + job.getVmId()
                        + indent
                        + indent
                        + indent
                        + dft.format(job.getActualCPUTime())
                        + indent
                        + indent
                        + dft.format(job.getExecStartTime())
                        + indent
                        + indent
                        + indent
                        + dft.format(job.getFinishTime())
                        + indent
                        + indent
                        + indent
                        + job.getDepth()
                        + indent
                        + indent
                        + indent
                        + dft.format(job.getProcessingCost()));
            }
        }
        Log.printLine("The total cost is " + dft.format(cost));
    }
}
