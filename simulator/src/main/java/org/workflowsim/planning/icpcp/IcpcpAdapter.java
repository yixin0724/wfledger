package org.workflowsim.planning.icpcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.FileType;

/** Converts WorkflowSim tasks into the IC-PCP deadline/cost model. */
public final class IcpcpAdapter {

    private IcpcpAdapter() {}

    public static IcpcpProblem toProblem(
            List<Task> tasks, List<CondorVM> vms, PlatformProfile platform, double deadlineSeconds) {
        if (tasks == null || vms == null || platform == null || !(deadlineSeconds > 0.0)) {
            throw new IllegalArgumentException("IC-PCP requires tasks, VMs, platform, and a positive deadline");
        }
        double averageBandwidth = 0.0;
        Map<Integer, Double> costs = new LinkedHashMap<Integer, Double>();
        for (CondorVM vm : vms) {
            averageBandwidth += vm.getBw();
            PlatformProfile.VmSpec spec = findVm(platform, vm.getId());
            PlatformProfile.CostSpec cost = spec.hasCosts() ? spec.getCosts() : platform.getCosts();
            if (cost == null) {
                throw new IllegalArgumentException("IC-PCP requires a declared VM cost for VM " + vm.getId());
            }
            costs.put(vm.getId(), cost.getCpuPerSecond());
        }
        averageBandwidth /= Math.max(1, vms.size());
        List<IcpcpTask> converted = new ArrayList<IcpcpTask>();
        for (Task task : tasks) {
            Map<Integer, Double> compute = new LinkedHashMap<Integer, Double>();
            for (CondorVM vm : vms) {
                compute.put(vm.getId(), task.getCloudletLength() / vm.getMips());
            }
            Map<Integer, Double> children = new LinkedHashMap<Integer, Double>();
            for (Task child : task.getChildList()) {
                double bits = 0.0;
                for (FileItem output : task.getFileList()) {
                    if (output.getType() != FileType.OUTPUT) {
                        continue;
                    }
                    for (FileItem input : child.getFileList()) {
                        if (input.getType() == FileType.INPUT
                                && output.getName().equals(input.getName())) {
                            bits += output.getSize() * 8.0;
                        }
                    }
                }
                children.put(child.getCloudletId(), bits / averageBandwidth);
            }
            List<Integer> parents = new ArrayList<Integer>();
            for (Task parent : task.getParentList()) {
                parents.add(parent.getCloudletId());
            }
            converted.add(new IcpcpTask(task.getCloudletId(), compute, parents, children));
        }
        return new IcpcpProblem(converted, deadlineSeconds, 1.0, costs);
    }

    private static PlatformProfile.VmSpec findVm(PlatformProfile platform, int vmId) {
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.getId() == vmId) {
                return spec;
            }
        }
        throw new IllegalArgumentException("No IC-PCP platform declaration for VM " + vmId);
    }
}
