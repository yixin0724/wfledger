package org.workflowsim.experiment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cloudbus.cloudsim.Consts;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.ClusteringParameters.ClusteringMethod;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationTiming;
import org.workflowsim.utils.TaskExecutionModel;

final class WorkflowModelReference {

    static final String CONTROLLED_SHARED_STORAGE_SCOPE =
            "SHARED_STORAGE_NO_CLUSTERING_NO_FAILURE_NO_OVERHEAD_SPACE_SHARED";
    static final String UNAVAILABLE_SCOPE = "UNAVAILABLE_OUTSIDE_CONTROLLED_SHARED_STORAGE_SCOPE";
    private static final String UNREPRESENTABLE_EXECUTION_COST_SCOPE = "UNAVAILABLE_UNREPRESENTABLE_EXECUTION_COST";
    private static final long STAGE_IN_JOB_LENGTH_MI = 110L;

    private WorkflowModelReference() {}

    private static Map<Task, Integer> validatedRemainingParents(List<Task> tasks) {
        Map<Task, Integer> remainingParents = new HashMap<Task, Integer>();
        Set<Task> taskSet = new HashSet<Task>(tasks);
        Set<Integer> taskIds = new HashSet<Integer>();
        for (Task task : tasks) {
            if (task == null || !taskIds.add(task.getCloudletId())) {
                return null;
            }
            for (Task parent : task.getParentList()) {
                if (parent == null || !taskSet.contains(parent)) {
                    return null;
                }
            }
            for (Task child : task.getChildList()) {
                if (child == null || !taskSet.contains(child)) {
                    return null;
                }
            }
            remainingParents.put(task, task.getParentList().size());
        }
        return remainingParents;
    }

    private static TraversalResult traverseForLowerBound(
            List<Task> tasks,
            List<PlatformProfile.VmSpec> vms,
            PlatformProfile platform,
            SimulationConfig config,
            Map<Task, Integer> remainingParents,
            double stageSeconds) {
        TraversalResult result = new TraversalResult();
        Map<Task, Double> finishTimes = new HashMap<Task, Double>();
        Deque<Task> ready = new ArrayDeque<Task>();
        for (Task task : tasks) {
            if (remainingParents.get(task).intValue() == 0) {
                ready.addLast(task);
            }
        }
        int processed = 0;
        double lowerBound = 0.0;
        while (!ready.isEmpty()) {
            Task task = ready.removeFirst();
            double readyTime = task.getParentList().isEmpty() ? stageSeconds : 0.0;
            for (Task parent : task.getParentList()) {
                readyTime = Math.max(readyTime, finishTimes.get(parent).doubleValue());
            }
            double duration;
            try {
                duration = fastestCompatibleDuration(
                        task, vms, platform.getStorage().getMaxTransferRateMbPerSecond());
            } catch (IllegalArgumentException | ArithmeticException unrepresentableCost) {

                result.unavailable = Reference.unavailable(UNREPRESENTABLE_EXECUTION_COST_SCOPE);
                return result;
            }
            if (Double.isInfinite(duration)) {
                result.unavailable = Reference.unavailable("UNAVAILABLE_NO_COMPATIBLE_VM");
                return result;
            }
            double finish = readyTime + duration;
            if (!Double.isFinite(finish) || finish < 0.0) {
                result.unavailable = Reference.unavailable(UNREPRESENTABLE_EXECUTION_COST_SCOPE);
                return result;
            }
            finishTimes.put(task, finish);
            lowerBound = Math.max(lowerBound, finish);
            processed++;

            List<Task> children = new ArrayList<Task>(task.getChildList());
            Collections.sort(children, new Comparator<Task>() {
                @Override
                public int compare(Task first, Task second) {
                    return Integer.compare(first.getCloudletId(), second.getCloudletId());
                }
            });
            for (Task child : children) {
                int remaining = remainingParents.get(child).intValue() - 1;
                remainingParents.put(child, Integer.valueOf(remaining));
                if (remaining == 0) {
                    ready.addLast(child);
                }
            }
        }
        result.processed = processed;
        result.lowerBound = lowerBound;
        return result;
    }

    private static final class TraversalResult {
        private Reference unavailable;
        private int processed;
        private double lowerBound;
    }

    static Reference calculate(List<Task> sourceTasks, SimulationConfig config, PlatformProfile platform) {
        if (!supportsControlledSharedStorage(config, platform)) {
            return Reference.unavailable(UNAVAILABLE_SCOPE);
        }
        if (sourceTasks == null || sourceTasks.isEmpty()) {
            return Reference.unavailable("UNAVAILABLE_NO_SOURCE_TASKS");
        }

        List<Task> tasks = new ArrayList<Task>(sourceTasks);
        Collections.sort(tasks, new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                return Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        List<PlatformProfile.VmSpec> vms = new ArrayList<PlatformProfile.VmSpec>(platform.getVms());
        Collections.sort(vms, new Comparator<PlatformProfile.VmSpec>() {
            @Override
            public int compare(PlatformProfile.VmSpec first, PlatformProfile.VmSpec second) {
                return Integer.compare(first.getId(), second.getId());
            }
        });

        Map<Task, Integer> remainingParents = validatedRemainingParents(tasks);
        if (remainingParents == null) {
            return Reference.unavailable("UNAVAILABLE_INVALID_SOURCE_TASK_GRAPH");
        }

        double fastestMips = 0.0;
        for (PlatformProfile.VmSpec vm : vms) {
            fastestMips = Math.max(fastestMips, vm.getMips());
        }
        if (fastestMips <= 0.0) {
            return Reference.unavailable("UNAVAILABLE_NO_POSITIVE_VM_MIPS");
        }

        double stageSeconds = SimulationTiming.earliestCloudletCompletionTime(
                        0.0, STAGE_IN_JOB_LENGTH_MI / fastestMips, config.getCloudSimMinEventIntervalSeconds())
                + config.getCloudSimMinEventIntervalSeconds();

        TraversalResult traversal = traverseForLowerBound(tasks, vms, platform, config, remainingParents, stageSeconds);
        if (traversal.unavailable != null) {
            return traversal.unavailable;
        }
        if (traversal.processed != tasks.size()) {
            return Reference.unavailable("UNAVAILABLE_CYCLIC_SOURCE_TASK_GRAPH");
        }
        return Reference.available(CONTROLLED_SHARED_STORAGE_SCOPE, traversal.lowerBound, tasks.size());
    }

    private static double fastestCompatibleDuration(
            Task task, List<PlatformProfile.VmSpec> vms, int transferRateMbPerSecond) {
        double best = Double.POSITIVE_INFINITY;

        double transferSeconds = transferSeconds(task, transferRateMbPerSecond);
        if (!Double.isFinite(transferSeconds) || transferSeconds < 0.0) {
            throw new IllegalArgumentException("Reference transfer seconds must be finite and non-negative");
        }
        for (PlatformProfile.VmSpec vm : vms) {
            if (task.getNumberOfPes() > vm.getPes()) {
                continue;
            }
            long computeMi = TaskExecutionModel.executionLengthMi(task, vm.getId(), vm.getMips());
            double transferWorkMi = vm.getMips() * transferSeconds;

            if (!Double.isFinite(transferWorkMi) || transferWorkMi < 0.0 || transferWorkMi >= 0x1.0p63) {
                throw new IllegalArgumentException(
                        "Reference transfer MI cannot be represented as a non-negative long");
            }
            long transferMi = (long) transferWorkMi;
            long envelopeMi = Math.addExact(computeMi, transferMi);
            TaskExecutionModel.requireRepresentableLength(envelopeMi, task.getNumberOfPes());

            double candidateSeconds = envelopeMi / vm.getMips();
            if (!Double.isFinite(candidateSeconds) || candidateSeconds <= 0.0) {
                throw new IllegalArgumentException("Reference execution seconds must be finite and positive");
            }
            best = Math.min(best, candidateSeconds);
        }
        return best;
    }

    private static double transferSeconds(Task task, int transferRateMbPerSecond) {
        double result = 0.0;
        for (FileItem file : task.getFileList()) {
            if (file.isRealInputFile(task.getFileList())) {
                result += file.getSize() / (double) Consts.MILLION / transferRateMbPerSecond;
            }
        }
        return result;
    }

    private static boolean supportsControlledSharedStorage(SimulationConfig config, PlatformProfile platform) {
        if (config.getFileSystem() != ReplicaCatalog.FileSystem.SHARED
                || config.getClusteringParameters().getClusteringMethod() != ClusteringMethod.NONE
                || config.getFailureModel().isEnabled()
                || !isNoOverhead(config)
                || platform.getStorage().getMaxTransferRateMbPerSecond() <= 0) {
            return false;
        }
        DataMovementModel.Kind movement = config.getDataMovementModel().getKind();
        if (movement != DataMovementModel.Kind.LEGACY_WORKFLOWSIM_V1
                && movement != DataMovementModel.Kind.FIXED_ENDPOINT_NO_CONTENTION_V1) {

            return false;
        }
        for (PlatformProfile.VmSpec vm : platform.getVms()) {
            if (vm.getSchedulerMode() != PlatformProfile.CloudletSchedulerMode.SPACE_SHARED || vm.getMips() <= 0.0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNoOverhead(SimulationConfig config) {
        return config.getOverheadModel().getWorkflowEngineDelayInterval() == 0
                && config.getOverheadModel().getBandwidth() == 0.0
                && config.getOverheadModel().getWorkflowEngineDelays().isEmpty()
                && config.getOverheadModel().getQueueDelays().isEmpty()
                && config.getOverheadModel().getPostDelays().isEmpty()
                && config.getOverheadModel().getClusteringDelays().isEmpty();
    }

    static final class Reference {
        private final boolean available;
        private final String scope;
        private final double criticalPathLowerBoundSeconds;
        private final int sourceTaskCount;

        private Reference(boolean available, String scope, double criticalPathLowerBoundSeconds, int sourceTaskCount) {
            this.available = available;
            this.scope = scope;
            this.criticalPathLowerBoundSeconds = criticalPathLowerBoundSeconds;
            this.sourceTaskCount = sourceTaskCount;
        }

        private static Reference available(String scope, double lowerBound, int taskCount) {
            return new Reference(true, scope, lowerBound, taskCount);
        }

        private static Reference unavailable(String scope) {
            return new Reference(false, scope, 0.0, 0);
        }

        boolean isAvailable() {
            return available;
        }

        String getScope() {
            return scope;
        }

        double getCriticalPathLowerBoundSeconds() {
            return criticalPathLowerBoundSeconds;
        }

        int getSourceTaskCount() {
            return sourceTaskCount;
        }
    }
}
