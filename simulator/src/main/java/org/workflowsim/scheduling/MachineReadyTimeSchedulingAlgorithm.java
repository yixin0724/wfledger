package org.workflowsim.scheduling;

import java.util.ArrayList;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * Late-binding batch mapping with machine ready times, shared by {@code READY_BATCH_MINMIN}, {@code
 * READY_BATCH_MAXMIN} and {@code READY_BATCH_MCT}.
 */
public abstract class MachineReadyTimeSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    protected enum Selection {
        MIN_MIN,
        MAX_MIN,
        QUEUE_ORDER
    }

    protected final void planAndBind(Selection selection) {
        validateCloudletCompatibility();
        List<CondorVM> vms = sortedVmsById();
        int vmCount = vms.size();
        double[] ready = new double[vmCount];
        boolean allIdle = true;
        for (int v = 0; v < vmCount; v++) {
            ready[v] = readySeconds(vms.get(v));
            allIdle &= vms.get(v).getState() == WorkflowSimTags.VM_STATUS_IDLE;
        }
        int jobCount = getCloudletList().size();
        double[] readSeconds = new double[jobCount];
        for (int t = 0; t < jobCount; t++) {
            readSeconds[t] = inputReadSeconds((Cloudlet) getCloudletList().get(t));
        }
        boolean[] assigned = new boolean[jobCount];
        boolean[] planned = new boolean[vmCount];
        List<Cloudlet> bound = new ArrayList<Cloudlet>();
        for (int step = 0; step < jobCount; step++) {
            int bestJob = -1;
            int bestVm = -1;
            double bestKey = Double.NaN;
            for (int t = 0; t < jobCount; t++) {
                if (assigned[t]) {
                    continue;
                }
                Cloudlet cloudlet = (Cloudlet) getCloudletList().get(t);
                int vm = minimumCompletionVm(cloudlet, vms, ready, readSeconds[t]);
                double key = ready[vm] + envelopeSeconds(cloudlet, vms.get(vm), readSeconds[t]);
                if (bestJob < 0
                        || better(selection, key, cloudlet, bestKey, (Cloudlet)
                                getCloudletList().get(bestJob))) {
                    bestJob = t;
                    bestVm = vm;
                    bestKey = key;
                }
                if (selection == Selection.QUEUE_ORDER) {
                    break;
                }
            }
            assigned[bestJob] = true;
            ready[bestVm] = bestKey;
            CondorVM target = vms.get(bestVm);
            if (!planned[bestVm] && target.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                Cloudlet cloudlet = (Cloudlet) getCloudletList().get(bestJob);
                cloudlet.setVmId(target.getId());
                bound.add(cloudlet);
            }
            planned[bestVm] = true;
        }
        if (allIdle && jobCount > 0 && bound.isEmpty()) {
            throw new IllegalStateException("Ready-time late binding dispatched none of " + jobCount
                    + " ready Job(s) although every VM is idle: the workflow would deadlock");
        }
        for (Cloudlet cloudlet : bound) {
            CondorVM target = findVm(vms, cloudlet.getVmId());
            target.setState(WorkflowSimTags.VM_STATUS_BUSY);
            getScheduledList().add(cloudlet);
        }
    }

    private int minimumCompletionVm(Cloudlet cloudlet, List<CondorVM> vms, double[] ready, double readSeconds) {
        int best = -1;
        double bestCompletion = Double.NaN;
        for (int v = 0; v < vms.size(); v++) {
            CondorVM vm = vms.get(v);
            if (!isCompatible(cloudlet, vm)) {
                continue;
            }
            double completion = ready[v] + envelopeSeconds(cloudlet, vm, readSeconds);
            if (best < 0 || completion < bestCompletion) {
                best = v;
                bestCompletion = completion;
            }
        }
        return best;
    }

    private static boolean better(Selection selection, double key, Cloudlet job, double bestKey, Cloudlet bestJob) {
        int order = Double.compare(key, bestKey);
        if (selection == Selection.MAX_MIN) {
            order = -order;
        }
        return order < 0 || (order == 0 && job.getCloudletId() < bestJob.getCloudletId());
    }

    private static CondorVM findVm(List<CondorVM> vms, int id) {
        for (CondorVM vm : vms) {
            if (vm.getId() == id) {
                return vm;
            }
        }
        throw new IllegalStateException("Bound VM " + id + " is not a created VM");
    }
}
