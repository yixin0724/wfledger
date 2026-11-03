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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
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
import org.workflowsim.ClusterStorage;
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
 * Demonstrates historical multi-cluster workflow assembly with two datacenters and 20 VMs.
 *
 * <p>This class retains the legacy static-initialization path for teaching and compatibility
 * exploration. It is not a standard research experiment entry point; its Host, bandwidth, and
 * allocation behavior does not form an auditable platform contract through
 * {@code PlatformProfile}.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class WorkflowSimMultipleClusterExample1 extends WorkflowSimBasicExample1 {

    protected static List<CondorVM> createVM(int userId, int vms, int vmIdBase) {

        // Create the VM container that is later submitted to the workflow engine.
        LinkedList<CondorVM> list = new LinkedList<>();

        // Abstract resource specification of a VM.
        long size = 10000; // Image size (MB)
        int ram = 512; // VM memory (MB)
        int mips = 1000;
        long bw = 1000;
        int pesNumber = 1; // Number of CPU cores
        String vmm = "Xen"; // Virtual machine monitor name

        // Create the VMs with globally unique VM IDs.
        CondorVM[] vm = new CondorVM[vms];
        for (int i = 0; i < vms; i++) {
            double ratio = 1.0;
            vm[i] = new CondorVM(
                    vmIdBase + i,
                    userId,
                    mips * ratio,
                    pesNumber,
                    ram,
                    bw,
                    size,
                    vmm,
                    new CloudletSchedulerSpaceShared());
            list.add(vm[i]);
        }
        return list;
    }

    // ======================== Static example entry point ========================
    /**
     * Run the historical multi-datacenter scenario.
     */
    public static void main(String[] args) {

        try {
            ExampleCliSupport.begin("multiple-cluster");
            // Step 1: configure the historical WorkflowSim static parameters.

            /**
             * When datacenter or Host capacity is insufficient, the number of VMs actually
             * created may be lower than the declared value.
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
             * The historical MINMIN scheduling decides at the scheduling layer, so the planner
             * is disabled to avoid overriding its result.
             */
            Parameters.SchedulingAlgorithm sch_method = Parameters.SchedulingAlgorithm.MINMIN;
            Parameters.PlanningAlgorithm pln_method = Parameters.PlanningAlgorithm.INVALID;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.SHARED;

            /**
             * No additional overhead is injected.
             */
            OverheadParameters op = new OverheadParameters(0, null, null, null, null, 0);

            /**
             * No task clustering is performed.
             */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.NONE;
            ClusteringParameters cp = new ClusteringParameters(0, 0, method, null);

            /**
             * Initialize the historical global parameters and the file catalog.
             */
            Parameters.init(vmNum, daxPath, null, null, op, cp, sch_method, pln_method, null, 0);
            ReplicaCatalog.init(file_system);

            // Complete initialization before creating any CloudSim entity.
            int num_user = 1; // Number of grid users
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false; // Whether to record the event trace

            // Initialize CloudSim.
            CloudSim.init(num_user, calendar, trace_flag);

            WorkflowDatacenter datacenter0 = createDatacenter("Datacenter_0");
            WorkflowDatacenter datacenter1 = createDatacenter("Datacenter_1");

            /**
             * Create a workflow planner with one scheduler.
             */
            WorkflowPlanner wfPlanner = new WorkflowPlanner("planner_0", 1);
            /**
             * Obtain the workflow engine associated with the planner.
             */
            WorkflowEngine wfEngine = wfPlanner.getWorkflowEngine();
            /**
             * Create two VM groups. The IDs of both groups must be globally unique, so the
             * second group is numbered from half of the total VM count.
             */
            List<CondorVM> vmlist0 = createVM(wfEngine.getSchedulerId(0), Parameters.getVmNum() / 2, 0);
            List<CondorVM> vmlist1 =
                    createVM(wfEngine.getSchedulerId(0), Parameters.getVmNum() / 2, Parameters.getVmNum() / 2);

            /**
             * Submit both VM groups to the workflow engine.
             */
            wfEngine.submitVmList(vmlist0, 0);
            wfEngine.submitVmList(vmlist1, 0);

            /**
             * Bind both datacenters to the same scheduler. This historical example relies on
             * allocation behavior that tries the next datacenter only when capacity is
             * insufficient; it must not be treated as a real multi-cluster resource scheduling
             * model.
             */
            wfEngine.bindSchedulerDatacenter(datacenter0.getId(), 0);
            wfEngine.bindSchedulerDatacenter(datacenter1.getId(), 0);

            CloudSim.startSimulation();
            List<Job> outputList0 = wfEngine.getJobsReceivedList();
            CloudSim.stopSimulation();
            printJobList(outputList0);
            ExampleCliSupport.reportCompletion(outputList0);
        } catch (Exception e) {
            throw ExampleCliSupport.failure("multiple-cluster", e);
        } finally {
            ExampleCliSupport.end();
        }
    }

    protected static WorkflowDatacenter createDatacenter(String name) {

        // Build the datacenter: first create the list of one or more Hosts.
        List<Host> hostList = new ArrayList<>();

        // Each Host consists of one or more PEs (CPU cores), so build the PE list first.
        // This legacy example relies on the same broker trying datacenters in order: only when
        // the first datacenter has insufficient capacity can an unallocated VM land on the next
        // datacenter. It is merely a historical allocation path, not a generalizable
        // multi-cluster scheduling model.
        for (int i = 1; i <= 3; i++) {
            List<Pe> peList1 = new ArrayList<>();
            int mips = 2000;
            // Create and add PEs; a multi-core Host needs a corresponding number of PEs.
            peList1.add(new Pe(0, new PeProvisionerSimple(mips))); // PE ID and MIPS rating.
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
        double time_zone = 10.0; // Time zone where the resources are located.
        double cost = 3.0; // Abstract computation cost.
        double costPerMem = 0.05; // Abstract memory cost.
        double costPerStorage = 0.1; // Abstract storage cost.
        double costPerBw = 0.1; // Abstract bandwidth cost.
        LinkedList<Storage> storageList = new LinkedList<>(); // This example does not model SAN devices.
        WorkflowDatacenter datacenter = null;
        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                arch, os, vmm, hostList, time_zone, cost, costPerMem, costPerStorage, costPerBw);

        // Finally create the cluster storage object.
        /**
         * Abstract bandwidth between datacenters. This historical constant does not constitute a
         * basis for real network calibration.
         */
        double interBandwidth = 1.5e7; // Can be replaced explicitly per the research platform contract.
        /**
         * Abstract bandwidth inside a datacenter.
         */
        double intraBandwidth = interBandwidth * 2;
        try {
            ClusterStorage s1 = new ClusterStorage(name, 1e12);
            switch (name) {
                case "Datacenter_0":
                    /** Bandwidth from Datacenter_0 to Datacenter_1. */
                    s1.setBandwidth("Datacenter_1", interBandwidth);
                    break;
                case "Datacenter_1":
                    /** Bandwidth from Datacenter_1 to Datacenter_0. */
                    s1.setBandwidth("Datacenter_0", interBandwidth);
                    break;
                default:
                    // Other datacenter names have no cross-site bandwidth entry, only the generic
                    // local/source entries configured below.
                    break;
            }
            // Bandwidth inside the datacenter.
            s1.setBandwidth("local", intraBandwidth);
            // Bandwidth to the source site.
            s1.setBandwidth("source", interBandwidth);
            storageList.add(s1);
            datacenter = new WorkflowDatacenter(
                    name, characteristics, new VmAllocationPolicySimple(hostList), storageList, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return datacenter;
    }
}
