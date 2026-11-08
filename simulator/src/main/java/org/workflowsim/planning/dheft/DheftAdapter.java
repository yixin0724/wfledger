package org.workflowsim.planning.dheft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.FileType;

/** Converts WorkflowSim tasks and VM bandwidth declarations into DHEFT inputs. */
public final class DheftAdapter {

    private DheftAdapter() {}

    public static DheftProblem toProblem(List<Task> tasks, List<CondorVM> vms, PlatformProfile platform) {
        if (tasks == null || vms == null || platform == null) {
            throw new IllegalArgumentException("DHEFT requires tasks, VMs, and a platform");
        }
        Map<Integer, Double> bandwidth = new LinkedHashMap<Integer, Double>();
        for (CondorVM vm : vms) {
            bandwidth.put(vm.getId(), (double) vm.getBw());
        }
        List<DheftTask> converted = new ArrayList<DheftTask>();
        for (Task task : tasks) {
            Map<Integer, Double> compute = new LinkedHashMap<Integer, Double>();
            for (CondorVM vm : vms) {
                compute.put(vm.getId(), task.getCloudletLength() / vm.getMips());
            }
            Map<Integer, DheftEdge> edges = new LinkedHashMap<Integer, DheftEdge>();
            for (Task child : task.getChildList()) {
                double bytes = 0.0;
                for (FileItem output : task.getFileList()) {
                    if (output.getType() != FileType.OUTPUT) {
                        continue;
                    }
                    for (FileItem input : child.getFileList()) {
                        if (input.getType() == FileType.INPUT
                                && output.getName().equals(input.getName())) {
                            bytes += input.getSize();
                        }
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
