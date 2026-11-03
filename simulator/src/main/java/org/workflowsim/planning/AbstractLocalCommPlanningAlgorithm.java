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

/**
 * Shared mechanism base class for communication-aware static DAG planners on the LOCAL
 * file system (the Topcuoglu family of list scheduling).
 *
 * <p>Hosts the contention-free estimation shared by LOCAL_HEFT, LOCAL_CPOP and LOCAL_PEFT:
 * integer-MI cost conversion per task x compatible VM, upward rank, a replica catalog with
 * availability times, per-parent grouped pre-execution transfer and insertion-based
 * compute reservation. Input transfer may overlap VM busy periods; external inputs
 * transfer from the dependency-ready instant; input replicas become available when the
 * whole hold completes, output replicas when the computation completes. A candidate only
 * reads planned replicas already available at its dependency-ready instant, and never
 * mistakes allocation order for time order.</p>
 *
 * <p>This is an estimate over the current partial plan and does not fully replay runtime
 * events. Same-instant event order, later-planned tasks producing replicas earlier, the
 * short-computation completion clamp and the contention model may still make the plan
 * differ from runtime; see {@link LocalHeftPlanningAlgorithm} for the detailed bounds.</p>
 *
 * <p><b>Applicability preconditions</b> (enforced by
 * {@link PlanningContext#validateLocalStaticDag()}): LOCAL file system, NONE clustering,
 * no failures, no modeling overhead, the {@code preExecutionTransferDelayV1} data movement
 * model and SPACE_SHARED VMs. A subclass {@code run()} first calls {@link #prepare()} and
 * then {@link #allocate} in its own priority order.</p>
 */
abstract class AbstractLocalCommPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    /** Algorithm label (e.g. "LOCAL_HEFT"), used in diagnostic messages. */
    private final String label;

    /** vmId -> VM. */
    private final Map<Integer, CondorVM> vmById = new LinkedHashMap<Integer, CondorVM>();
    /** VMs in ascending VM ID order (available after {@link #prepare()}). */
    private final List<CondorVM> vms = new ArrayList<CondorVM>();
    /** Task -> (compatible VM -> effective compute seconds for the integer MI). */
    private final Map<Task, Map<CondorVM, Double>> computeSecondsByVm = new HashMap<Task, Map<CondorVM, Double>>();
    /** File name -> (site -> earliest replica availability time); a site is SOURCE or a VM ID string. */
    private final Map<String, Map<String, Double>> replicas = new LinkedHashMap<String, Map<String, Double>>();
    /** File name -> size in bytes. */
    private final Map<String, Double> fileSizes = new LinkedHashMap<String, Double>();
    /**
     * Forward topological order after DAG validation; used only for rank/OCT dynamic
     * programming and does not change the ID order of scheduling.
     */
    private List<Task> topologicalTasks = Collections.emptyList();
    /** Task -> upward rank. */
    private final Map<Task, Double> upwardRanks = new HashMap<Task, Double>();
    /** VM -> reserved intervals. */
    private final Map<CondorVM, List<Reservation>> reservations = new LinkedHashMap<CondorVM, List<Reservation>>();
    /** Task -> planned finish time. */
    private final Map<Task, Double> finishes = new HashMap<Task, Double>();

    AbstractLocalCommPlanningAlgorithm(String label, PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException(label + " requires a PlanningContext from SimulationRunner");
        }
        this.label = label;
        this.context = context;
    }

    /** Algorithm label, used in subclass diagnostic messages. */
    final String label() {
        return label;
    }

    /**
     * Precondition validation + deterministic ordering + cost/replica assembly + full
     * upward rank computation.
     *
     * @return task list in ascending task ID order
     */
    final List<Task> prepare() {
        context.validateLocalStaticDag();
        List<Task> tasks = sortedTasks();
        sortedVms();
        WorkflowDagValidator.validateAndAssignDepths(tasks);
        // Every validated edge strictly increases depth. Only the replica copy is sorted,
        // preserving the Task/VM scheduling tie-break rules.
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
        // The same planner may run again; ranks, finished state and evolved replicas all
        // belong to the previous plan only.
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

    /**
     * Invalidation hook for subclass caches derived during prepare; does not replace
     * base-class validation and cost assembly.
     */
    void resetPreparedCaches() {}

    /**
     * Read-only forward topological order after validation; every parent precedes its
     * children, for subclass static rank/OCT computation.
     */
    final List<Task> tasksInTopologicalOrder() {
        return topologicalTasks;
    }

    /** VMs in ascending VM ID order (available after {@link #prepare()}). */
    final List<CondorVM> vms() {
        return vms;
    }

    /** Upward rank of the task (available after {@link #prepare()}). */
    final double upwardRankOf(Task task) {
        return upwardRanks.get(task).doubleValue();
    }

    /** Planned finish time of the task; null when not yet planned. */
    final Double plannedFinishOf(Task task) {
        return finishes.get(task);
    }

    /** Effective compute seconds on the given VM; positive infinity when the PE counts are incompatible. */
    final double computeSecondsOn(Task task, CondorVM vm) {
        Double seconds = computeSecondsByVm.get(task).get(vm);
        return seconds == null ? Double.POSITIVE_INFINITY : seconds.doubleValue();
    }

    final boolean isCompatible(Task task, CondorVM vm) {
        return task.getNumberOfPes() <= vm.getNumberOfPes();
    }

    /**
     * Root tasks wait for the model stage-in Job: as in the SHARED_STORAGE_* planners, the
     * stage-in Job executes 110 MI on the fallback VM (smallest VM ID), its completion time
     * is aligned by the minimum event interval rule, and the engine releases the root
     * compute Job one further kernel interval after receiving the completion event.
     */
    final double stageInFinishTime() {
        return SimulationTiming.earliestCloudletCompletionTime(
                        0.0,
                        SimulationConstants.STAGE_IN_JOB_LENGTH_MI / vms.get(0).getMips(),
                        context.getCloudSimMinEventIntervalSeconds())
                + context.getCloudSimMinEventIntervalSeconds();
    }

    /**
     * Ready time of the task = the maximum planned finish time over all parents; for a root
     * task = the stage-in finish time.
     */
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

    /**
     * Estimate the completion instant of the whole input hold from the replicas visible at
     * the dependency-ready instant.
     *
     * <p>Preserves the per-parent grouping semantics of the contention-free runtime model:
     * files from the same parent accumulate serially, different parents run in parallel, and
     * the candidate arrival instant is {@code finish(pred) + sum c_file(pred, vm)}. External
     * inputs transfer starting at this task's dependency-ready instant; the platform-level
     * registration of stage-in does not mean the root task's target VM already holds the
     * file. A positive hold is clamped by the same minimum event interval, and input
     * replicas become visible to later estimates only once the whole hold completes.</p>
     *
     * @param task task to allocate
     * @param vm target VM
     * @param dependencyReady instant at which all parents have completed; the bootstrap
     *                        instant for a root task
     * @return instant at which all input holds complete and the computation can be
     *         dispatched, excluding VM queueing
     */
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

    /**
     * Allocate the task to the candidate VM with the earliest finish time (insertion-based
     * EFT) and evolve replica state.
     *
     * <p>Pre-execution transfer delay semantics: the data arrival instant is the latest
     * arrival among the per-parent parallel transfers; VM reserved intervals cover only the
     * computation (transfers may overlap VM busy periods). The start instant is
     * {@code max(avail[vm], dataArrival)} found by insertion-based search.</p>
     *
     * @param pinnedVm when non-null, only this VM is considered (CPOP critical-path
     *                 processor binding); when null, the smallest EFT over all VMs wins,
     *                 with the smaller VM ID breaking ties
     * @return planned finish time
     */
    final double allocate(Task task, CondorVM pinnedVm, double stageInFinish) {
        return allocateInternal(task, pinnedVm, stageInFinish, null);
    }

    /**
     * Allocate the task to the VM with the smallest optimistic completion time
     * (insertion-based {@code EFT + OCT(t, vm)}) and evolve replica state.
     *
     * <p>Identical to {@link #allocate(Task, CondorVM, double)} except for the scoring
     * function: same dispatchable instant, data arrival instant, insertion-based earliest
     * start search, reservation and replica evolution semantics; the return value is still
     * the EFT finish time (without the OCT term). The smaller VM ID breaks score ties.</p>
     *
     * @param octPerVm OCT values of the task on all VMs; missing any VM throws
     *                 {@link IllegalStateException}
     * @return planned finish time (EFT, without OCT)
     */
    final double allocateOptimistic(Task task, Map<CondorVM, Double> octPerVm, double stageInFinish) {
        if (octPerVm == null) {
            throw new IllegalArgumentException(label + " requires per-VM OCT values for task " + task.getCloudletId());
        }
        return allocateInternal(task, null, stageInFinish, octPerVm);
    }

    private double allocateInternal(
            Task task, CondorVM pinnedVm, double stageInFinish, Map<CondorVM, Double> octPerVm) {
        // Runtime mirror: a Job is released only after all parent tasks return, so the
        // dispatchable instant is max(all parent finish instants, data arrival instant).
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
        // Global OUTPUT name set: decides which INPUTs are true external inputs (registered
        // to SOURCE at parse time by ClusteringEngine) and which are intermediate files
        // (registered only by the completion of the producing task).
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
                    // SOURCE is available from the start; the transfer to the target VM still begins
                    // only after the consuming Job becomes dependency-ready.
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

    /**
     * Mean effective compute seconds of the task over all compatible VMs (w-bar); infinite
     * values of infeasible candidates do not enter the rank.
     */
    final double meanComputeSeconds(Task task) {
        double total = 0.0;
        Map<CondorVM, Double> perVm = computeSecondsByVm.get(task);
        for (Double seconds : perVm.values()) {
            total += seconds.doubleValue();
        }
        return total / perVm.size();
    }

    /**
     * Return the mean communication seconds over ordered cross-VM pairs, PE-compatible on
     * both ends, for all files transferred between a parent and a child task.
     *
     * <p>The mean covers only ordered pairs of distinct VMs (same-VM transfer is zero and
     * handled by runtime replica locality, consistent with the c-bar_ij convention of the
     * HEFT paper); a cross-VM pair uses
     * {@code bytes / (1e6 * min(bw_i, bw_j))}. Under uniform bandwidth this degenerates to
     * the edge weight itself.</p>
     */
    final double meanCommunicationSeconds(Task parent, Task child) {
        double bytes = communicationBytes(parent, child);
        if (!(bytes > 0.0)) {
            return 0.0;
        }
        double total = 0.0;
        int pairs = 0;
        for (Map.Entry<Integer, CondorVM> first : vmById.entrySet()) {
            for (Map.Entry<Integer, CondorVM> second : vmById.entrySet()) {
                if (first.getKey().equals(second.getKey())
                        || !isCompatible(parent, first.getValue())
                        || !isCompatible(child, second.getValue())) {
                    continue;
                }
                double minBw =
                        Math.min(first.getValue().getBw(), second.getValue().getBw());
                total += bytes / 1.0e6 / minBw;
                pairs++;
            }
        }
        // With a single VM there is no cross-processor communication; avoid 0/0 turning
        // the rank into NaN.
        return pairs == 0 ? 0.0 : total / pairs;
    }

    /**
     * Communication seconds for all files transferred from the parent to the child over the
     * given VM pair (used by the OCT recurrence).
     *
     * <p>Same convention as {@link #meanCommunicationSeconds}: the cross-VM transfer rate is
     * {@code min(bw_from, bw_to)}, and no shared file or the same VM yields zero. Independent
     * of replica state -- OCT is a static pre-scheduling quantity computed only from the
     * bytes on the task edge and the endpoint bandwidths, with no replica locality
     * discount.</p>
     */
    final double communicationSeconds(Task parent, Task child, CondorVM from, CondorVM to) {
        if (from.getId() == to.getId()) {
            return 0.0;
        }
        return communicationSecondsForBytes(communicationBytes(parent, child), from, to);
    }

    /**
     * Reuse the static edge byte count within one OCT row-group computation for the same
     * parent, including a zero intersection. The caller owns this local map; it is not
     * retained across prepare or the first lazy OCT phase, and it does not cache replica
     * state.
     */
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

    /** Total bytes of the parent's OUTPUT files consumed by the child's INPUT files. */
    private double communicationBytes(Task parent, Task child) {
        double bytes = 0.0;
        LinkedHashSet<String> parentOutputs = new LinkedHashSet<String>();
        for (FileItem file : parent.getFileList()) {
            if (file.getType() == FileType.OUTPUT) {
                parentOutputs.add(file.getName());
            }
        }
        for (FileItem file : child.getFileList()) {
            if (file.getType() == FileType.INPUT && parentOutputs.contains(file.getName())) {
                bytes += file.getSize();
            }
        }
        return bytes;
    }

    /**
     * Select the fastest source among the replicas visible at the task's dependency-ready
     * instant; a target replica that has not arrived yet cannot count as local zero cost.
     * Availability times of planned tasks are read-only and no replica is registered early
     * during candidate trials.
     */
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
        // Inputs are registered when the whole hold completes, possibly before the
        // computation starts; outputs are available only after the computation completes.
        // Allocation order differs from actual time order, so timestamps must be kept
        // instead of treating insertion into the collection as immediately readable.
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

    /** VM occupancy interval during planning. */
    private static final class Reservation {

        private final double start;
        private final double finish;

        private Reservation(double start, double finish) {
            this.start = start;
            this.finish = finish;
        }
    }
}
