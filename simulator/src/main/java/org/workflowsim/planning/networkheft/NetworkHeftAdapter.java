package org.workflowsim.planning.networkheft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.FileType;

/** Converts file-carrying tasks into Network-Aware HEFT flows and shared bottlenecks. */
public final class NetworkHeftAdapter {

    private NetworkHeftAdapter() {}

    public static NetworkHeftProblem toProblem(List<Task> tasks, List<CondorVM> vms, PlatformProfile platform) {
        if (tasks == null || vms == null || platform == null) {
            throw new IllegalArgumentException("Network-Aware HEFT requires tasks, VMs, and a platform");
        }
        double averageBandwidth = 0.0;
        for (CondorVM vm : vms) {
            averageBandwidth += vm.getBw() * 8.0e6;
        }
        averageBandwidth /= Math.max(1, vms.size());
        List<NetworkHeftTask> converted = new ArrayList<NetworkHeftTask>();
        for (Task task : tasks) {
            Map<Integer, Double> compute = new LinkedHashMap<Integer, Double>();
            for (CondorVM vm : vms) {
                if (task.getNumberOfPes() <= vm.getNumberOfPes()) {
                    compute.put(vm.getId(), task.getCloudletLength() / vm.getMips());
                }
            }
            if (compute.isEmpty()) {
                throw new IllegalArgumentException(
                        "Network-Aware HEFT cannot map task " + task.getCloudletId() + "; no PE-compatible VM");
            }
            LinkedHashSet<String> outputNames = new LinkedHashSet<String>();
            for (FileItem output : task.getFileList()) {
                if (output.getType() == FileType.OUTPUT) {
                    outputNames.add(output.getName());
                }
            }
            Map<Integer, NetworkHeftEdge> edges = new LinkedHashMap<Integer, NetworkHeftEdge>();
            for (Task child : task.getChildList()) {
                double bytes = 0.0;
                for (FileItem input : child.getFileList()) {
                    if (input.getType() == FileType.INPUT && outputNames.contains(input.getName())) {
                        bytes += input.getSize();
                    }
                }
                // WorkflowSim bandwidth is decimal MB/s. Convert file bytes to bits for
                // the nominal estimate and use the corresponding byte-rate capacity below.
                double bits = bytes * 8.0;
                edges.put(
                        child.getCloudletId(),
                        new NetworkHeftEdge(
                                child.getCloudletId(), bytes, bits / averageBandwidth, "shared-bottleneck"));
            }
            List<Integer> parents = new ArrayList<Integer>();
            for (Task parent : task.getParentList()) {
                parents.add(parent.getCloudletId());
            }
            converted.add(new NetworkHeftTask(task.getCloudletId(), compute, parents, edges));
        }
        return new NetworkHeftProblem(converted, averageBandwidth / 8.0);
    }
}
