package org.workflowsim.planning.greenheft;

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
 * Converts WorkflowSim tasks into a GreenHEFT/MOHEFT problem under the SHARED compute-envelope model.
 *
 * <p>FGCS-R3 (2026-11-24) M6 root cause: durations were raw {@code MI / MIPS} and roots started at 0, while
 * the SHARED runtime executes {@code (L_raw + floor(mips * inputSeconds)) / mips} after the 110-MI stage-in
 * Job and the root-release interval. Planned windows therefore diverged from execution, unlike
 * HEFT/CPOP/DLS/ETF/PEFT, IC-PCP and DVFS. Durations, stage-in and root release now follow
 * {@code SharedStorageDagPlanner} exactly; task energy is {@code P_max} times the envelope duration, because
 * the runtime energy law charges the VM busy interval of the whole Job envelope at the dynamic power.
 * Edges carry no point-to-point transfer: the SHARED runtime has none.
 */
public final class GreenHeftAdapter {

    private GreenHeftAdapter() {}

    public static GreenHeftProblem toProblem(
            List<Task> tasks,
            List<CondorVM> vms,
            PlatformProfile platform,
            double storageRateMbPerSecond,
            double minimumEventIntervalSeconds) {
        if (tasks == null || vms == null || vms.isEmpty() || platform == null) {
            throw new IllegalArgumentException("GreenHEFT requires tasks, VMs, and a platform");
        }
        if (!(storageRateMbPerSecond > 0.0)
                || !Double.isFinite(storageRateMbPerSecond)
                || !(minimumEventIntervalSeconds > 0.0)
                || !Double.isFinite(minimumEventIntervalSeconds)) {
            throw new IllegalArgumentException(
                    "GreenHEFT requires a positive shared-storage rate and minimum event interval");
        }
        Map<Integer, PlatformProfile.VmSpec> specs = new LinkedHashMap<Integer, PlatformProfile.VmSpec>();
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (!spec.hasPowerModel() || !spec.getFrequencyTiers().isEmpty()) {
                throw new IllegalArgumentException("GreenHEFT requires one declared power profile per VM");
            }
            specs.put(spec.getId(), spec);
        }
        List<GreenHeftTask> converted = new ArrayList<GreenHeftTask>();
        for (Task task : tasks) {
            double inputSeconds = 0.0;
            for (FileItem file : task.getFileList()) {
                if (file.isRealInputFile(task.getFileList())) {
                    inputSeconds += file.getSize() / 1.0e6 / storageRateMbPerSecond;
                }
            }
            Map<Integer, Double> compute = new LinkedHashMap<Integer, Double>();
            Map<Integer, Double> energy = new LinkedHashMap<Integer, Double>();
            for (CondorVM vm : vms) {
                PlatformProfile.VmSpec spec = specs.get(vm.getId());
                if (spec == null) {
                    throw new IllegalArgumentException("No GreenHEFT power declaration for VM " + vm.getId());
                }
                if (vm.getNumberOfPes() >= task.getNumberOfPes()) {
                    long transferMi = (long) (vm.getMips() * inputSeconds);
                    double seconds = (task.getCloudletLength() + transferMi) / vm.getMips();
                    compute.put(vm.getId(), seconds);
                    energy.put(vm.getId(), seconds * spec.getMaxPowerWatts());
                }
            }
            if (compute.isEmpty()) {
                throw new IllegalArgumentException("No PE-compatible GreenHEFT VM for Task " + task.getCloudletId());
            }
            List<Integer> parents = new ArrayList<Integer>();
            for (Task parent : task.getParentList()) {
                parents.add(parent.getCloudletId());
            }
            Map<Integer, Double> children = new LinkedHashMap<Integer, Double>();
            for (Task child : task.getChildList()) {
                children.put(child.getCloudletId(), 0.0);
            }
            converted.add(new GreenHeftTask(task.getCloudletId(), compute, energy, parents, children));
        }
        return new GreenHeftProblem(converted, rootRelease(vms, minimumEventIntervalSeconds));
    }

    /** Finish of the 110-MI stage-in Job on the lowest-ID VM plus the root-release interval. */
    private static double rootRelease(List<CondorVM> vms, double minimumEventIntervalSeconds) {
        CondorVM first = null;
        for (CondorVM vm : vms) {
            if (first == null || vm.getId() < first.getId()) {
                first = vm;
            }
        }
        return SimulationTiming.earliestCloudletCompletionTime(
                        0.0, SimulationConstants.STAGE_IN_JOB_LENGTH_MI / first.getMips(), minimumEventIntervalSeconds)
                + minimumEventIntervalSeconds;
    }
}
