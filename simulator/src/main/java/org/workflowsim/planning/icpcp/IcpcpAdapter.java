package org.workflowsim.planning.icpcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.SimulationConstants;
import org.workflowsim.utils.SimulationTiming;

/**
 * Converts WorkflowSim tasks into an IC-PCP problem under the SHARED compute-envelope model.
 *
 * <p>FGCS-R3 (2026-11-24) M3 root cause: the adapter added a point-to-point edge transfer
 * ({@code bytes / mean VM bandwidth}) on top of task durations that already include the shared-storage
 * read of every real input, so cross-VM edges were charged twice relative to the runtime, which has no
 * point-to-point edges in SHARED. Edge transfer times are now zero; every input read stays in the
 * consumer's {@code (L_raw + floor(mips * inputSeconds)) / mips} envelope.
 */
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
        Map<Integer, Double> costs = new LinkedHashMap<Integer, Double>();
        for (CondorVM vm : vms) {
            PlatformProfile.VmSpec spec = findVm(platform, vm.getId());
            PlatformProfile.CostSpec cost = spec.hasCosts() ? spec.getCosts() : platform.getCosts();
            if (cost == null) {
                throw new IllegalArgumentException("IC-PCP requires a declared VM cost for VM " + vm.getId());
            }
            costs.put(vm.getId(), cost.getCpuPerSecond());
        }
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
            // FGCS-R3 (2026-11-24) M3: SHARED has no point-to-point edges; the child's read of this
            // task's output is already part of the child's envelope duration above.
            Map<Integer, Double> children = new LinkedHashMap<Integer, Double>();
            for (Task child : task.getChildList()) {
                children.put(child.getCloudletId(), 0.0);
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
