package org.workflowsim.rl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

public final class RlObservation {

    public static final class JobView {
        private final int jobId;
        private final long totalLength;
        private final int pes;

        JobView(int jobId, long totalLength, int pes) {
            this.jobId = jobId;
            this.totalLength = totalLength;
            this.pes = pes;
        }

        public int getJobId() {
            return jobId;
        }

        public long getTotalLength() {
            return totalLength;
        }

        public int getPes() {
            return pes;
        }
    }

    public static final class VmView {
        private final int vmId;
        private final double mips;
        private final int pes;
        private final boolean idle;

        VmView(int vmId, double mips, int pes, boolean idle) {
            this.vmId = vmId;
            this.mips = mips;
            this.pes = pes;
            this.idle = idle;
        }

        public int getVmId() {
            return vmId;
        }

        public double getMips() {
            return mips;
        }

        public int getPes() {
            return pes;
        }

        public boolean isIdle() {
            return idle;
        }
    }

    private final double simulationTime;
    private final List<JobView> readyJobs;
    private final List<VmView> vms;

    private RlObservation(double simulationTime, List<JobView> readyJobs, List<VmView> vms) {
        this.simulationTime = simulationTime;
        this.readyJobs = Collections.unmodifiableList(new ArrayList<JobView>(readyJobs));
        this.vms = Collections.unmodifiableList(new ArrayList<VmView>(vms));
    }

    public static RlObservation of(List<? extends Cloudlet> readyJobs, List<CondorVM> vms) {
        List<JobView> jobViews = new ArrayList<JobView>();
        for (Cloudlet cloudlet : readyJobs) {
            jobViews.add(
                    new JobView(cloudlet.getCloudletId(), cloudlet.getCloudletLength(), cloudlet.getNumberOfPes()));
        }
        List<CondorVM> sorted = new ArrayList<CondorVM>(vms);
        Collections.sort(sorted, (first, second) -> Integer.compare(first.getId(), second.getId()));
        List<VmView> vmViews = new ArrayList<VmView>();
        for (CondorVM vm : sorted) {
            vmViews.add(new VmView(
                    vm.getId(), vm.getMips(), vm.getNumberOfPes(), vm.getState() == WorkflowSimTags.VM_STATUS_IDLE));
        }
        return new RlObservation(CloudSim.clock(), jobViews, vmViews);
    }

    public double getSimulationTime() {
        return simulationTime;
    }

    public List<JobView> getReadyJobs() {
        return readyJobs;
    }

    public List<VmView> getVms() {
        return vms;
    }
}
