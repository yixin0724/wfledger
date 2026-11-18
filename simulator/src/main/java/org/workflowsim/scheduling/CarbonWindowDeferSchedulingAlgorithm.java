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

public final class CarbonWindowDeferSchedulingAlgorithm extends BaseSchedulingAlgorithm
        implements DeferralAwareSchedulingAlgorithm {

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

    private boolean canDefer(List<Cloudlet> ready) {
        for (Cloudlet cloudlet : ready) {
            if (deferredOnce.contains(Integer.valueOf(cloudlet.getCloudletId()))) {
                return false;
            }
        }
        return true;
    }

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

    public int getTotalDeferredJobs() {
        return deferredOnce.size();
    }
}
