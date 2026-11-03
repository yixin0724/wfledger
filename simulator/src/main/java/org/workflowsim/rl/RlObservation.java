package org.workflowsim.rl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * Decision-instant state snapshot (observation) of the R4 RL track.
 *
 * <p>State = ready Job queue features at the current simulation clock + VM load view, consistent
 * with the environment contract of roadmap R4. The snapshot is taken once from runtime state at the
 * scheduling decision instant and is immutable after construction; ready Jobs keep the scheduler
 * queue arrival order and VMs are ordered by ascending ID -- the order itself is deterministic, so
 * the index correspondence between policy output and state input is reproducible bit for bit.</p>
 *
 * <p>The VM load view is deliberately minimal: under the space-sharing model each VM runs at most
 * one Job at a time, so "load" is fully described by the busy/idle state plus MIPS and PE count; no
 * inference quantity without regression verification is introduced. The Job view exposes the
 * aggregate work length (the same semantics as the online scheduler's ECT:
 * {@code cloudletLength / mips}) and the PE demand, so a policy can estimate the completion
 * instant.</p>
 *
 * <p><b>Non-Markovian by design (AUD-F11)</b>: the snapshot deliberately omits the DAG structure,
 * network/transfer state, and any history of past actions or observations, so a policy cannot in
 * principle distinguish states that differ only in the omitted context. This demo-interface
 * boundary keeps the closed loop minimal and auditable; it is not a claim that the observation
 * is a sufficient statistic of the environment state.</p>
 */
public final class RlObservation {

    /** State view of a ready Job. */
    public static final class JobView {
        private final int jobId;
        private final long totalLength;
        private final int pes;

        JobView(int jobId, long totalLength, int pes) {
            this.jobId = jobId;
            this.totalLength = totalLength;
            this.pes = pes;
        }

        /** @return Job ID (CloudSim cloudlet ID) */
        public int getJobId() {
            return jobId;
        }

        /** @return Job aggregate work length (MI); ECT estimation uses {@code totalLength / vmMips} */
        public long getTotalLength() {
            return totalLength;
        }

        /** @return number of PEs the Job requires */
        public int getPes() {
            return pes;
        }
    }

    /** State view of a VM. */
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

        /** @return VM ID */
        public int getVmId() {
            return vmId;
        }

        /** @return VM per-PE performance (MIPS) */
        public double getMips() {
            return mips;
        }

        /** @return number of PEs of the VM */
        public int getPes() {
            return pes;
        }

        /** @return whether the VM is currently idle (a busy VM accepts no new Job under space sharing) */
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

    /**
     * Extracts an immutable snapshot from runtime state at the decision instant.
     *
     * @param readyJobs current ready Job queue (arrival order preserved)
     * @param vms all current VMs (sorted internally by ascending ID)
     * @return immutable observation snapshot
     */
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

    /** @return simulation clock at the decision instant (seconds) */
    public double getSimulationTime() {
        return simulationTime;
    }

    /** @return ready Job view list (scheduler arrival order; the index is the policy action index) */
    public List<JobView> getReadyJobs() {
        return readyJobs;
    }

    /** @return VM view list (ascending VM ID; the index is the policy action value domain) */
    public List<VmView> getVms() {
        return vms;
    }
}
