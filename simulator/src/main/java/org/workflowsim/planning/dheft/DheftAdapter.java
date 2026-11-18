package org.workflowsim.planning.dheft;

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

public final class DheftAdapter {

    private DheftAdapter() {}

    public static DheftProblem toProblem(List<Task> tasks, List<CondorVM> vms, PlatformProfile platform) {
        if (tasks == null || vms == null || platform == null) {
            throw new IllegalArgumentException("DHEFT requires tasks, VMs, and a platform");
        }
        Map<Integer, Double> bandwidth = new LinkedHashMap<Integer, Double>();
        for (CondorVM vm : vms) {

            bandwidth.put(vm.getId(), vm.getBw() * 1.0e6);
        }
        List<DheftTask> converted = new ArrayList<DheftTask>();
        for (Task task : tasks) {
            Map<Integer, Double> compute = new LinkedHashMap<Integer, Double>();
            for (CondorVM vm : vms) {
                if (task.getNumberOfPes() <= vm.getNumberOfPes()) {
                    compute.put(vm.getId(), task.getCloudletLength() / vm.getMips());
                }
            }
            if (compute.isEmpty()) {
                throw new IllegalArgumentException(
                        "DHEFT cannot map task " + task.getCloudletId() + "; no PE-compatible VM");
            }
            LinkedHashSet<String> outputNames = new LinkedHashSet<String>();
            for (FileItem output : task.getFileList()) {
                if (output.getType() == FileType.OUTPUT) {
                    outputNames.add(output.getName());
                }
            }
            Map<Integer, DheftEdge> edges = new LinkedHashMap<Integer, DheftEdge>();
            for (Task child : task.getChildList()) {
                double bytes = 0.0;
                for (FileItem input : child.getFileList()) {
                    if (input.getType() == FileType.INPUT && outputNames.contains(input.getName())) {
                        bytes += input.getSize();
                    }
                }
                edges.put(child.getCloudletId(), new DheftEdge(child.getCloudletId(), bytes));
            }
            List<Integer> parents = new ArrayList<Integer>();
            for (Task parent : task.getParentList()) {
                parents.add(parent.getCloudletId());
            }
            converted.add(new DheftTask(task.getCloudletId(), compute, parents, edges));
        }
        return new DheftProblem(converted, bandwidth);
    }
}
