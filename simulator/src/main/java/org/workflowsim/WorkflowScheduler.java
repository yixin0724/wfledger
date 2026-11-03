/**
 * Copyright 2012-2013 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.workflowsim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.DatacenterBroker;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.lists.VmList;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.experiment.SimulationEventRecorder;
import org.workflowsim.experiment.SimulationEventType;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;
import org.workflowsim.scheduling.CarbonWindowDeferSchedulingAlgorithm;
import org.workflowsim.scheduling.DataAwareSchedulingAlgorithm;
import org.workflowsim.scheduling.DeferralAwareSchedulingAlgorithm;
import org.workflowsim.scheduling.FCFSSchedulingAlgorithm;
import org.workflowsim.scheduling.FastestVmSchedulingAlgorithm;
import org.workflowsim.scheduling.FirstFitIdleSchedulingAlgorithm;
import org.workflowsim.scheduling.FrequencyTierSelector;
import org.workflowsim.scheduling.LjfFastestIdleSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchMCTSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchMaxMinSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchMinMinSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchRoundRobinSchedulingAlgorithm;
import org.workflowsim.scheduling.RlPolicySchedulingAlgorithm;
import org.workflowsim.scheduling.SptFastestIdleSchedulingAlgorithm;
import org.workflowsim.scheduling.StaticSchedulePlan;
import org.workflowsim.scheduling.StaticSchedulingAlgorithm;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;

/**
 * CloudSim broker that performs runtime scheduling on behalf of one workflow user.
 *
 * <p>This entity manages VM creation and destruction, ready Job dispatch, and completion-event
 * reclamation, and instantiates one {@link BaseSchedulingAlgorithm} according to the
 * configuration. The algorithm object is reused for the lifetime of the scheduler so that
 * stateful policies such as ready-batch round robin keep their cross-event semantics.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class WorkflowScheduler extends DatacenterBroker {

    /** CloudSim entity id of the upstream workflow engine. */
    private int workflowEngineId;

    /** Policy instance retained for the lifetime of this scheduling entity. */
    private BaseSchedulingAlgorithm activeSchedulingPolicy;

    /** Configuration enum value used when the current policy instance was created. */
    private SchedulingAlgorithm activeSchedulingPolicyType;

    private SimulationEventRecorder eventRecorder = SimulationEventRecorder.disabled();
    private StaticSchedulePlan staticSchedulePlan = StaticSchedulePlan.empty();
    private boolean strictDataflowBindings;
    /** Require an intact input-prepared compute binding, without changing stage-in fallback. */
    public void enableDataflowBindingGuard() {
        strictDataflowBindings = true;
    }
    /**
     * VM-to-Host mapping frozen when VM creation succeeds.
     *
     * <p>CloudSim releases the Host reference of a VM during shutdown, so this is the only
     * reliable lifecycle instant at which execution evidence can be recorded.</p>
     */
    private final Map<Integer, Integer> createdVmHostAssignments = new LinkedHashMap<Integer, Integer>();

    /**
     * Creates the workflow runtime scheduling entity.
     *
     * @param name CloudSim entity name
     * @throws Exception when the CloudSim broker cannot be created
     */
    public WorkflowScheduler(String name) throws Exception {
        super(name);
    }

    /**
     * Binds a datacenter from which this scheduler can request VMs.
     *
     * @param datacenterId datacenter entity id
     */
    public void bindSchedulerDatacenter(int datacenterId) {
        if (datacenterId <= 0) {
            Log.printLine("Error in data center id");
            return;
        }
        this.datacenterIdsList.add(datacenterId);
    }

    /**
     * Sets the upstream workflow engine id.
     *
     * @param workflowEngineId workflow engine entity id
     */
    public void setWorkflowEngineId(int workflowEngineId) {
        this.workflowEngineId = workflowEngineId;
    }

    /**
     * Installs the event recorder private to this run before the simulation starts.
     *
     * @param value event recorder of this run
     */
    public void setEventRecorder(SimulationEventRecorder value) {
        if (value == null) {
            throw new IllegalArgumentException("Event recorder cannot be null");
        }
        this.eventRecorder = value;
    }

    /**
     * Returns the VM-to-Host mapping observed when VM creation succeeded.
     *
     * <p>CloudSim clears the VM Host reference during shutdown, so this copy preserves the only
     * lifecycle observation usable as execution evidence.</p>
     *
     * @return unmodifiable mapping from VM id to Host id
     */
    public Map<Integer, Integer> getCreatedVmHostAssignments() {
        return Collections.unmodifiableMap(new LinkedHashMap<Integer, Integer>(createdVmHostAssignments));
    }

    /**
     * Installs the complete static plan order of this run after clustering.
     *
     * @param value complete static plan
     */
    public void setStaticSchedulePlan(StaticSchedulePlan value) {
        if (value == null) {
            throw new IllegalArgumentException("Static schedule plan cannot be null");
        }
        this.staticSchedulePlan = value;
        if (activeSchedulingPolicy instanceof StaticSchedulingAlgorithm) {
            ((StaticSchedulingAlgorithm) activeSchedulingPolicy).setStaticSchedulePlan(value);
        }
    }

    /**
     * Dispatches runtime scheduling lifecycle events.
     *
     * @param ev received CloudSim event
     */
    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
                // Request resource characteristics from each datacenter.
            case CloudSimTags.RESOURCE_CHARACTERISTICS_REQUEST:
                processResourceCharacteristicsRequest(ev);
                break;
                // Receive datacenter resource characteristics.
            case CloudSimTags.RESOURCE_CHARACTERISTICS:
                processResourceCharacteristics(ev);
                break;
                // Receive the VM creation acknowledgement.
            case CloudSimTags.VM_CREATE_ACK:
                processVmCreate(ev);
                break;
                // Receive a Job completion, or a return event triggered by a completion check.
            case WorkflowSimTags.CLOUDLET_CHECK:
                processCloudletReturn(ev);
                break;
            case CloudSimTags.CLOUDLET_RETURN:
                processCloudletReturn(ev);
                break;
            case CloudSimTags.END_OF_SIMULATION:
                shutdownEntity();
                break;
            case CloudSimTags.CLOUDLET_SUBMIT:
                processCloudletSubmit(ev);
                break;
            case WorkflowSimTags.CLOUDLET_UPDATE:
                processCloudletUpdate(ev);
                break;
            default:
                processOtherEvent(ev);
                break;
        }
    }

    /**
     * Creates the runtime scheduling policy from the configuration.
     *
     * <p>The mapping must stay consistent with the public experiment semantics of
     * {@link Parameters.SchedulingAlgorithm}. The four deprecated enums bind their
     * historical policies only after the explicit legacy declaration
     * ({@link Parameters#enableLegacySchedulingAliases()}); without that declaration the
     * binding fails fast and names the actual policy, because SimulationRunner already
     * rejects the deprecated labels for research evidence (AUD-F10).</p>
     *
     * <p>Every enum value has an explicit case; {@code INVALID} keeps its historical static
     * binding shell explicitly, and an unmapped value throws instead of silently falling
     * back to the static binding, so a missed switch addition cannot masquerade as a
     * plan-execution run (AUD-F21).</p>
     *
     * @param name scheduling algorithm enum
     * @return the corresponding scheduling policy
     * @throws IllegalStateException when {@code name} has no explicit case in this switch
     */
    private BaseSchedulingAlgorithm createSchedulingPolicy(SchedulingAlgorithm name) {
        BaseSchedulingAlgorithm algorithm;

        // A new policy requires maintaining the reportable algorithm enum in Parameters at the same time.
        switch (name) {
                // FCFS is the default online scheduling policy.
            case FCFS:
                algorithm = new FCFSSchedulingAlgorithm();
                break;
            case READY_BATCH_MINMIN:
                algorithm = new ReadyBatchMinMinSchedulingAlgorithm();
                break;
            case READY_BATCH_MAXMIN:
                algorithm = new ReadyBatchMaxMinSchedulingAlgorithm();
                break;
            case READY_BATCH_MCT:
                algorithm = new ReadyBatchMCTSchedulingAlgorithm();
                break;
            case READY_BATCH_ROUNDROBIN:
                algorithm = new ReadyBatchRoundRobinSchedulingAlgorithm();
                break;
            case RL_POLICY:
                // R4 RL track: the policy is delivered through RlPolicyRegistry; running outside an
                // episode fails explicitly.
                algorithm = new RlPolicySchedulingAlgorithm();
                break;
            case MINMIN:
                // Preserves the legacy behavior: SPT-fastest-idle variant.
                requireLegacyAliasDeclaration(name, "SPT-fastest-idle");
                algorithm = new SptFastestIdleSchedulingAlgorithm();
                break;
            case MAXMIN:
                // Preserves the legacy behavior: LJF-fastest-idle variant.
                requireLegacyAliasDeclaration(name, "LJF-fastest-idle");
                algorithm = new LjfFastestIdleSchedulingAlgorithm();
                break;
            case MCT:
                // Preserves the legacy behavior: greedy fastest VM.
                requireLegacyAliasDeclaration(name, "fastest-VM");
                algorithm = new FastestVmSchedulingAlgorithm();
                break;
            case ROUNDROBIN:
                // Preserves the legacy behavior: First-Fit-Idle.
                requireLegacyAliasDeclaration(name, "First-Fit-Idle");
                algorithm = new FirstFitIdleSchedulingAlgorithm();
                break;
            case DATA:
                algorithm = new DataAwareSchedulingAlgorithm();
                break;
            case CARBON_WINDOW_DEFER:
                algorithm = new CarbonWindowDeferSchedulingAlgorithm();
                break;
            case STATIC:
                algorithm = new StaticSchedulingAlgorithm();
                break;
            case INVALID:
                // AUD-F21: INVALID is the legacy Parameters default (Parameters.reset()) and
                // the registered-provider sentinel; without a provider there is no scheduling
                // decision to make, so the static binding shell keeps the historical role of
                // executing an already produced binding. Runner-configured runs never reach
                // this case because the runner rejects INVALID first.
                algorithm = new StaticSchedulingAlgorithm();
                break;
            default:
                // AUD-F21: an unmapped enum value fails loudly instead of silently falling
                // back to the static binding, so a missed switch addition cannot masquerade
                // as a plan-execution run.
                throw new IllegalStateException("SchedulingAlgorithm." + name + " has no policy "
                        + "binding in WorkflowScheduler; add an explicit case when the enum grows "
                        + "(AUD-F21).");
        }
        return algorithm;
    }

    /**
     * AUD-F10: refuses to silently bind a deprecated label to its historical policy.
     *
     * <p>The four deprecated labels never name their actual policies, so the historical
     * binding is refused without the explicit legacy declaration; the message names the
     * actual policy, the maintained replacement family, and the declaration path.</p>
     *
     * @param name deprecated scheduling label that was configured
     * @param actualPolicy the historical policy the label actually binds
     */
    private static void requireLegacyAliasDeclaration(SchedulingAlgorithm name, String actualPolicy) {
        if (!Parameters.isLegacySchedulingAliasesEnabled()) {
            throw new IllegalStateException("SchedulingAlgorithm." + name + " is a deprecated "
                    + "compatibility label: WorkflowScheduler binds the " + actualPolicy
                    + " policy, not classic " + name + ", and SimulationRunner already rejects "
                    + "it. Use the maintained READY_BATCH_* labels for research evidence, or "
                    + "call Parameters.enableLegacySchedulingAliases() before Parameters.init "
                    + "when running the historical examples (AUD-F10).");
        }
    }

    /**
     * Returns the scheduling policy retained for this simulation.
     *
     * <p>Stateful policies such as ready-batch round robin must survive multiple Cloudlet
     * update events; when the configured algorithm value changes, a new policy instance is
     * created deliberately.</p>
     *
     * @return current active scheduling policy
     */
    protected BaseSchedulingAlgorithm getSchedulingPolicy() {
        // BAT-3 (2026-10-26): a registered algorithm track takes precedence over enum dispatch;
        // the provider policy survives across events, and Parameters.reset() invalidates it at
        // the session boundary. The existing enum path is unchanged.
        if (Parameters.getRegisteredSchedulingAlgorithmProvider() != null) {
            if (activeSchedulingPolicy == null || activeSchedulingPolicyType != SchedulingAlgorithm.INVALID) {
                activeSchedulingPolicy =
                        Parameters.getRegisteredSchedulingAlgorithmProvider().create();
                activeSchedulingPolicyType = SchedulingAlgorithm.INVALID;
            }
            return activeSchedulingPolicy;
        }
        SchedulingAlgorithm configuredType = Parameters.getSchedulingAlgorithm();
        if (activeSchedulingPolicy == null || activeSchedulingPolicyType != configuredType) {
            activeSchedulingPolicy = createSchedulingPolicy(configuredType);
            activeSchedulingPolicyType = configuredType;
        }
        return activeSchedulingPolicy;
    }

    /**
     * Processes the acknowledgement of a VM creation request.
     *
     * <p>If none of the candidate datacenters can create all requested VMs, fail explicitly
     * instead of continuing on a platform with degraded resources and producing experimental
     * results that are hard to compare.</p>
     *
     * @param ev VM creation acknowledgement event
     */
    @Override
    protected void processVmCreate(SimEvent ev) {
        int[] data = (int[]) ev.getData();
        int datacenterId = data[0];
        int vmId = data[1];
        int result = data[2];

        if (result == CloudSimTags.TRUE) {
            getVmsToDatacentersMap().put(vmId, datacenterId);
            // Historical CloudSim paths may return an unknown VM id; a null must not be added
            // to the created list.
            if (VmList.getById(getVmList(), vmId) != null) {
                getVmsCreatedList().add(VmList.getById(getVmList(), vmId));
                CondorVM createdVm = (CondorVM) VmList.getById(getVmsCreatedList(), vmId);
                if (createdVm.getHost() == null) {
                    throw new IllegalStateException(
                            getName() + " received successful creation for VM #" + vmId + " without a Host assignment");
                }
                if (createdVmHostAssignments.put(
                                createdVm.getId(), createdVm.getHost().getId())
                        != null) {
                    throw new IllegalStateException(
                            getName() + " received duplicate successful creation for VM #" + vmId);
                }
                Log.printLine(CloudSim.clock() + ": " + getName() + ": VM #" + vmId
                        + " has been created in Datacenter #" + datacenterId + ", Host #"
                        + createdVm.getHost().getId());
            }
        } else {
            // A failed creation ACK is part of a legitimate flow: the legacy multi-cluster
            // example submits the same VM batch to several datacenters for trial placement, and
            // the losing side replies ACK FALSE (WorkflowSimMultipleClusterExample1). Under the
            // standard SimulationRunner path the platform layout has been preflight-validated
            // (PLAT-2/PLAT-5), so the VMs are always placeable and this branch does not trigger;
            // if it does trigger, the condition count is never satisfied and the watchdog reports
            // a stall (R8 audit P1-6 kept the logging behavior after evaluation, because
            // fail-fast would break the multi-cluster trial-placement semantics).
            Log.printLine(CloudSim.clock() + ": " + getName() + ": Creation of VM #" + vmId + " failed in Datacenter #"
                    + datacenterId);
        }

        incrementVmsAcks();

        // Pending Jobs may be submitted only after all requested VMs are created.
        if (getVmsCreatedList().size() == getVmList().size() - getVmsDestroyed()) {
            submitCloudlets();
            // Flush ready Jobs that arrived early: if the engine released Jobs before VM
            // creation completed (for example the idempotent release path of JOB_SUBMIT), those
            // Jobs are already waiting in the ready list; issue one scheduling cycle here so that
            // they do not stall silently when no new submit event drives them. Under the standard
            // order (Jobs arrive after the VMs) the ready list is empty and this flush is an idle
            // cycle that dispatches nothing.
            if (!getCloudletList().isEmpty()) {
                sendNow(this.getId(), WorkflowSimTags.CLOUDLET_UPDATE);
            }
        } else {
            // When all acks have arrived but some VM creation still failed, try the next
            // datacenter that has not been requested yet.
            if (getVmsRequested() == getVmsAcks()) {
                // Find the next datacenter in which VM creation has not been attempted yet.
                for (int nextDatacenterId : getDatacenterIdsList()) {
                    if (!getDatacenterRequestedIdsList().contains(nextDatacenterId)) {
                        createVmsInDatacenter(nextDatacenterId);
                        return;
                    }
                }

                // Every datacenter has been tried; refuse to continue with a degraded resource
                // scale.
                throw new IllegalStateException(getName() + " could create only "
                        + getVmsCreatedList().size() + " of " + getVmList().size()
                        + " requested VMs; refusing to run on a degraded platform");
            }
        }
    }

    /**
     * Runs one scheduling decision cycle over the current ready Jobs and created VMs.
     *
     * <p>The method records the decision latency, the ready/idle counts, and the dispatch
     * decision of every Job. The timing reflects local Java scheduling overhead, not simulated
     * time, and must not be compared directly across different hardware.</p>
     *
     * @param ev Cloudlet update event
     */
    protected void processCloudletUpdate(SimEvent ev) {

        // A scheduling cycle cannot run while the VMs are not all created: the scheduling
        // algorithm cannot make a valid decision on an empty (or incomplete) VM list. The ready
        // Jobs stay in the ready list and are flushed together by the scheduling cycle triggered
        // from processVmCreate once VM creation completes (see the CLOUDLET_UPDATE flush logic
        // in that method). This guard makes the Job arrival order independent of the VM creation
        // instant (for example the scenario where the engine idempotently releases ready Jobs
        // during JOB_SUBMIT).
        if (getVmsCreatedList().size() < getVmList().size() - getVmsDestroyed()) {
            return;
        }
        BaseSchedulingAlgorithm scheduler = getSchedulingPolicy();
        if (scheduler instanceof StaticSchedulingAlgorithm) {
            ((StaticSchedulingAlgorithm) scheduler).setStaticSchedulePlan(staticSchedulePlan);
            if (strictDataflowBindings) {
                ((StaticSchedulingAlgorithm) scheduler).requireDataflowBindings();
            }
        }
        scheduler.getScheduledList().clear();
        scheduler.setCloudletList(getCloudletList());
        scheduler.setVmList(getVmsCreatedList());
        int readyJobs = getCloudletList().size();
        int idleVms = 0;
        for (Object item : getVmsCreatedList()) {
            if (((CondorVM) item).getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                idleVms++;
            }
        }
        long decisionStart = System.nanoTime();
        try {
            scheduler.run();
        } catch (SimulationConfigurationException exception) {
            // R8 audit fix (P1-7): configuration exceptions (such as RL_POLICY with no
            // registered policy) must not be wrapped into the execution semantics of a
            // "scheduling algorithm failed" error.
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Scheduling algorithm " + Parameters.getSchedulingAlgorithm() + " failed", exception);
        }
        long decisionElapsed = System.nanoTime() - decisionStart;

        List<Cloudlet> scheduledList = scheduler.getScheduledList();
        recordSchedulingCycle(readyJobs, idleVms, decisionElapsed, scheduledList);
        dispatchScheduledJobs(scheduledList);
        getCloudletList().removeAll(scheduledList);
        getCloudletSubmittedList().addAll(scheduledList);
        cloudletsSubmitted += scheduledList.size();
        armDeferralWakeUp(scheduler, scheduledList);
    }

    /**
     * C1 (2026-11-02): wake-up/deadlock contract of deferral policies.
     *
     * <p>The carbon-aware deferral engine keeps the ready queue unchanged when it dispatches
     * nothing in the current round and supplies a future wake-up instant; the scheduler then
     * queues a {@code CLOUDLET_UPDATE} event to advance the simulation clock to that instant and
     * schedules again in the next cycle. If the policy dispatches nothing and also cannot supply
     * a future instant, the ready Jobs never see the next cycle -- that is a deadlock and must
     * fail explicitly (rule: failure must be explicit, silent suspension is forbidden).</p>
     */
    private void armDeferralWakeUp(BaseSchedulingAlgorithm policy, List<Cloudlet> scheduledList) {
        if (!(policy instanceof DeferralAwareSchedulingAlgorithm)) {
            return;
        }
        if (!scheduledList.isEmpty() || getCloudletList().isEmpty()) {
            // Something was dispatched, or nothing is left to dispatch: no wake-up needed.
            return;
        }
        DeferralAwareSchedulingAlgorithm deferral = (DeferralAwareSchedulingAlgorithm) policy;
        double wakeSeconds = deferral.getNextWakeSeconds();
        double now = org.cloudbus.cloudsim.core.CloudSim.clock();
        if (!Double.isFinite(wakeSeconds) || wakeSeconds <= now) {
            throw new IllegalStateException("Deferral-aware scheduling policy "
                    + policy.getClass().getSimpleName() + " left "
                    + getCloudletList().size()
                    + " ready Job(s) unscheduled without a future wake time (wake=" + wakeSeconds
                    + ", now=" + now + "): the workflow would deadlock");
        }
        schedule(this.getId(), wakeSeconds - now, WorkflowSimTags.CLOUDLET_UPDATE);
    }

    /** Scheduling-cycle event stage of processCloudletUpdate (event key order unchanged). */
    private void recordSchedulingCycle(int readyJobs, int idleVms, long decisionElapsed, List<Cloudlet> scheduledList) {
        eventRecorder.record(
                SimulationEventType.SCHEDULING_CYCLE,
                CloudSim.clock(),
                null,
                SimulationEventRecorder.attributes(
                        "schedulingAlgorithm",
                        Parameters.getSchedulingAlgorithm().name(),
                        "readyJobCount",
                        readyJobs,
                        "idleVmCount",
                        idleVms,
                        "scheduledJobCount",
                        scheduledList.size(),
                        "decisionElapsedNanos",
                        decisionElapsed));
    }

    /**
     * Dispatch stage of processCloudletUpdate (queue delay, event recording, and CloudSim event
     * order, transcribed verbatim).
     */
    private void dispatchScheduledJobs(List<Cloudlet> scheduledList) {
        for (Cloudlet cloudlet : scheduledList) {
            Job job = (Job) cloudlet;
            int vmId = cloudlet.getVmId();
            double delay = 0.0;
            if (Parameters.getOverheadParams().getQueueDelay() != null) {
                delay = Parameters.getOverheadParams().getQueueDelay(cloudlet);
            }
            eventRecorder.record(
                    SimulationEventType.SCHEDULING_DECISION,
                    CloudSim.clock(),
                    job,
                    SimulationEventRecorder.attributes(
                            "schedulingAlgorithm",
                            Parameters.getSchedulingAlgorithm().name(),
                            "queueDelaySeconds",
                            delay));
            // BAT-D2 (2026-10-29): the scheduling algorithm has fixed the VM assignment -> this
            // centralized dispatch point makes the frequency-tier decision uniformly (every setVmId
            // on the online/STATIC/registered algorithm tracks funnels into this method). Placed
            // after event recording: the events v1 schema carries no tier field (F-006 domain
            // adjudication), and the tier evidence chain goes through JobOutcome.tierMips.
            assignFrequencyTier(job);
            schedule(getVmsToDatacentersMap().get(vmId), delay, CloudSimTags.CLOUDLET_SUBMIT, cloudlet);
            eventRecorder.record(
                    SimulationEventType.JOB_DISPATCHED,
                    CloudSim.clock(),
                    job,
                    SimulationEventRecorder.attributes("queueDelaySeconds", delay));
        }
    }

    /**
     * BAT-D2 (2026-10-29): fixes the single frequency tier of a compute Job that has just been
     * assigned a VM and scales the Job length accordingly.
     *
     * <p>Semantic contract (docs/advanced/DVFS_DIMENSION_DESIGN.md sections 1.4/2.2):
     * assignment at admission -- each compute Job receives exactly one frequency tier at the
     * moment it is dispatched to a VM, and the tier is fixed for the full Job duration with no
     * mid-Job frequency switching, which preserves the auditable one-rectangle-per-Job
     * structure. The simulation-level semantics of "a lower tier runs slower" is expressed by
     * scaling the Job MI: execution duration = work / tier speed, which on a machine executing
     * at the declared MIPS of CloudSim is equivalent to multiplying the length by
     * {@code vm.mips / tier.mips} (the datacenter rebuilds the Job length before submission
     * through {@code projectComputeExecutionLength}, which takes effectiveExecutionLengthMi as
     * the source of truth, so the effective value and the Cloudlet length are both written
     * here). The transfer MI injected by stage-in happens afterwards and runs at the VM
     * declared speed (network transfer does not scale with the CPU frequency tier).</p>
     *
     * <p>Non-compute Jobs (stage-in platform preparation) and VMs without frequency tiers (v1
     * platforms or the tierless VMs of a mixed platform) receive no tier: tierMips stays null
     * and the length is unchanged -- v1 runs stay bit-identical.</p>
     *
     * @param job compute Job that the scheduling algorithm has just assigned to a VM
     */
    private void assignFrequencyTier(Job job) {
        if (job.getClassType() != Parameters.ClassType.COMPUTE.value) {
            // Non-compute Jobs (stage-in/stage-out/cleanup platform bookkeeping) occupy no
            // compute frequency tier.
            return;
        }
        CondorVM vm = findCreatedVm(job.getVmId());
        if (!vm.hasFrequencyTiers()) {
            return;
        }
        List<PlatformProfile.FrequencyTier> tiers = vm.getFrequencyTiers();
        PlatformProfile.FrequencyTier selected = selectFrequencyTier(job, tiers);
        long scaledWork = scaledTierWorkMi(job, vm, selected);
        // Evidence chain: the Job freezes this value into JobOutcome.tierMips when the report
        // is captured.
        job.setTierMips(selected.getMips());
        job.setEffectiveExecutionLengthMi(scaledWork);
        job.setCloudletLength(scaledWork);
    }

    /**
     * BAT-D2: selects the single tier of the VM frequency-tier table under the session
     * frequency policy (table order = descending, first tier = default tier).
     *
     * <p>BAT-D6 (2026-10-29, docs/advanced/DVFS_DIMENSION_DESIGN.md section 2.2 registry
     * surface): when the active scheduling policy instance implements
     * {@link FrequencyTierSelector}, the per-Job tier choice belongs to the algorithm -- the
     * consumption point of the first-class scheduling-time API and the only channel through
     * which a registered DVFS-aware algorithm track (such as the EDP_GREEDY demonstrator)
     * expresses a heterogeneous tier policy; the session-level policy (BASE path) recedes to
     * the default cross-cutting behavior for algorithms without a selector. The return values
     * of both paths go to
     * {@link #validateDeclaredTier(Job, PlatformProfile.FrequencyTier, List)} for declared-tier
     * membership validation (tiers are unique by MIPS); an out-of-table or missing tier is
     * rejected explicitly as a broken evidence chain (no silent fallback).</p>
     */
    private PlatformProfile.FrequencyTier selectFrequencyTier(Job job, List<PlatformProfile.FrequencyTier> tiers) {
        BaseSchedulingAlgorithm policy = getSchedulingPolicy();
        if (policy instanceof FrequencyTierSelector) {
            return validateDeclaredTier(job, ((FrequencyTierSelector) policy).selectFrequencyTier(job, tiers), tiers);
        }
        SimulationConfig.FrequencyPolicy policyValue = Parameters.getFrequencyPolicy();
        switch (policyValue) {
            case MIN_POWER:
                // Power-saving upper bound: every Job runs at the lowest frequency tier (last
                // table entry).
                return tiers.get(tiers.size() - 1);
            case EDP_HEURISTIC:
                return selectMinEdpTier(job, tiers);
            case DEFAULT:
                // Default (including unconfigured): first tier = the default tier declared by the
                // platform (never silently "pick the fastest" -- the identity of the default tier
                // comes from the table-order declaration, not from an implicit max()).
                return tiers.get(0);
            default:
                throw new IllegalStateException("Unknown frequency policy " + policyValue);
        }
    }

    /**
     * BAT-D6: declared-membership validation of the tier returned by a selector -- null, or not
     * inside the declared frequency-tier table of this VM (exact identity by mips, tiers are
     * unique by mips) = broken frequency-tier evidence chain, fail explicitly.
     *
     * <p>This validation has the same source as the same-named clause of the Java/Python
     * ledger (EnergyAccounting.tierByMips, verify-study-evidence.py): a freely constructed
     * tier identity is stopped here and cannot flow into JobOutcome.tierMips as a
     * "falsely declared tier" to corrupt the conservation-law recompute.</p>
     */
    private PlatformProfile.FrequencyTier validateDeclaredTier(
            Job job, PlatformProfile.FrequencyTier selected, List<PlatformProfile.FrequencyTier> tiers) {
        if (selected == null) {
            throw new IllegalStateException("DVFS frequency-tier selector "
                    + policyLabel() + " returned no tier for Job " + job.getCloudletId()
                    + " (BAT-D6): assignment at admission requires exactly one declared tier");
        }
        for (PlatformProfile.FrequencyTier tier : tiers) {
            if (Double.compare(tier.getMips(), selected.getMips()) == 0) {
                return tier;
            }
        }
        throw new IllegalStateException("DVFS frequency-tier selector " + policyLabel() + " returned tier mips "
                + selected.getMips() + " which matches no declared frequency tier of the target VM (Job "
                + job.getCloudletId() + ", BAT-D6): the per-Job tier evidence chain would be broken");
    }

    /**
     * BAT-D6: identity of the active policy cited in selector rejection messages (registered
     * label before enum name).
     */
    private String policyLabel() {
        if (Parameters.getRegisteredSchedulingAlgorithmProvider() != null) {
            String label = Parameters.getRegisteredSchedulingAlgorithmProvider().label();
            if (label != null) {
                return label;
            }
        }
        return String.valueOf(Parameters.getSchedulingAlgorithm());
    }

    /**
     * BAT-D2: v1 honest greedy EDP -- evaluate every tier at dispatch time and take the tier
     * with the smallest EDP.
     *
     * <p>Estimate for each candidate tier t (dispatch-observable state only, no clairvoyance):
     * {@code T_t = work / tier speed};
     * the makespan estimate takes the greedy critical-path lower bound
     * {@code clock + T_t} (this Job pushes the schedule at least to its own completion, and
     * any smaller estimate denies this Job its own execution interval). Per the task contract:
     * {@code EDP_t = (P_max(t)*T_t + P_idle(default)*makespan_est) * T_t}. The globally
     * optimal EDP is an NP-hard cross-Job problem (design section 3 honest exclusion), and the
     * v2 target is an independent greedy choice per Job; on ties the strict {@code <} keeps the
     * first table entry (deterministic tie-break, AGENTS #9).</p>
     *
     * @param job compute Job to be dispatched (supplies the declared per-PE compute MI)
     * @param tiers candidate frequency-tier table (descending)
     * @return tier with the smallest estimated EDP
     */
    private PlatformProfile.FrequencyTier selectMinEdpTier(Job job, List<PlatformProfile.FrequencyTier> tiers) {
        long workMi = job.getEffectiveExecutionLengthMi();
        double clock = CloudSim.clock();
        PlatformProfile.FrequencyTier defaultTier = tiers.get(0);
        PlatformProfile.FrequencyTier best = null;
        double bestEdp = Double.NaN;
        for (PlatformProfile.FrequencyTier tier : tiers) {
            double estimatedSeconds = workMi / tier.getMips();
            double makespanEstimate = clock + estimatedSeconds;
            double energyEstimate =
                    tier.getMaxPowerWatts() * estimatedSeconds + defaultTier.getIdlePowerWatts() * makespanEstimate;
            double edp = energyEstimate * estimatedSeconds;
            if (best == null || edp < bestEdp) {
                best = tier;
                bestEdp = edp;
            }
        }
        return best;
    }

    /**
     * BAT-D2: converts the declared per-PE compute MI into a CloudSim length at the tier speed.
     *
     * <p>{@code scaled = round(work * vm.mips / tier.mips)}: executing this length at the
     * declared MIPS in CloudSim takes exactly {@code work / tier.mips} (work / tier speed). The
     * work is unchanged -- the work-semantic quantity of the Job (MI) only converts its timing
     * basis with the tier speed, and the declared task length (TaskOutcome.lengthMi) stays
     * unchanged. An overflow, or a collapse to an unrepresentable value, fails explicitly
     * instead of being silently truncated.</p>
     */
    private long scaledTierWorkMi(Job job, CondorVM vm, PlatformProfile.FrequencyTier tier) {
        long declaredWorkMi = job.getEffectiveExecutionLengthMi();
        double factor = vm.getMips() / tier.getMips();
        double scaled = declaredWorkMi * factor;
        if (!Double.isFinite(scaled) || scaled >= (double) Long.MAX_VALUE) {
            throw new IllegalStateException("Frequency tier " + tier.getMips() + " scaling overflows Job "
                    + job.getCloudletId() + " work of " + declaredWorkMi + " MI");
        }
        return org.workflowsim.utils.TaskExecutionModel.requireRepresentableLength(
                Math.round(scaled), job.getNumberOfPes());
    }

    /**
     * Processes one Job return event.
     *
     * <p>The datacenter has already decided the failure outcome of a compute Job before it
     * submits the output replicas. This path only releases the VM, records the events, and
     * returns the Job to the workflow engine. The post-processing delay is sampled from the
     * overhead model according to the current Job depth.</p>
     *
     * @param ev Job return event
     */
    @Override
    protected void processCloudletReturn(SimEvent ev) {
        Cloudlet cloudlet = (Cloudlet) ev.getData();
        Job job = (Job) cloudlet;

        getCloudletReceivedList().add(cloudlet);
        getCloudletSubmittedList().remove(cloudlet);

        CondorVM vm = findCreatedVm(cloudlet.getVmId());
        // The Job has returned, so the VM can accept subsequent Jobs again.
        vm.setState(WorkflowSimTags.VM_STATUS_IDLE);

        double delay = 0.0;
        if (Parameters.getOverheadParams().getPostDelay() != null) {
            delay = Parameters.getOverheadParams().getPostDelay(job);
        }
        eventRecorder.record(
                SimulationEventType.JOB_RETURNED,
                CloudSim.clock(),
                job,
                SimulationEventRecorder.attributes(
                        "postDelaySeconds",
                        delay,
                        "jobStatus",
                        job.getCloudletStatus(),
                        "taskStatuses",
                        taskStatuses(job)));
        if (job.getCloudletStatus() == Cloudlet.FAILED) {
            eventRecorder.record(
                    SimulationEventType.JOB_FAILED,
                    CloudSim.clock(),
                    job,
                    SimulationEventRecorder.attributes("failedStatus", true));
        }
        schedule(this.workflowEngineId, delay, CloudSimTags.CLOUDLET_RETURN, cloudlet);

        cloudletsSubmitted--;
        // PLAT-12: trigger a subsequent scheduling cycle only when ready Jobs are waiting. The
        // historical implementation re-sent CLOUDLET_UPDATE unconditionally on every Job return,
        // so several returns at the same instant produced several idle cycles with
        // readyJobCount=0 and distorted the cycle/overhead metrics. Later ready Jobs are
        // dispatched through the scheduling cycle that processCloudletSubmit triggers itself, so
        // skipping the idle cycle does not stall any Job.
        if (!getCloudletList().isEmpty()) {
            schedule(this.getId(), 0.0, WorkflowSimTags.CLOUDLET_UPDATE);
        }
    }

    /**
     * The CloudSim VM id may come from external configuration and must not be assumed to equal
     * the list index.
     */
    private CondorVM findCreatedVm(int vmId) {
        for (Object item : getVmsCreatedList()) {
            CondorVM vm = (CondorVM) item;
            if (vm.getId() == vmId) {
                return vm;
            }
        }
        throw new IllegalStateException("Returned cloudlet references unknown created VM " + vmId);
    }

    private static List<Integer> taskStatuses(Job job) {
        List<Integer> statuses = new ArrayList<Integer>();
        for (Task task : job.getTaskList()) {
            statuses.add(task.getCloudletStatus());
        }
        return statuses;
    }

    /** Starts the scheduling entity and registers it with the CloudSim information service. */
    @Override
    public void startEntity() {
        Log.printLine(getName() + " is starting...");
        // When no regional GIS is configured, use the system default CloudInformationService.
        // int gisID = CloudSim.getEntityId(regionalCisName);
        int gisID = -1;
        if (gisID == -1) {
            gisID = CloudSim.getCloudInfoServiceEntityId();
        }

        // Register this scheduling entity with the CloudSim information service.
        sendNow(gisID, CloudSimTags.REGISTER_RESOURCE, getId());
    }

    /** Shuts down the scheduling entity and releases the datacenter resources. */
    @Override
    public void shutdownEntity() {
        clearDatacenters();
        Log.printLine(getName() + " is shutting down...");
    }

    /** Notifies the workflow engine to start submitting Jobs once VM creation completes. */
    @Override
    protected void submitCloudlets() {
        sendNow(this.workflowEngineId, CloudSimTags.CLOUDLET_SUBMIT, null);
    }

    /**
     * Receives a batch of ready Jobs from the workflow engine and triggers a scheduling cycle.
     *
     * <p>The engine may send an empty batch to a scheduler that received no Jobs; an empty batch
     * and a {@code null} payload do not trigger a scheduling cycle, so no idle-cycle event is
     * produced (PLAT-12). The historical dead-code flag
     * {@code processCloudletSubmitHasShown} has been removed (PLAT-20: setting it had no
     * effect).</p>
     *
     * @param ev submit event carrying the Job list
     */
    protected void processCloudletSubmit(SimEvent ev) {
        Object data = ev.getData();
        if (data == null) {
            return;
        }
        List<Job> list = (List) data;
        if (list.isEmpty()) {
            return;
        }
        getCloudletList().addAll(list);

        sendNow(this.getId(), WorkflowSimTags.CLOUDLET_UPDATE);
    }

    /**
     * Requests the resource characteristics of the bound datacenters.
     *
     * @param ev resource-characteristics request event
     */
    @Override
    protected void processResourceCharacteristicsRequest(SimEvent ev) {
        setDatacenterCharacteristicsList(new HashMap<>());
        Log.printLine(CloudSim.clock() + ": " + getName() + ": Cloud Resource List received with "
                + getDatacenterIdsList().size() + " resource(s)");
        for (Integer datacenterId : getDatacenterIdsList()) {
            sendNow(datacenterId, CloudSimTags.RESOURCE_CHARACTERISTICS, getId());
        }
    }
}
