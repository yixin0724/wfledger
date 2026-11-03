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
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.examples.ExampleCliSupport;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * Historical cost model teaching example that outputs the result computed per VM.
 *
 * <p>It contrasts with the example that bills per datacenter and serves to explain the
 * legacy cost fields; these values are model observations, not cloud provider prices or
 * bill predictions, and this class is not a standard research experiment entry point.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 6, 2014
 */
public class WorkflowSimCostExample2 extends WorkflowSimCostExample1 {

    // ======================== Static example entry point ========================
    /**
     * Run the per-VM cost scenario on a single datacenter and a single storage.
     */
    public static void main(String[] args) {
        try {
            ExampleCliSupport.begin("cost-vm");
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
            /**
             * The MINMIN scheduler decides the mapping in this scenario, so the planner is
             * disabled. On this historical path, planning and scheduling are not two mapping
             * stages that can be optimized independently at the same time.
             */
            Parameters.SchedulingAlgorithm sch_method = Parameters.SchedulingAlgorithm.MINMIN;
            Parameters.PlanningAlgorithm pln_method = Parameters.PlanningAlgorithm.INVALID;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.LOCAL;

            /** Select the legacy per-VM cost model; the default model bills per datacenter. */
            Parameters.setCostModel(Parameters.CostModel.VM);

            /** Inject no additional overhead. */
            OverheadParameters op = new OverheadParameters(0, null, null, null, null, 0);

            /** Perform no task clustering. */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.NONE;
            ClusteringParameters cp = new ClusteringParameters(0, 0, method, null);

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
            throw ExampleCliSupport.failure("cost-vm", e);
        } finally {
            ExampleCliSupport.end();
        }
    }

    protected static List<CondorVM> createVM(int userId, int vms) {

        // Create the VM container that is later submitted to the workflow engine.
        LinkedList<CondorVM> list = new LinkedList<>();
        // Abstract resource specification of the VM.
        long size = 10000; // Image size (MB)
        int ram = 512; // VM memory (MB)
        int mips = 1000;
        long bw = 1000;
        int pesNumber = 1; // Number of CPU cores
        String vmm = "Xen"; // Virtual machine monitor name

        // Create the VMs with abstract unit price fields.
        CondorVM[] vm = new CondorVM[vms];
        double cost = 3.0; // Abstract computation unit price.
        double costPerMem = 0.05; // Abstract memory unit price.
        double costPerStorage = 0.1; // Abstract storage unit price.
        double costPerBw = 0.1; // Abstract bandwidth unit price.
        for (int i = 0; i < vms; i++) {
            double ratio = 1.0;
            vm[i] = new CondorVM(
                    i,
                    userId,
                    mips * ratio,
                    pesNumber,
                    ram,
                    bw,
                    size,
                    vmm,
                    cost,
                    costPerMem,
                    costPerStorage,
                    costPerBw,
                    new CloudletSchedulerSpaceShared());
            list.add(vm[i]);
        }
        return list;
    }
}
