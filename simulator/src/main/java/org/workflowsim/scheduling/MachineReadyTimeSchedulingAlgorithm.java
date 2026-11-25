package org.workflowsim.scheduling;

import java.util.ArrayList;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * Late-binding batch mapping with machine ready times, shared by {@code READY_BATCH_MINMIN},
 * {@code READY_BATCH_MAXMIN} and {@code READY_BATCH_MCT}.
 *
 * <p>FGCS-R6 (2026-11-25): root cause — the former rounds mapped Jobs only to idle VMs and ignored
 * machine ready time, so the labels did not denote the literature heuristics. Each round now
 * <ol>
 *   <li>reads {@code r_v} for every created VM sorted by ID (0 when idle, else the no-failure remaining
 *       envelope time of its running Job, see {@link MachineReadyTimeEstimator});</li>
 *   <li>plans every Job of the batch with completion time {@code CT(j,v) = r_v + ETC(j,v)} over its
 *       PE-compatible VMs, where {@code ETC} is the charged runtime envelope
 *       {@link #envelopeSeconds(Cloudlet, CondorVM, double)}; Min-Min (Max-Min) repeatedly assigns the
 *       Job whose minimum completion time is the smallest (largest), MCT assigns Jobs in queue order
 *       (Maheswaran et al. 1999 immediate mode); after each assignment {@code r_v := CT} (Braun et al.
 *       2001 update step);</li>
 *   <li>dispatches exactly the Jobs that are the first planned Job of an idle VM. Every other Job stays
 *       queued and is re-planned at the next round (late binding); every Job return requests a round.</li>
 * </ol>
 * Ties: the lower VM ID among equal completion times, then the lower Job ID among equal selection
 * keys (MCT keeps queue order). A Job may wait for a busy faster VM while a slower VM idles: the
 * heuristic is not work-conserving, as in the literature. When every VM is idle the first assignment
 * targets an idle VM, so a non-empty batch always dispatches (checked fail-fast).
 */
public abstract class MachineReadyTimeSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    /** Job-selection rule of one planning step. */
    protected enum Selection {
        MIN_MIN,
        MAX_MIN,
        QUEUE_ORDER
    }

    /**
     * Plans the batch and dispatches the first planned Job of every idle VM.
     *
     * @param selection job-selection rule
     */
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
