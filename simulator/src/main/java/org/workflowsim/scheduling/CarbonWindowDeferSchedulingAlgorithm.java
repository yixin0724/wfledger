package org.workflowsim.scheduling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;

/**
 * C1 (2026-11-02): carbon-aware deferral inside green-budget windows (an online version of
 * the CWM time shift).
 *
 * <p>This is not the "LPT + smallest VM id" demonstrator of C5-lite; it is an engine that
 * really <b>pushes jobs back to a cleaner time window</b> before executing them. There is
 * exactly one rule, deliberately kept auditable:</p>
 *
 * <ol>
 *   <li>Take the window w that contains the current instant ({@code carbonWindows} covers
 *       [0,T_sim), and the declaration phase has already validated ordering and
 *       non-overlap) and record its green power g_w.</li>
 *   <li>Look forward for the <b>first window w' with strictly higher green power</b>; if
 *       there is none, dispatch under the ordinary online policy.</li>
 *   <li>If the ready set is non-empty and every job in it has <b>not yet</b> been deferred,
 *       dispatch none of them in this round and set the wake-up instant to the start of
 *       w' -- all of them are dispatched together once the cleaner window opens.</li>
 *   <li>Each job is deferred at most once: this rules out unbounded postponement (a
 *       starvation bound) and makes the "deferral gain" attributable per job.</li>
 * </ol>
 *
 * <p><b>Honest boundary</b>: this engine reads only the green power declared by the
 * platform, not live fleet load (that belongs to the window-declaration domain of the
 * research protocol); it trades batch completion time for a lower overshoot, and whether
 * that is worthwhile is decided by the experimental criteria -- the engine itself claims no
 * superiority. Both the windows and the dispatch rule come from the run configuration, so
 * the same input is reproducible bit for bit.</p>
 *
 * <p>Contract: a deferred job stays in the scheduler ready queue, and the wake-up event
 * returned by {@link DeferralAwareSchedulingAlgorithm#getNextWakeSeconds()} re-triggers the
 * scheduling cycle; {@link org.workflowsim.WorkflowScheduler} schedules that event and fails
 * explicitly on the deadlock shape "deferred but no wake-up instant".</p>
 */
public final class CarbonWindowDeferSchedulingAlgorithm extends BaseSchedulingAlgorithm
        implements DeferralAwareSchedulingAlgorithm {

    /** Ids of jobs already deferred: each job is deferred at most once (starvation bound). */
    private final Set<Integer> deferredOnce = new HashSet<Integer>();

    private double nextWakeSeconds = Double.POSITIVE_INFINITY;
    private int deferredJobCount;

    @Override
    public void run() {
        validateCloudletCompatibility();
        nextWakeSeconds = Double.POSITIVE_INFINITY;
        deferredJobCount = 0;

        List<Cloudlet> ready = new ArrayList<Cloudlet>();
        for (Object item : getCloudletList()) {
            ready.add((Cloudlet) item);
        }
        Collections.sort(ready, new Comparator<Cloudlet>() {
            @Override
            public int compare(Cloudlet left, Cloudlet right) {
                return Integer.compare(left.getCloudletId(), right.getCloudletId());
            }
        });

        double now = CloudSim.clock();
        List<PlatformProfile.CarbonWindow> windows = Parameters.getCarbonWindows();
        if (!ready.isEmpty() && windows != null && !windows.isEmpty()) {
            PlatformProfile.CarbonWindow clean = nextCleanerWindow(windows, now);
            if (clean != null && canDefer(ready)) {
                nextWakeSeconds = clean.getStartSeconds();
                deferredJobCount = ready.size();
                for (Cloudlet cloudlet : ready) {
                    deferredOnce.add(Integer.valueOf(cloudlet.getCloudletId()));
                }
                return;
            }
        }
        dispatch(ready);
    }

    /**
     * Ordinary online dispatch: FCFS onto the smallest idle compatible VM (same order as
     * FCFS, so the two can be compared).
     */
    private void dispatch(List<Cloudlet> ready) {
        List<CondorVM> vms = sortedVmsById();
        for (Cloudlet cloudlet : ready) {
            CondorVM target = null;
            for (CondorVM vm : vms) {
                if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE && isCompatible(cloudlet, vm)) {
                    target = vm;
                    break;
                }
            }
            if (target == null) {
                continue;
            }
            target.setState(WorkflowSimTags.VM_STATUS_BUSY);
            cloudlet.setVmId(target.getId());
            getScheduledList().add(cloudlet);
        }
    }

    /**
     * Defer the whole ready set only when every ready job has not been deferred yet --
     * partial deferral would cause reordering and starvation.
     */
    private boolean canDefer(List<Cloudlet> ready) {
        for (Cloudlet cloudlet : ready) {
            if (deferredOnce.contains(Integer.valueOf(cloudlet.getCloudletId()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Find the first window after the current instant whose green power is strictly higher.
     *
     * @return that window; {@code null} when the current instant is already at the peak, no
     *     cleaner window follows it, or the candidate window starts no later than the current
     *     instant (so the clock cannot advance)
     */
    private static PlatformProfile.CarbonWindow nextCleanerWindow(
            List<PlatformProfile.CarbonWindow> windows, double now) {
        double currentGreen = greenWattsAt(windows, now);
        PlatformProfile.CarbonWindow best = null;
        for (PlatformProfile.CarbonWindow window : windows) {
            if (window.getStartSeconds() <= now) {
                continue;
            }
            if (window.getGreenWatts() > currentGreen) {
                if (best == null || window.getStartSeconds() < best.getStartSeconds()) {
                    best = window;
                }
            }
        }
        return best;
    }

    /**
     * Green power of the window that covers {@code now}; when no window covers it (historical
     * or boundary shape) the value is treated as infinite, which triggers no deferral.
     */
    private static double greenWattsAt(List<PlatformProfile.CarbonWindow> windows, double now) {
        for (PlatformProfile.CarbonWindow window : windows) {
            if (now >= window.getStartSeconds() && now < window.getEndSeconds()) {
                return window.getGreenWatts();
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public double getNextWakeSeconds() {
        return nextWakeSeconds;
    }

    @Override
    public int getDeferredJobCount() {
        return deferredJobCount;
    }

    /**
     * Number of jobs deferred so far, read by the evidence side (accumulated across cycles,
     * for observation only).
     */
    public int getTotalDeferredJobs() {
        return deferredOnce.size();
    }
}
