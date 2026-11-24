package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.WorkflowDagValidator;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.SimulationConstants;
import org.workflowsim.utils.SimulationTiming;
import org.workflowsim.utils.TaskExecutionModel;

abstract class AbstractLocalCommPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;

    private final String label;

    private final Map<Integer, CondorVM> vmById = new LinkedHashMap<Integer, CondorVM>();

    private final List<CondorVM> vms = new ArrayList<CondorVM>();

    private final Map<Task, Map<CondorVM, Double>> computeSecondsByVm = new HashMap<Task, Map<CondorVM, Double>>();

    private final Map<String, Map<String, Double>> replicas = new LinkedHashMap<String, Map<String, Double>>();

    private final Map<String, Double> fileSizes = new LinkedHashMap<String, Double>();

    private List<Task> topologicalTasks = Collections.emptyList();

    private final Map<Task, Double> upwardRanks = new HashMap<Task, Double>();

    private final Map<CondorVM, List<Reservation>> reservations = new LinkedHashMap<CondorVM, List<Reservation>>();

    private final Map<Task, Double> finishes = new HashMap<Task, Double>();

    AbstractLocalCommPlanningAlgorithm(String label, PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException(label + " requires a PlanningContext from SimulationRunner");
        }
        this.label = label;
        this.context = context;
    }

    final String label() {
        return label;
    }

    final List<Task> prepare() {
        context.validateLocalStaticDag();
        List<Task> tasks = sortedTasks();
        sortedVms();
        WorkflowDagValidator.validateAndAssignDepths(tasks);

        List<Task> byDepth = new ArrayList<Task>(tasks);
        Collections.sort(byDepth, new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                int byDepth = Integer.compare(first.getDepth(), second.getDepth());
                return byDepth != 0 ? byDepth : Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        topologicalTasks = Collections.unmodifiableList(byDepth);
        resetPreparedCaches();

        computeSecondsByVm.clear();
        replicas.clear();
        fileSizes.clear();
        upwardRanks.clear();
        finishes.clear();
        populateCosts(tasks);
        populateReplicasAndSizes(tasks);
        for (int index = topologicalTasks.size() - 1; index >= 0; index--) {
            upwardRank(topologicalTasks.get(index));
        }
        return tasks;
    }

    void resetPreparedCaches() {}

    final List<Task> tasksInTopologicalOrder() {
        return topologicalTasks;
    }

    final List<CondorVM> vms() {
        return vms;
    }

    final double upwardRankOf(Task task) {
        return upwardRanks.get(task).doubleValue();
    }

    final Double plannedFinishOf(Task task) {
        return finishes.get(task);
    }

    final double computeSecondsOn(Task task, CondorVM vm) {
        Double seconds = computeSecondsByVm.get(task).get(vm);
        return seconds == null ? Double.POSITIVE_INFINITY : seconds.doubleValue();
    }

    final boolean isCompatible(Task task, CondorVM vm) {
        return task.getNumberOfPes() <= vm.getNumberOfPes();
    }

    final double stageInFinishTime() {
        return SimulationTiming.earliestCloudletCompletionTime(
                        0.0,
                        SimulationConstants.STAGE_IN_JOB_LENGTH_MI / vms.get(0).getMips(),
                        context.getCloudSimMinEventIntervalSeconds())
                + context.getCloudSimMinEventIntervalSeconds();
    }

    final double readyTime(Task task, double stageInFinish) {
        double readyTime = task.getParentList().isEmpty() ? stageInFinish : 0.0;
        for (Task parent : task.getParentList()) {
            Double parentFinish = finishes.get(parent);
            if (parentFinish == null) {
                throw new IllegalStateException(label + " priority order did not schedule parent "
                        + parent.getCloudletId() + " before task " + task.getCloudletId());
            }
            if (parentFinish.doubleValue() > readyTime) {
                readyTime = parentFinish.doubleValue();
            }
        }
        return readyTime;
    }

    private double dataArrivalOn(Task task, CondorVM vm, double dependencyReady) {
        double arrival = dependencyReady;
        LinkedHashSet<String> attributedNames = new LinkedHashSet<String>();
        for (Task parent : task.getParentList()) {
            LinkedHashSet<String> parentOutputs = new LinkedHashSet<String>();
            for (FileItem file : parent.getFileList()) {
                if (file.getType() == FileType.OUTPUT) {
                    parentOutputs.add(file.getName());
                }
            }
            double secondsFromParent = 0.0;
            boolean hasFilesFromParent = false;
            for (FileItem file : task.getFileList()) {
                if (file.getType() == FileType.INPUT
                        && file.isRealInputFile(task.getFileList())
                        && parentOutputs.contains(file.getName())) {
                    hasFilesFromParent = true;
                    attributedNames.add(file.getName());
                    secondsFromParent += fileStageInSeconds(file, vm, dependencyReady);
                }
            }
            if (!hasFilesFromParent) {
                continue;
            }
            Double parentFinish = finishes.get(parent);
            if (parentFinish == null) {
                throw new IllegalStateException(label + " priority order did not schedule parent "
                        + parent.getCloudletId() + " before task " + task.getCloudletId());
            }
            arrival = Math.max(arrival, parentFinish.doubleValue() + secondsFromParent);
        }
        double externalSeconds = 0.0;
        for (FileItem file : task.getFileList()) {
            if (file.getType() == FileType.INPUT
                    && file.isRealInputFile(task.getFileList())
                    && !attributedNames.contains(file.getName())) {
                externalSeconds += fileStageInSeconds(file, vm, dependencyReady);
            }
        }
        arrival = Math.max(arrival, dependencyReady + externalSeconds);
        double hold = arrival - dependencyReady;
        return hold > 0.0
                ? dependencyReady + Math.max(hold, context.getCloudSimMinEventIntervalSeconds())
                : dependencyReady;
    }

    final double allocate(Task task, CondorVM pinnedVm, double stageInFinish) {
        return allocateInternal(task, pinnedVm, stageInFinish, null);
    }

    final double allocateOptimistic(Task task, Map<CondorVM, Double> octPerVm, double stageInFinish) {
        if (octPerVm == null) {
            throw new IllegalArgumentException(label + " requires per-VM OCT values for task " + task.getCloudletId());
        }
        return allocateInternal(task, null, stageInFinish, octPerVm);
    }

    private double allocateInternal(
            Task task, CondorVM pinnedVm, double stageInFinish, Map<CondorVM, Double> octPerVm) {

        double dispatchable = readyTime(task, stageInFinish);
        List<CondorVM> candidates = pinnedVm != null ? Collections.singletonList(pinnedVm) : vms;
        CondorVM selectedVm = null;
        double selectedStart = 0.0;
        double selectedInputReady = 0.0;
        double selectedFinish = Double.POSITIVE_INFINITY;
        double selectedScore = Double.POSITIVE_INFINITY;
        for (CondorVM vm : candidates) {
            if (!isCompatible(task, vm)) {
                continue;
            }
            double arrival = dataArrivalOn(task, vm, dispatchable);
            double duration = computeSecondsOn(task, vm);
            double start = earliestStart(reservations.get(vm), arrival, duration);
            double finish = start + duration;
            double score = finish;
            if (octPerVm != null) {
                Double oct = octPerVm.get(vm);
                if (oct == null) {
                    throw new IllegalStateException(
                            label + " is missing OCT for task " + task.getCloudletId() + " on VM " + vm.getId());
                }
                score = finish + oct.doubleValue();
            }
            if (score < selectedScore
                    || (Double.compare(score, selectedScore) == 0
                            && selectedVm != null
                            && vm.getId() < selectedVm.getId())) {
                selectedVm = vm;
                selectedStart = start;
                selectedInputReady = arrival;
                selectedFinish = finish;
                selectedScore = score;
            }
        }
        if (selectedVm == null) {
            throw new IllegalStateException(label + " cannot map task " + task.getCloudletId() + "; no candidate VM");
        }
        reserve(task, selectedVm, selectedStart, selectedFinish, selectedInputReady);
        return selectedFinish;
    }

    private void populateCosts(List<Task> tasks) {
        for (Task task : tasks) {
            Map<CondorVM, Double> perVm = new LinkedHashMap<CondorVM, Double>();
            for (CondorVM vm : vms) {
                if (isCompatible(task, vm)) {
                    perVm.put(vm, Double.valueOf(TaskExecutionModel.executionSeconds(task, vm.getId(), vm.getMips())));
                }
            }
            if (perVm.isEmpty()) {
                throw new IllegalArgumentException(label + " cannot plan task " + task.getCloudletId()
                        + "; no compatible VM has sufficient processing elements");
            }
            computeSecondsByVm.put(task, perVm);
        }
    }

    private void populateReplicasAndSizes(List<Task> tasks) {

        LinkedHashSet<String> outputNames = new LinkedHashSet<String>();
        for (Task task : tasks) {
            for (FileItem file : task.getFileList()) {
                if (file.getType() == FileType.OUTPUT) {
                    outputNames.add(file.getName());
                }
                Double previous = fileSizes.put(file.getName(), Double.valueOf(file.getSize()));
                if (previous != null && Double.compare(previous.doubleValue(), file.getSize()) != 0) {
                    throw new IllegalStateException("File '" + file.getName()
                            + "' has inconsistent size declarations: " + previous + " vs "
                            + file.getSize());
                }
            }
        }
        for (Task task : tasks) {
            for (FileItem file : task.getFileList()) {
                Map<String, Double> sites = replicas.get(file.getName());
                if (sites == null) {
                    sites = new LinkedHashMap<String, Double>();
                    replicas.put(file.getName(), sites);
                }
                if (file.getType() == FileType.INPUT && !outputNames.contains(file.getName())) {

                    sites.put(Parameters.SOURCE, Double.valueOf(0.0));
                }
            }
        }
    }

    private double upwardRank(Task task) {
        Double cached = upwardRanks.get(task);
        if (cached != null) {
            return cached.doubleValue();
        }
        double rank = meanComputeSeconds(task);
        if (!task.getChildList().isEmpty()) {
            double bestChild = 0.0;
            for (Task child : task.getChildList()) {
                double via = meanCommunicationSeconds(task, child)
                        + upwardRanks.get(child).doubleValue();
                if (via > bestChild) {
                    bestChild = via;
                }
            }
            rank += bestChild;
        }
        upwardRanks.put(task, Double.valueOf(rank));
        return rank;
    }

    final double meanComputeSeconds(Task task) {
        double total = 0.0;
        Map<CondorVM, Double> perVm = computeSecondsByVm.get(task);
        for (Double seconds : perVm.values()) {
            total += seconds.doubleValue();
        }
        return total / perVm.size();
    }

    /**
     * Average communication cost {@code c̄(parent, child)} used by the upward and downward ranks.
     *
     * <p>FGCS-R3 (2026-11-24) m2 root cause: this method previously averaged the per-pair transfer
     * times. Topcuoglu, Hariri and Wu (IEEE TPDS 13(3), 2002, Section 3) define
     * {@code c̄(i,k) = L̄ + data(i,k) / B̄}, where {@code B̄} is the average transfer rate among the
     * processors and {@code L̄} the average startup time. This model has no communication startup term
     * ({@code L̄ = 0}); {@code B̄} is the mean of the slower-endpoint rate over every ordered pair of
     * distinct VMs compatible with the parent and child respectively. For uniform bandwidth both
     * conventions coincide, so the paper fixtures (HEFT 80, CPOP 86, PEFT 122/133) are unchanged.
     */
    final double meanCommunicationSeconds(Task parent, Task child) {
        double bytes = communicationBytes(parent, child);
        if (!(bytes > 0.0)) {
            return 0.0;
        }
        double rateTotal = 0.0;
        int pairs = 0;
        for (Map.Entry<Integer, CondorVM> first : vmById.entrySet()) {
            for (Map.Entry<Integer, CondorVM> second : vmById.entrySet()) {
                if (first.getKey().equals(second.getKey())
                        || !isCompatible(parent, first.getValue())
                        || !isCompatible(child, second.getValue())) {
                    continue;
                }
                rateTotal +=
                        Math.min(first.getValue().getBw(), second.getValue().getBw());
                pairs++;
            }
        }
        if (pairs == 0) {
            return 0.0;
        }
        return bytes / 1.0e6 / (rateTotal / pairs);
    }

    final double communicationSeconds(Task parent, Task child, CondorVM from, CondorVM to) {
        if (from.getId() == to.getId()) {
            return 0.0;
        }
        return communicationSecondsForBytes(communicationBytes(parent, child), from, to);
    }

    final double communicationSeconds(
            Task parent, Task child, CondorVM from, CondorVM to, Map<Task, Double> edgeBytes) {
        if (from.getId() == to.getId()) {
            return 0.0;
        }
        Double bytes = edgeBytes.get(child);
        if (bytes == null) {
            bytes = Double.valueOf(communicationBytes(parent, child));
            edgeBytes.put(child, bytes);
        }
        return communicationSecondsForBytes(bytes.doubleValue(), from, to);
    }

    private static double communicationSecondsForBytes(double bytes, CondorVM from, CondorVM to) {
        if (!(bytes > 0.0)) {
            return 0.0;
        }
        return bytes / 1.0e6 / Math.min(from.getBw(), to.getBw());
    }

    /**
     * Bytes carried by the parent-to-child edge.
     *
     * <p>FGCS-R3 (2026-11-24) m4 root cause: the edge previously counted a child INPUT that the child
     * also declares as OUTPUT, although {@link #dataArrivalOn} and the runtime pre-execution estimate
     * ({@code WorkflowEngine.aggregatePreExecutionTransfer}) skip it via
     * {@link FileItem#isRealInputFile}. Both of those charge every real INPUT declaration (a repeated
     * declaration is charged again), so this estimate deliberately keeps per-declaration counting to
     * stay consistent with them rather than de-duplicating only here.
     */
    private double communicationBytes(Task parent, Task child) {
        double bytes = 0.0;
        LinkedHashSet<String> parentOutputs = new LinkedHashSet<String>();
        for (FileItem file : parent.getFileList()) {
            if (file.getType() == FileType.OUTPUT) {
                parentOutputs.add(file.getName());
            }
        }
        if (parentOutputs.isEmpty()) {
            return 0.0;
        }
        List<FileItem> childFiles = child.getFileList();
        LinkedHashSet<String> childOutputs = new LinkedHashSet<String>();
        List<FileItem> candidates = new ArrayList<FileItem>();
        for (FileItem file : childFiles) {
            FileType type = file.getType();
            if (type == FileType.OUTPUT) {
                childOutputs.add(file.getName());
            } else if (type == FileType.INPUT) {
                candidates.add(file);
            }
        }
        for (FileItem file : candidates) {
            String name = file.getName();
            // Equivalent to FileItem.isRealInputFile(childFiles) in one linear pass.
            if (!childOutputs.contains(name) && parentOutputs.contains(name)) {
                bytes += file.getSize();
            }
        }
        return bytes;
    }

    private double fileStageInSeconds(FileItem file, CondorVM vm, double dependencyReady) {
        Map<String, Double> sites = replicas.get(file.getName());
        if (sites == null || sites.isEmpty()) {
            throw new IllegalStateException("Required input file '" + file.getName() + "' has no registered replica");
        }
        String vmSite = Integer.toString(vm.getId());
        double maxBwth = 0.0;
        for (Map.Entry<String, Double> replica : sites.entrySet()) {
            if (replica.getValue().doubleValue() > dependencyReady) {
                continue;
            }
            String site = replica.getKey();
            if (site.equals(vmSite)) {
                return 0.0;
            }
            double bwth;
            if (site.equals(Parameters.SOURCE)) {
                bwth = vm.getBw();
            } else {
                int sourceVmId;
                try {
                    sourceVmId = Integer.parseInt(site);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException(
                            "Replica site '" + site + "' is neither the source nor a VM identifier", e);
                }
                CondorVM sourceVm = vmById.get(Integer.valueOf(sourceVmId));
                if (sourceVm == null) {
                    throw new IllegalStateException("Replica site VM " + sourceVmId + " is unavailable for input file '"
                            + file.getName() + "'");
                }
                bwth = Math.min(vm.getBw(), sourceVm.getBw());
            }
            if (bwth > maxBwth) {
                maxBwth = bwth;
            }
        }
        if (!(maxBwth > 0.0)) {
            throw new IllegalStateException("Required input file '" + file.getName()
                    + "' has no usable replica at dependency-ready time " + dependencyReady);
        }
        return file.getSize() / 1.0e6 / maxBwth;
    }

    private void reserve(Task task, CondorVM vm, double start, double finish, double inputReady) {
        reservations.get(vm).add(new Reservation(start, finish));
        Collections.sort(reservations.get(vm), new Comparator<Reservation>() {
            @Override
            public int compare(Reservation first, Reservation second) {
                int byStart = Double.compare(first.start, second.start);
                return byStart != 0 ? byStart : Double.compare(first.finish, second.finish);
            }
        });
        finishes.put(task, Double.valueOf(finish));
        task.setVmId(vm.getId());
        task.setStaticScheduleStartTime(start);

        String vmSite = Integer.toString(vm.getId());
        for (FileItem file : task.getFileList()) {
            if (file.getType() == FileType.INPUT && file.isRealInputFile(task.getFileList())) {
                registerReplica(file.getName(), vmSite, inputReady);
            } else if (file.getType() == FileType.OUTPUT) {
                registerReplica(file.getName(), vmSite, finish);
            }
        }
    }

    private void registerReplica(String fileName, String site, double availableAt) {
        Map<String, Double> sites = replicas.get(fileName);
        Double previous = sites.get(site);
        if (previous == null || availableAt < previous.doubleValue()) {
            sites.put(site, Double.valueOf(availableAt));
        }
    }

    private static double earliestStart(List<Reservation> schedule, double readyTime, double duration) {
        double start = readyTime;
        for (Reservation reservation : schedule) {
            if (start + duration <= reservation.start) {
                return start;
            }
            start = Math.max(start, reservation.finish);
        }
        return start;
    }

    private List<Task> sortedTasks() {
        if (getTaskList() == null || getTaskList().isEmpty()) {
            throw new IllegalArgumentException(label + " requires at least one task");
        }
        List<Task> tasks = new ArrayList<Task>(getTaskList());
        Collections.sort(tasks, new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                return Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        return tasks;
    }

    private void sortedVms() {
        if (getVmList() == null || getVmList().isEmpty()) {
            throw new IllegalArgumentException(label + " requires at least one VM");
        }
        vms.clear();
        for (Object object : getVmList()) {
            if (!(object instanceof CondorVM)) {
                throw new IllegalArgumentException(label + " requires CondorVM instances");
            }
            vms.add((CondorVM) object);
        }
        Collections.sort(vms, new Comparator<CondorVM>() {
            @Override
            public int compare(CondorVM first, CondorVM second) {
                return Integer.compare(first.getId(), second.getId());
            }
        });
        vmById.clear();
        reservations.clear();
        for (CondorVM vm : vms) {
            vmById.put(Integer.valueOf(vm.getId()), vm);
            reservations.put(vm, new ArrayList<Reservation>());
        }
    }

    private static final class Reservation {

        private final double start;
        private final double finish;

        private Reservation(double start, double finish) {
            this.start = start;
            this.finish = finish;
        }
    }
}
