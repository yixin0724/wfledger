package org.workflowsim.scheduling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.utils.ReshiConfig;
import org.workflowsim.utils.ReshiRankTable;
import org.workflowsim.utils.ReshiRegistry;
import org.workflowsim.utils.SimulationRandom;

/** Reshi-C/M resource recommendation and ready-queue prioritization. */
public final class ReshiSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    private final ReshiStrategy strategy;
    private final ReshiConfig config;

    public ReshiSchedulingAlgorithm(ReshiStrategy strategy) {
        this(strategy, null);
    }

    public ReshiSchedulingAlgorithm(ReshiStrategy strategy, ReshiConfig config) {
        if (strategy == null) {
            throw new IllegalArgumentException("Reshi strategy is required");
        }
        this.strategy = strategy;
        this.config = config;
    }

    private ReshiConfig configuration() {
        return config == null ? ReshiRegistry.get() : config;
    }

    @Override
    public void run() {
        List<Job> jobs = new ArrayList<Job>();
        for (Object item : getCloudletList()) {
            jobs.add((Job) item);
        }
        if (strategy == ReshiStrategy.RESHI_C) {
            Collections.sort(jobs, new Comparator<Job>() {
                @Override
                public int compare(Job first, Job second) {
                    int byChildren = Integer.compare(
                            second.getChildList().size(), first.getChildList().size());
                    return byChildren != 0
                            ? byChildren
                            : Integer.compare(first.getCloudletId(), second.getCloudletId());
                }
            });
        } else if (strategy == ReshiStrategy.RESHI_M) {
            Collections.sort(jobs, new Comparator<Job>() {
                @Override
                public int compare(Job first, Job second) {
                    int byRuntime = Double.compare(runtime(second), runtime(first));
                    return byRuntime != 0 ? byRuntime : Integer.compare(first.getCloudletId(), second.getCloudletId());
                }
            });
        }

        List<CondorVM> vms = sortedVmsById();
        for (Job job : jobs) {
            List<CondorVM> free = new ArrayList<CondorVM>();
            for (CondorVM vm : vms) {
                if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                    free.add(vm);
                }
            }
            if (free.isEmpty()) {
                break;
            }
            CondorVM selected = selectVm(job, free);
            selected.setState(WorkflowSimTags.VM_STATUS_BUSY);
            job.setVmId(selected.getId());
            getScheduledList().add(job);
        }
    }

    private double runtime(Job job) {
        ReshiConfig active = configuration();
        double base = active.getTaskHistory()
                .averageRuntimeFor(job.getTaskList().get(0).getType());
        if (active.getPredictionErrorModel() != null && active.getPredictionErrorFraction() > 0.0) {
            return base
                    * active.getPredictionErrorModel()
                            .sampleFactor(
                                    active.getPredictionErrorFraction(),
                                    SimulationRandom.newJavaRandom("prediction.reshi.runtime"));
        }
        return base;
    }

    private CondorVM selectVm(Job job, List<CondorVM> free) {
        if (job.getTaskList().isEmpty()) {
            return free.get(0);
        }
        List<Integer> freeIds = new ArrayList<Integer>();
        for (CondorVM vm : free) {
            freeIds.add(vm.getId());
        }
        ReshiRankTable.Entry best =
                configuration().getRankTable().bestFor(job.getTaskList().get(0).getType(), freeIds);
        if (best == null) {
            return free.get(0);
        }
        for (CondorVM vm : free) {
            if (vm.getId() == best.getVmId()) {
                return vm;
            }
        }
        throw new IllegalStateException("Reshi rank table selected an unavailable VM");
    }
}
