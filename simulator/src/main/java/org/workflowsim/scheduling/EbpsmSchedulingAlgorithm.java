package org.workflowsim.scheduling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.utils.EbpsmConfig;
import org.workflowsim.utils.EbpsmRegistry;
import org.workflowsim.utils.Parameters.FileType;

/** EBPSM ready-task scheduling with input/container cache preference and task budgets. */
public final class EbpsmSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    private final EbpsmConfig config;
    private Map<Integer, Set<String>> cachedInputs;
    private Map<Integer, Set<String>> containerImages;

    public EbpsmSchedulingAlgorithm() {
        this(null);
    }

    public EbpsmSchedulingAlgorithm(EbpsmConfig config) {
        this.config = config;
    }

    @Override
    public void run() {
        EbpsmConfig active = config == null ? EbpsmRegistry.get() : config;
        ensureCaches(active);
        List<Job> jobs = new ArrayList<Job>();
        for (Object item : getCloudletList()) {
            jobs.add((Job) item);
        }
        jobs.sort((first, second) -> {
            int byStart = Double.compare(estimatedStart(second), estimatedStart(first));
            return byStart != 0 ? -byStart : Integer.compare(first.getCloudletId(), second.getCloudletId());
        });
        for (Job job : jobs) {
            List<CondorVM> idle = new ArrayList<CondorVM>();
            for (CondorVM vm : sortedVmsById()) {
                if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                    idle.add(vm);
                }
            }
            if (idle.isEmpty()) {
                break;
            }
            if (job.getTaskList().isEmpty()) {
                CondorVM selected = idle.get(0);
                selected.setState(WorkflowSimTags.VM_STATUS_BUSY);
                job.setVmId(selected.getId());
                getScheduledList().add(job);
                continue;
            }
            Task task = job.getTaskList().get(0);
            double budget = active.getTaskBudget(task.getCloudletId());
            List<CondorVM> inputCandidates = new ArrayList<CondorVM>();
            List<CondorVM> containerCandidates = new ArrayList<CondorVM>();
            for (CondorVM vm : idle) {
                if (hasRequiredInput(task, vm.getId())) {
                    inputCandidates.add(vm);
                }
                if (hasContainer(task, vm.getId(), active)) {
                    containerCandidates.add(vm);
                }
            }
            CondorVM selected = choose(inputCandidates, task, budget, active);
            if (selected == null) {
                selected = choose(containerCandidates, task, budget, active);
            }
            if (selected == null) {
                selected = choose(idle, task, budget, active);
            }
            if (selected == null) {
                continue;
            }
            selected.setState(WorkflowSimTags.VM_STATUS_BUSY);
            job.setVmId(selected.getId());
            getScheduledList().add(job);
            publish(task, selected.getId(), active);
        }
    }

    private CondorVM choose(List<CondorVM> candidates, Task task, double budget, EbpsmConfig active) {
        CondorVM best = null;
        double bestSeconds = Double.POSITIVE_INFINITY;
        for (CondorVM vm : candidates) {
            double seconds = predictedSeconds(task, vm, active);
            double cost = Math.ceil(seconds) * active.getVmCostPerSecond(vm.getId());
            if (cost <= budget + 1.0e-9
                    && (seconds < bestSeconds - 1.0e-9
                            || (Math.abs(seconds - bestSeconds) <= 1.0e-9 && vm.getId() < best.getId()))) {
                best = vm;
                bestSeconds = seconds;
            }
        }
        return best;
    }

    private double predictedSeconds(Task task, CondorVM vm, EbpsmConfig active) {
        double inputBytes = 0.0;
        double outputBytes = 0.0;
        for (FileItem file : task.getFileList()) {
            if (file.getType() == FileType.INPUT) {
                inputBytes += file.getSize();
            } else if (file.getType() == FileType.OUTPUT) {
                outputBytes += file.getSize();
            }
        }
        double read = hasRequiredInput(task, vm.getId())
                ? 0.0
                : inputBytes / active.getGlobalReadRateBytesPerSecond() + inputBytes / vm.getBw();
        double write = outputBytes / active.getGlobalWriteRateBytesPerSecond() + outputBytes / vm.getBw();
        double container = hasContainer(task, vm.getId(), active) ? 0.0 : active.getContainerProvisioningDelaySeconds();
        return read + task.getCloudletLength() / vm.getMips() + write + container;
    }

    private boolean hasRequiredInput(Task task, int vmId) {
        for (FileItem file : task.getFileList()) {
            if (file.getType() == FileType.INPUT
                    && !cachedInputs.getOrDefault(vmId, new HashSet<String>()).contains(file.getName())) {
                return false;
            }
        }
        return true;
    }

    private boolean hasContainer(Task task, int vmId, EbpsmConfig active) {
        return containerImages
                .getOrDefault(vmId, new HashSet<String>())
                .contains(active.getContainerImage(task.getCloudletId()));
    }

    private void ensureCaches(EbpsmConfig active) {
        if (cachedInputs != null) {
            return;
        }
        cachedInputs = new HashMap<Integer, Set<String>>();
        containerImages = new HashMap<Integer, Set<String>>();
        for (Object item : getVmList()) {
            CondorVM vm = (CondorVM) item;
            cachedInputs.put(vm.getId(), new HashSet<String>(active.getCachedInputs(vm.getId())));
            containerImages.put(vm.getId(), new HashSet<String>(active.getContainerImages(vm.getId())));
        }
    }

    private void publish(Task task, int vmId, EbpsmConfig active) {
        containerImages.get(vmId).add(active.getContainerImage(task.getCloudletId()));
        for (FileItem file : task.getFileList()) {
            if (file.getType() == FileType.OUTPUT) {
                cachedInputs.get(vmId).add(file.getName());
            }
        }
    }

    private static double estimatedStart(Job job) {
        double latest = 0.0;
        for (Object parentItem : job.getParentList()) {
            Task parent = (Task) parentItem;
            latest = Math.max(latest, parent.getFinishTime());
        }
        return latest;
    }
}
