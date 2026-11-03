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

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.HarddriveStorage;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
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
 * Historical teaching example that submits multiple DAX workflows at the same time.
 *
 * <p>This class passes several input paths through the historical static parameters and is
 * suitable for illustrating the legacy multi-workflow assembly; it is not the standard research
 * experiment entry point, and it does not replace the campaign protocol of frozen inputs, seed
 * plans, and evidence artifacts.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Nov 9, 2014
 */
public class WorkflowSimMultipleWorkflowsExample1 {

    protected static List<CondorVM> createVM(int userId, int vms) {

        // Create the VM container and submit it to the workflow execution engine afterwards.
        LinkedList<CondorVM> list = new LinkedList<>();

        // Abstract resource specification of the VMs.
        long size = 10000; // Image size (MB)
        int ram = 512; // VM memory (MB)
        int mips = 1000;
        long bw = 1000;
        int pesNumber = 1; // Number of CPU cores
        String vmm = "Xen"; // Virtual machine monitor name

        // Create the VMs with identical specifications.
        CondorVM[] vm = new CondorVM[vms];

        for (int i = 0; i < vms; i++) {
            double ratio = 1.0;
            vm[i] = new CondorVM(
                    i, userId, mips * ratio, pesNumber, ram, bw, size, vmm, new CloudletSchedulerSpaceShared());
            list.add(vm[i]);
        }

        return list;
    }

    // ======================== Static example entry point ========================
    /**
     * Run the historical multi-workflow scenario with a single datacenter and a single storage.
     */
    public static void main(String[] args) {

        try {
            ExampleCliSupport.begin("multiple-workflows");
            // Step 1: configure the historical WorkflowSim static parameters.

            /**
             * When datacenter or Host capacity is insufficient, the number of VMs actually
             * created may be smaller than the declared value.
             */
            int vmNum = 20; // Number of VMs
            /**
             * The default uses relative paths inside the project; adjust them when running from
             * another working directory.
             */
            List<String> daxPaths =
                    ExampleCliSupport.multipleDax(args, ExampleCliSupport.DEFAULT_MULTIPLE_DAX_PATHS, 3);

            /**
             * Historical MINMIN scheduling decides at the scheduling layer, so the planner is
             * disabled to keep it from overwriting that result.
             */
            Parameters.SchedulingAlgorithm sch_method = Parameters.SchedulingAlgorithm.MINMIN;
            Parameters.PlanningAlgorithm pln_method = Parameters.PlanningAlgorithm.INVALID;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.SHARED;

            /**
             * Do not inject additional overhead.
             */
            OverheadParameters op = new OverheadParameters(0, null, null, null, null, 0);

            /**
             * Do not perform any task clustering.
             */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.NONE;
            ClusteringParameters cp = new ClusteringParameters(0, 0, method, null);

            /**
             * Initialize the historical global parameters and file directories.
             */
            Parameters.init(vmNum, daxPaths, null, null, op, cp, sch_method, pln_method, null, 0);
            ReplicaCatalog.init(file_system);

            // Complete initialization before creating any CloudSim entity.
            int num_user = 1; // Number of grid users
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false; // Whether event tracing is recorded

            // Initialize CloudSim.
            CloudSim.init(num_user, calendar, trace_flag);

            WorkflowDatacenter datacenter0 = createDatacenter("Datacenter_0");

            /**
             * Create a workflow planner that contains one scheduler.
             */
            WorkflowPlanner wfPlanner = new WorkflowPlanner("planner_0", 1);
            /**
             * Obtain the workflow execution engine associated with the planner.
             */
            WorkflowEngine wfEngine = wfPlanner.getWorkflowEngine();
            /**
             * Create the VM list; a VM's userId corresponds to its scheduler ID.
             */
            List<CondorVM> vmlist0 = createVM(wfEngine.getSchedulerId(0), Parameters.getVmNum());

            /**
             * Submit the VM list to the workflow execution engine.
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
            throw ExampleCliSupport.failure("multiple-workflows", e);
        } finally {
            ExampleCliSupport.end();
        }
    }

    protected static WorkflowDatacenter createDatacenter(String name) {

        // Build the datacenter: first create a list of one or more Hosts.
        List<Host> hostList = new ArrayList<>();

        // Each Host consists of one or more PEs (CPU cores), so build the PE list first.
        for (int i = 1; i <= 20; i++) {
            List<Pe> peList1 = new ArrayList<>();
            int mips = 2000;
            // Create and add the PEs; a multi-core Host needs the matching number of PEs.
            peList1.add(new Pe(0, new PeProvisionerSimple(mips))); // PE identifier and MIPS rating.
            peList1.add(new Pe(1, new PeProvisionerSimple(mips)));

            int hostId = 0;
            int ram = 2048; // Host memory (MB).
            long storage = 1000000; // Host storage capacity.
            int bw = 10000;
            hostList.add(new Host(
                    hostId,
                    new RamProvisionerSimple(ram),
                    new BwProvisionerSimple(bw),
                    storage,
                    peList1,
                    new VmSchedulerTimeShared(peList1))); // Host scheduler of this example.
            hostId++;
        }

        // Describe the datacenter architecture, operating system, Host list, time zone, and
        // abstract costs.
        String arch = "x86"; // System architecture.
        String os = "Linux"; // Operating system.
        String vmm = "Xen";
        double time_zone = 10.0; // Time zone of the resources.
        double cost = 3.0; // Abstract computation cost.
        double costPerMem = 0.05; // Abstract memory cost.
        double costPerStorage = 0.1; // Abstract storage cost.
        double costPerBw = 0.1; // Abstract bandwidth cost.
        LinkedList<Storage> storageList = new LinkedList<>(); // This example models no SAN device.
        WorkflowDatacenter datacenter = null;
        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                arch, os, vmm, hostList, time_zone, cost, costPerMem, costPerStorage, costPerBw);
        // Finally create the shared storage object.
        /**
         * Maximum transfer rate inside the datacenter (MB/s). This historical value is not a
         * basis for calibrating a real storage system.
         */
        int maxTransferRate = 15; // May be replaced explicitly under the research platform contract.
        try {
            HarddriveStorage s1 = new HarddriveStorage(name, 1e12);
            s1.setMaxTransferRate(maxTransferRate);
            storageList.add(s1);
            datacenter = new WorkflowDatacenter(
                    name, characteristics, new VmAllocationPolicySimple(hostList), storageList, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return datacenter;
    }

    /**
     * Print the job result table.
     *
     * @param list job list to print
     */
    protected static void printJobList(List<Job> list) {
        int size = list.size();
        Job job;
        String indent = "    ";
        Log.printLine();
        Log.printLine("========== OUTPUT ==========");
        Log.printLine("Cloudlet ID" + indent + "STATUS" + indent
                + "Data center ID" + indent + "VM ID" + indent + indent
                + "Time" + indent + "Start Time" + indent + "Finish Time" + indent + "Depth");
        DecimalFormat dft = new DecimalFormat("###.##");
        for (int i = 0; i < size; i++) {
            job = list.get(i);
            Log.print(indent + job.getCloudletId() + indent + indent);
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
                        + job.getDepth());
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
                        + job.getDepth());
            }
        }
    }
}
