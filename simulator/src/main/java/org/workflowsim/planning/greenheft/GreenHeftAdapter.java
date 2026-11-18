package org.workflowsim.planning.greenheft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;

public final class GreenHeftAdapter {

    private GreenHeftAdapter() {}

    public static GreenHeftProblem toProblem(List<Task> tasks, List<CondorVM> vms, PlatformProfile platform) {
        if (tasks == null || vms == null || platform == null) {
            throw new IllegalArgumentException("GreenHEFT requires tasks, VMs, and a platform");
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
            Map<Integer, Double> compute = new LinkedHashMap<Integer, Double>();
            Map<Integer, Double> energy = new LinkedHashMap<Integer, Double>();
            for (CondorVM vm : vms) {
                PlatformProfile.VmSpec spec = specs.get(vm.getId());
                if (spec == null) {
                    throw new IllegalArgumentException("No GreenHEFT power declaration for VM " + vm.getId());
                }
                if (vm.getNumberOfPes() >= task.getNumberOfPes()) {
                    double seconds = task.getCloudletLength() / vm.getMips();
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
        return new GreenHeftProblem(converted);
    }
}
