package org.workflowsim.planning.icpcp;

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
import org.workflowsim.utils.SimulationConstants;
import org.workflowsim.utils.SimulationTiming;

public final class IcpcpAdapter {

    private IcpcpAdapter() {}

    public static IcpcpProblem toProblem(
            List<Task> tasks, List<CondorVM> vms, PlatformProfile platform, double deadlineSeconds) {
        return toProblem(tasks, vms, platform, deadlineSeconds, 0.1);
    }

    public static IcpcpProblem toProblem(
            List<Task> tasks,
            List<CondorVM> vms,
            PlatformProfile platform,
            double deadlineSeconds,
            double minimumEventIntervalSeconds) {
        if (tasks == null
                || vms == null
                || platform == null
                || !(deadlineSeconds > 0.0)
                || !(minimumEventIntervalSeconds > 0.0)
                || !Double.isFinite(minimumEventIntervalSeconds)) {
            throw new IllegalArgumentException(
                    "IC-PCP requires tasks, VMs, platform, a positive deadline, and a positive event interval");
        }
        double averageBandwidth = 0.0;
        Map<Integer, Double> costs = new LinkedHashMap<Integer, Double>();
        for (CondorVM vm : vms) {
            averageBandwidth += vm.getBw() * 8.0e6;
            PlatformProfile.VmSpec spec = findVm(platform, vm.getId());
            PlatformProfile.CostSpec cost = spec.hasCosts() ? spec.getCosts() : platform.getCosts();
            if (cost == null) {
                throw new IllegalArgumentException("IC-PCP requires a declared VM cost for VM " + vm.getId());
            }
            costs.put(vm.getId(), cost.getCpuPerSecond());
        }
        averageBandwidth /= Math.max(1, vms.size());
        double workflowStart = sharedBootstrapFinish(vms, minimumEventIntervalSeconds);
        double storageRate = platform.getStorage().getMaxTransferRateMbPerSecond();
        List<IcpcpTask> converted = new ArrayList<IcpcpTask>();
        for (Task task : tasks) {
            double inputSeconds = 0.0;
            for (FileItem input : task.getFileList()) {
                if (input.isRealInputFile(task.getFileList())) {
                    inputSeconds += input.getSize() / 1.0e6 / storageRate;
                }
            }
            Map<Integer, Double> compute = new LinkedHashMap<Integer, Double>();
            for (CondorVM vm : vms) {
                if (task.getNumberOfPes() <= vm.getNumberOfPes()) {
                    long transferMi = (long) (vm.getMips() * inputSeconds);
                    compute.put(vm.getId(), (task.getCloudletLength() + transferMi) / vm.getMips());
                }
            }
            if (compute.isEmpty()) {
                throw new IllegalArgumentException(
                        "IC-PCP cannot map task " + task.getCloudletId() + "; no PE-compatible VM");
            }
            LinkedHashSet<String> outputNames = new LinkedHashSet<String>();
            for (FileItem output : task.getFileList()) {
                if (output.getType() == FileType.OUTPUT) {
                    outputNames.add(output.getName());
                }
            }
            Map<Integer, Double> children = new LinkedHashMap<Integer, Double>();
            for (Task child : task.getChildList()) {
                double bytes = 0.0;
                for (FileItem input : child.getFileList()) {
                    if (input.getType() == FileType.INPUT && outputNames.contains(input.getName())) {
                        bytes += input.getSize();
                    }
                }
                children.put(child.getCloudletId(), bytes * 8.0 / averageBandwidth);
            }
            List<Integer> parents = new ArrayList<Integer>();
            for (Task parent : task.getParentList()) {
                parents.add(parent.getCloudletId());
            }
            converted.add(new IcpcpTask(task.getCloudletId(), compute, parents, children));
        }
        return new IcpcpProblem(converted, deadlineSeconds, 1.0, costs, workflowStart);
    }

    private static double sharedBootstrapFinish(List<CondorVM> vms, double minimumEventIntervalSeconds) {
        CondorVM fallback = null;
        for (CondorVM vm : vms) {
            if (fallback == null || vm.getId() < fallback.getId()) {
                fallback = vm;
            }
        }
        if (fallback == null) {
            throw new IllegalArgumentException("IC-PCP requires at least one VM");
        }
        return SimulationTiming.earliestCloudletCompletionTime(
                        0.0,
                        SimulationConstants.STAGE_IN_JOB_LENGTH_MI / fallback.getMips(),
                        minimumEventIntervalSeconds)
                + minimumEventIntervalSeconds;
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
