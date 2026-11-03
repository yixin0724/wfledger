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
package org.workflowsim.examples;

import java.io.File;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.CloudletSchedulerDynamicWorkload;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * Historical teaching example that uses {@code CloudletSchedulerDynamicWorkload} as the
 * VM-local scheduler.
 *
 * <p>This example configures the historical static API directly and only serves to explain
 * how the dynamic workload scheduler connects to WorkflowSim; it is not a standard research
 * experiment entry point and provides no auditable experiment configuration or artifacts.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Oct 13, 2013
 */
public class DynamicWorkloadExample1 extends WorkflowSimBasicExample1 {

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

        // Create the VMs that use the dynamic workload scheduler.
        CondorVM[] vm = new CondorVM[vms];

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
                    new CloudletSchedulerDynamicWorkload(mips * ratio, pesNumber));
            list.add(vm[i]);
        }

        return list;
    }

    // ======================== Static example entry point ========================
    /**
     * Run the historical dynamic workload scenario on a single datacenter and a single
     * storage.
     */
    public static void main(String[] args) {

        try {
            ExampleCliSupport.begin("dynamic-workload");
            // Step 1: configure the historical static WorkflowSim parameters.

            /**
             * When the datacenter or Host capacity is insufficient, fewer VMs may be created
             * than declared.
             */
            int vmNum = 20; // Number of VMs
            /**
             * The in-project workflow path is used by default; adjust it when running from
             * another working directory.
             */
            String daxPath = ExampleCliSupport.singleDax(args, ExampleCliSupport.DEFAULT_DAX_PATH);
            File daxFile = new File(daxPath);
            if (!daxFile.exists()) {
                Log.printLine("Warning: Please replace daxPath with the physical path in your working environment!");
                return;
            }

            /**
             * The teaching point of this example is {@link CloudletSchedulerDynamicWorkload}
             * (the MIPS of a VM change dynamically over time); the planner is only a
             * supporting part. R9 removed the historical HEFT label that did not align with
             * the execution model and switched to an unconstrained RANDOM planning: it is
             * compatible with the LOCAL file system and the dynamic-MIPS VM scheduler used
             * here (the SHARED_STORAGE_* family requires SHARED storage plus SPACE_SHARED
             * VMs, which conflicts with this setup).
             */
            Parameters.SchedulingAlgorithm sch_method = Parameters.SchedulingAlgorithm.STATIC;
            Parameters.PlanningAlgorithm pln_method = Parameters.PlanningAlgorithm.RANDOM;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.LOCAL;

            /**
             * Inject no additional overhead.
             */
            OverheadParameters op = new OverheadParameters(0, null, null, null, null, 0);

            /**
             * Perform no task clustering.
             */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.NONE;
            ClusteringParameters cp = new ClusteringParameters(0, 0, method, null);

            /**
             * Initialize the historical global parameters and file directories.
             */
            Parameters.init(vmNum, daxPath, null, null, op, cp, sch_method, pln_method, null, 0);
            ReplicaCatalog.init(file_system);

            // Complete the initialization before creating any CloudSim entity.
            int num_user = 1; // Number of grid users
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false; // Whether to record the event trace

            // Initialize CloudSim.
            CloudSim.init(num_user, calendar, trace_flag);

            WorkflowDatacenter datacenter0 = createDatacenter("Datacenter_0");

            /**
             * Create a workflow planner that holds one scheduler.
             */
            WorkflowPlanner wfPlanner = new WorkflowPlanner("planner_0", 1);
            /**
             * Obtain the workflow engine associated with the planner.
             */
            WorkflowEngine wfEngine = wfPlanner.getWorkflowEngine();
            /**
             * Create the VM list; the userId of a VM is the id of its scheduler.
             */
            List<CondorVM> vmlist0 = createVM(wfEngine.getSchedulerId(0), Parameters.getVmNum());

            /**
             * Submit the VM list to the workflow engine.
             */
            wfEngine.submitVmList(vmlist0, 0);

            /**
             * Bind the datacenter to the scheduler.
             */
            wfEngine.bindSchedulerDatacenter(datacenter0.getId(), 0);

            CloudSim.startSimulation();
            List<Job> outputList0 = wfEngine.getJobsReceivedList();
            CloudSim.stopSimulation();
            printJobList(outputList0);
            ExampleCliSupport.reportCompletion(outputList0);
        } catch (Exception e) {
            throw ExampleCliSupport.failure("dynamic-workload", e);
        } finally {
            ExampleCliSupport.end();
        }
    }
}
