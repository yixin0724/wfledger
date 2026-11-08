package org.workflowsim.planning.naco;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.FileType;

/** Converts maintained workflow tasks into NACO DNC inputs without changing source bytes. */
public final class NacoAdapter {

    private NacoAdapter() {}

    public static NacoDncProblem toProblem(List<Task> tasks, List<CondorVM> vms, PlatformProfile platform) {
        if (tasks == null || vms == null || platform == null) {
            throw new IllegalArgumentException("NACO requires tasks, VMs, and a platform");
        }
        Map<Integer, Double> network = new LinkedHashMap<Integer, Double>();
        Map<Integer, Double> disk = new LinkedHashMap<Integer, Double>();
        for (CondorVM vm : vms) {
            network.put(vm.getId(), (double) vm.getBw());
            disk.put(vm.getId(), (double) platform.getStorage().getMaxTransferRateMbPerSecond());
        }
        List<NacoTask> converted = new ArrayList<NacoTask>();
        for (Task task : tasks) {
            Map<Integer, Double> compute = new LinkedHashMap<Integer, Double>();
            for (CondorVM vm : vms) {
                if (task.getNumberOfPes() <= vm.getNumberOfPes()) {
                    // Per-PE MI is not divided by task PEs again; PEs constrain
                    // placement only in the maintained execution model.
                    compute.put(vm.getId(), task.getCloudletLength() / vm.getMips());
                }
            }
            // R-PAPER-03 audit: DNC rates are Mbit/s, whereas FileItem sizes are
            // bytes. Convert bytes to Mbit once here; do not divide raw bits by MB/s.
            double inputBits = 0.0;
            double outputBits = 0.0;
            java.util.Set<String> parentProducedInputs = new java.util.HashSet<String>();
            for (Task parent : task.getParentList()) {
                for (FileItem parentFile : parent.getFileList()) {
                    if (parentFile.getType() == FileType.OUTPUT) {
                        parentProducedInputs.add(parentFile.getName());
                    }
                }
            }
            for (FileItem file : task.getFileList()) {
                if (file.getType() == FileType.INPUT && !parentProducedInputs.contains(file.getName())) {
                    inputBits += file.getSize() * 8.0 / 1.0e6;
                } else if (file.getType() == FileType.OUTPUT) {
                    outputBits += file.getSize() * 8.0 / 1.0e6;
                }
            }
            Map<Integer, Double> edges = new LinkedHashMap<Integer, Double>();
            for (Task child : task.getChildList()) {
                double edgeBits = 0.0;
                for (FileItem parentFile : task.getFileList()) {
                    if (parentFile.getType() != FileType.OUTPUT) {
                        continue;
                    }
                    for (FileItem childFile : child.getFileList()) {
                        if (childFile.getType() == FileType.INPUT
                                && parentFile.getName().equals(childFile.getName())) {
                            edgeBits += parentFile.getSize() * 8.0 / 1.0e6;
                        }
                    }
                }
                edges.put(child.getCloudletId(), edgeBits);
            }
            List<Integer> parents = new ArrayList<Integer>();
            for (Task parent : task.getParentList()) {
                parents.add(parent.getCloudletId());
            }
            converted.add(new NacoTask(task.getCloudletId(), inputBits, outputBits, compute, parents, edges));
        }
        return new NacoDncProblem(converted, network, disk);
    }
}
