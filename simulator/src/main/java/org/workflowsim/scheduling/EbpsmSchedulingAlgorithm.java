package org.workflowsim.scheduling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.utils.EbpsmConfig;
import org.workflowsim.utils.EbpsmRegistry;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.TaskExecutionModel;

/**
 * Fixed-pool subset of EBPSM (Hilman, Rodriguez and Buyya, "Resource-sharing Policy in
 * Multi-tenant Scientific Workflow-as-a-Service Cloud Platform", Algorithm 2).
 *
 * <p>FGCS-R3 (2026-11-24) root causes fixed here:
 *
 * <ul>
 *   <li>A1: outputs and container images were "published" only at dispatch, where no Task is
 *       {@code SUCCESS}, so the cache never grew. Publication now happens in
 *       {@link #onJobReturned(Job)}: a successful Task leaves its container image and its input and
 *       output files in the executing VM's local storage (paper Sec. 3.2); a failed attempt
 *       publishes nothing. Local storage is unbounded (no FIFO eviction in this subset).
 *   <li>A2: a task that fit no idle VM's sub-budget was silently skipped and could starve until
 *       the event queue drained. Algorithm 2's last resort is provisioning a new VM of the fastest
 *       type within budget, which this fixed declared pool does not model. The task is therefore
 *       deferred while any VM is busy after the round (a return is what frees a VM or grows a
 *       cache). A round that leaves such a task and an entirely idle pool, or a budget that even
 *       the fully cached prediction exceeds on every compatible VM, fails explicitly.
 *   <li>A2: the prediction uses the effective execution length on the candidate VM
 *       ({@link TaskExecutionModel}) and counts only real input files, matching the runtime's
 *       compute envelope; read/write/container terms follow paper Eqs. 1–2 with the declared
 *       {@link EbpsmConfig} rates. They are the policy's estimate only: the platform runtime does not
 *       model EBPSM's VM-local cache and still charges its own data-movement model (for SHARED, the
 *       storage read on every attempt), so cache locality changes decisions, not simulated read time.
 * </ul>
 */
public final class EbpsmSchedulingAlgorithm extends BaseSchedulingAlgorithm implements JobReturnListener {

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
        validateCloudletCompatibility();
        EbpsmConfig active = activeConfig();
        ensureCaches(active);
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
            Task task = job.getTaskList().get(0);
            double budget = active.getTaskBudget(task.getCloudletId());
            requireAttainableBudget(task, budget, active);
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
                + " fixed declared pool cannot provision the paper's fallback VM (FGCS-R3 A2)");
    }

    /**
     * Publishes the container image and the input/output files of every successful Task of a
     * returned attempt into the executing VM's local storage.
     *
     * @param job returned attempt with final Task statuses
     */
    @Override
    public void onJobReturned(Job job) {
        if (job.getTaskList().isEmpty()) {
            return;
        }
        EbpsmConfig active = activeConfig();
        ensureCaches(active);
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

    private void requireAttainableBudget(Task task, double budget, EbpsmConfig active) {
        for (CondorVM vm : sortedVmsById()) {
            if (isCompatible(task, vm)
                    && cost(predictedSeconds(task, vm, active, true, true), vm, active) <= budget + 1.0e-9) {
                return;
            }
        }
        throw new IllegalStateException("EBPSM task " + task.getCloudletId() + " exceeds its sub-budget " + budget
                + " on every compatible VM even with cached input and deployed container; the fixed"
                + " declared pool cannot provision the paper's fallback VM (FGCS-R3 A2)");
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
        return Math.ceil(seconds) * active.getVmCostPerSecond(vm.getId());
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

    private static double estimatedStart(Job job) {
        double latest = 0.0;
        for (Object parentItem : job.getParentList()) {
            Task parent = (Task) parentItem;
            latest = Math.max(latest, parent.getFinishTime());
        }
        return latest;
    }
}
