package org.workflowsim.scheduling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.scheduling.ebpsm.EbpsmBudgetDistributor;
import org.workflowsim.scheduling.ebpsm.EbpsmBudgetLedger;
import org.workflowsim.scheduling.ebpsm.EbpsmTask;
import org.workflowsim.scheduling.ebpsm.EbpsmVmType;
import org.workflowsim.utils.EbpsmConfig;
import org.workflowsim.utils.EbpsmRegistry;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.TaskExecutionModel;

public final class EbpsmSchedulingAlgorithm extends BaseSchedulingAlgorithm
        implements JobReturnListener, WorkflowContextAware {

    private final EbpsmConfig config;
    private Map<Integer, Set<String>> cachedInputs;
    private Map<Integer, Set<String>> containerImages;
    private List<Task> workflowTasks;
    private Map<Integer, Integer> taskWorkflowIndices;
    private EbpsmBudgetLedger ledger;
    private final Map<Integer, Boolean> containerDeployedAtDispatch = new TreeMap<Integer, Boolean>();

    public EbpsmSchedulingAlgorithm() {
        this(null);
    }

    public EbpsmSchedulingAlgorithm(EbpsmConfig config) {
        this.config = config;
    }

    @Override
    public void bindWorkflows(List<Task> tasks, Map<Integer, Integer> workflowIndices) {
        if (tasks == null || workflowIndices == null) {
            throw new IllegalArgumentException("EBPSM workflow context requires Tasks and workflow indices");
        }
        if (ledger != null) {
            throw new IllegalStateException("EBPSM workflow context cannot change after budgets were distributed");
        }
        this.workflowTasks = new ArrayList<Task>(tasks);
        this.taskWorkflowIndices = new TreeMap<Integer, Integer>(workflowIndices);
    }

    public EbpsmBudgetLedger getBudgetLedger() {
        if (ledger == null) {
            throw new IllegalStateException("EBPSM budgets are distributed at the first scheduling round");
        }
        return ledger;
    }

    @Override
    public void run() {
        validateCloudletCompatibility();
        EbpsmConfig active = activeConfig();
        ensureCaches(active);
        ensureLedger(active);
        List<Job> jobs = new ArrayList<Job>();
        for (Object item : getCloudletList()) {
            jobs.add((Job) item);
        }
        jobs.sort((first, second) -> {
            int byStart = Double.compare(estimatedStart(first), estimatedStart(second));
            return byStart != 0 ? byStart : Integer.compare(first.getCloudletId(), second.getCloudletId());
        });
        List<Integer> unaffordable = new ArrayList<Integer>();
        for (Job job : jobs) {
            List<CondorVM> idle = new ArrayList<CondorVM>();
            for (CondorVM vm : sortedVmsById()) {
                if (!isCompatible(job, vm)) {
                    continue;
                }
                if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                    idle.add(vm);
                }
            }
            if (idle.isEmpty()) {
                continue;
            }
            if (job.getTaskList().isEmpty()) {
                assign(job, idle.get(0));
                continue;
            }
            Task task = singleTask(job);
            double budget = ledger.budgetOf(task.getCloudletId());
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
                unaffordable.add(task.getCloudletId());
                continue;
            }
            containerDeployedAtDispatch.put(task.getCloudletId(), hasContainer(task, selected.getId(), active));
            ledger.markScheduled(task.getCloudletId());
            assign(job, selected);
        }
        requireProgressPossible(unaffordable);
    }

    private void requireProgressPossible(List<Integer> unaffordable) {
        if (unaffordable.isEmpty()) {
            return;
        }
        for (CondorVM vm : sortedVmsById()) {
            if (vm.getState() != WorkflowSimTags.VM_STATUS_IDLE) {
                return;
            }
        }
        throw new IllegalStateException("EBPSM tasks " + unaffordable + " fit no idle VM within their sub-budgets"
                + " while the whole pool is idle, so no running attempt can change a VM or cache state; the"
                + " fixed declared pool cannot provision the paper's fallback VM");
    }

    @Override
    public void onJobReturned(Job job) {
        if (job.getTaskList().isEmpty()) {
            return;
        }
        EbpsmConfig active = activeConfig();
        ensureCaches(active);
        if (ledger == null) {
            throw new IllegalStateException("EBPSM job " + job.getCloudletId() + " returned before any dispatch");
        }
        Task returned = singleTask(job);
        Boolean deployed = containerDeployedAtDispatch.remove(returned.getCloudletId());
        if (deployed == null) {
            throw new IllegalStateException("EBPSM task " + returned.getCloudletId() + " returned without dispatch");
        }
        double envelope = returned.getTaskFinishTime() - job.getExecStartTime();
        if (!(envelope >= 0.0) || !Double.isFinite(envelope)) {
            throw new IllegalStateException("EBPSM job " + job.getCloudletId() + " has an invalid execution envelope ["
                    + job.getExecStartTime() + ", " + returned.getTaskFinishTime() + "]");
        }
        double billed = EbpsmBudgetDistributor.billedSeconds(
                envelope + (deployed ? 0.0 : active.getContainerProvisioningDelaySeconds()));
        double actualCost = billed * active.getVmCostPerSecond(job.getVmId());
        if (returned.getCloudletStatus() == Cloudlet.SUCCESS) {
            ledger.completed(returned.getCloudletId(), actualCost);
        } else {
            ledger.failed(returned.getCloudletId(), actualCost);
        }
        Set<String> files = cachedInputs.get(job.getVmId());
        Set<String> images = containerImages.get(job.getVmId());
        if (files == null || images == null) {
            throw new IllegalStateException(
                    "EBPSM job " + job.getCloudletId() + " returned from undeclared VM " + job.getVmId());
        }
        for (Task task : job.getTaskList()) {
            if (task.getCloudletStatus() != Cloudlet.SUCCESS) {
                continue;
            }
            images.add(active.getContainerImage(task.getCloudletId()));
            for (FileItem file : task.getFileList()) {
                if (file.getType() == FileType.OUTPUT || file.isRealInputFile(task.getFileList())) {
                    files.add(file.getName());
                }
            }
        }
    }

    private EbpsmConfig activeConfig() {
        return config == null ? EbpsmRegistry.get() : config;
    }

    private void assign(Job job, CondorVM vm) {
        vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
        job.setVmId(vm.getId());
        getScheduledList().add(job);
    }

    private CondorVM choose(List<CondorVM> candidates, Task task, double budget, EbpsmConfig active) {
        CondorVM best = null;
        double bestSeconds = Double.POSITIVE_INFINITY;
        for (CondorVM vm : candidates) {
            double seconds = predictedSeconds(
                    task, vm, active, hasRequiredInput(task, vm.getId()), hasContainer(task, vm.getId(), active));
            if (cost(seconds, vm, active) <= budget + 1.0e-9
                    && (seconds < bestSeconds - 1.0e-9
                            || (Math.abs(seconds - bestSeconds) <= 1.0e-9 && vm.getId() < best.getId()))) {
                best = vm;
                bestSeconds = seconds;
            }
        }
        return best;
    }

    private static double cost(double seconds, CondorVM vm, EbpsmConfig active) {
        return EbpsmBudgetDistributor.billedSeconds(seconds) * active.getVmCostPerSecond(vm.getId());
    }

    private double predictedSeconds(
            Task task, CondorVM vm, EbpsmConfig active, boolean inputCached, boolean containerDeployed) {
        double inputBytes = 0.0;
        double outputBytes = 0.0;
        for (FileItem file : task.getFileList()) {
            if (file.isRealInputFile(task.getFileList())) {
                inputBytes += file.getSize();
            } else if (file.getType() == FileType.OUTPUT) {
                outputBytes += file.getSize();
            }
        }
        double read = inputCached
                ? 0.0
                : inputBytes / active.getGlobalReadRateBytesPerSecond() + inputBytes / (1000000.0 * vm.getBw());
        double write = outputBytes / active.getGlobalWriteRateBytesPerSecond() + outputBytes / (1000000.0 * vm.getBw());
        double container = containerDeployed ? 0.0 : active.getContainerProvisioningDelaySeconds();
        double compute = TaskExecutionModel.executionSeconds(task, vm.getId(), vm.getMips());
        return read + compute + write + container;
    }

    private boolean hasRequiredInput(Task task, int vmId) {
        Set<String> cached = cachedInputs.getOrDefault(vmId, new HashSet<String>());
        for (FileItem file : task.getFileList()) {
            if (file.isRealInputFile(task.getFileList()) && !cached.contains(file.getName())) {
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

    private static Task singleTask(Job job) {
        if (job.getTaskList().size() != 1) {
            throw new IllegalStateException("EBPSM requires one Task per compute Job (clustering NONE); Job "
                    + job.getCloudletId() + " carries " + job.getTaskList().size());
        }
        return job.getTaskList().get(0);
    }

    private void ensureLedger(EbpsmConfig active) {
        if (ledger != null) {
            return;
        }
        if (workflowTasks == null) {
            throw new IllegalStateException("EBPSM requires the parsed workflow context before the first round"
                    + " (WorkflowContextAware.bindWorkflows)");
        }
        Map<String, Integer> classIds = new LinkedHashMap<String, Integer>();
        Map<Integer, CondorVM> representatives = new TreeMap<Integer, CondorVM>();
        Map<Integer, List<CondorVM>> members = new TreeMap<Integer, List<CondorVM>>();
        List<EbpsmVmType> types = new ArrayList<EbpsmVmType>();
        for (CondorVM vm : sortedVmsById()) {
            double price = active.getVmCostPerSecond(vm.getId());
            String key = vm.getMips() + "|" + price + "|" + vm.getNumberOfPes() + "|" + vm.getBw();
            Integer typeId = classIds.get(key);
            if (typeId == null) {
                typeId = classIds.size();
                classIds.put(key, typeId);
                representatives.put(typeId, vm);
                members.put(typeId, new ArrayList<CondorVM>());
                types.add(new EbpsmVmType(typeId, vm.getMips(), price, 0.0));
            }
            members.get(typeId).add(vm);
        }
        Map<Integer, List<EbpsmTask>> byWorkflow = new TreeMap<Integer, List<EbpsmTask>>();
        List<Task> ordered = new ArrayList<Task>(workflowTasks);
        ordered.sort((first, second) -> Integer.compare(first.getCloudletId(), second.getCloudletId()));
        for (Task task : ordered) {
            Integer workflow = taskWorkflowIndices.get(task.getCloudletId());
            if (workflow == null) {
                throw new IllegalStateException("EBPSM task " + task.getCloudletId() + " has no workflow index");
            }
            Map<Integer, Double> processing = new LinkedHashMap<Integer, Double>();
            for (Map.Entry<Integer, CondorVM> entry : representatives.entrySet()) {
                if (!isCompatible(task, entry.getValue())) {
                    continue;
                }
                double seconds = predictedSeconds(task, entry.getValue(), active, false, true);
                for (CondorVM member : members.get(entry.getKey())) {
                    double other = predictedSeconds(task, member, active, false, true);
                    if (Math.abs(other - seconds) > 1.0e-9) {
                        throw new IllegalStateException(
                                "EBPSM VM class of VM " + entry.getValue().getId()
                                        + " is not a VM type: task " + task.getCloudletId() + " takes " + seconds + " s"
                                        + " there but " + other + " s on VM " + member.getId());
                    }
                }
                processing.put(entry.getKey(), seconds);
            }
            if (processing.isEmpty()) {
                throw new IllegalStateException("EBPSM task " + task.getCloudletId() + " fits no declared VM type");
            }
            List<Integer> parents = new ArrayList<Integer>();
            for (Task parent : task.getParentList()) {
                parents.add(parent.getCloudletId());
            }
            List<EbpsmTask> list = byWorkflow.get(workflow);
            if (list == null) {
                list = new ArrayList<EbpsmTask>();
                byWorkflow.put(workflow, list);
            }
            list.add(new EbpsmTask(task.getCloudletId(), parents, processing));
        }
        EbpsmBudgetDistributor distributor = new EbpsmBudgetDistributor(active.getContainerProvisioningDelaySeconds());
        for (Map.Entry<Integer, List<EbpsmTask>> entry : byWorkflow.entrySet()) {
            double minimum = 0.0;
            for (EbpsmTask task : entry.getValue()) {
                double cheapest = Double.POSITIVE_INFINITY;
                for (EbpsmVmType type : types) {
                    if (task.getProcessingSecondsByVmType().containsKey(type.getVmTypeId())) {
                        cheapest = Math.min(cheapest, distributor.cost(task, type));
                    }
                }
                minimum += cheapest;
            }
            Double declared = active.getWorkflowBudgets().get(entry.getKey());
            if (declared != null && declared < minimum - 1.0e-9) {
                throw new IllegalStateException("EBPSM workflow " + entry.getKey() + " budget " + declared
                        + " is below its cheapest-type cost " + minimum + "; the paper rejects such jobs");
            }
        }
        ledger = new EbpsmBudgetLedger(distributor, types, byWorkflow, active.getWorkflowBudgets());
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
