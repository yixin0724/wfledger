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
     * {@link Parameters.SchedulingAlgorithm}. The four deprecated enums still bind their
     * historical policies and only serve compatibility with existing callers; research runs
     * should select an explicitly maintained ready-batch or static policy.</p>
     *
     * @param name scheduling algorithm enum
     * @return the corresponding scheduling policy
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
                algorithm = new SptFastestIdleSchedulingAlgorithm();
                break;
            case MAXMIN:
                // Preserves the legacy behavior: LJF-fastest-idle variant.
                algorithm = new LjfFastestIdleSchedulingAlgorithm();
                break;
            case MCT:
                // Preserves the legacy behavior: greedy fastest VM.
                algorithm = new FastestVmSchedulingAlgorithm();
                break;
            case ROUNDROBIN:
                // Preserves the legacy behavior: First-Fit-Idle.
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
            default:
                algorithm = new StaticSchedulingAlgorithm();
                break;
        }
        return algorithm;
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
     * BAT-D2（2026-10-29）：为刚获得 VM 指派的计算 Job 决定唯一频档并缩放作业长度。
     *
     * <p>语义契约（docs/advanced/DVFS_DIMENSION_DESIGN.md §1.4/§2.2）：assignment at
     * admission——每个计算 Job 在派发到 VM 的时刻获得恰好一个频档，全 Job 时长固定
     * 不做中途变频，保持 one-rectangle-per-Job 的可审计结构。仿真级语义的"低档变慢"
     * 由缩放作业 MI 表达：执行时长 = work/档速，在 CloudSim 按声明 MIPS 执行的机器上
     * 等价于把长度乘 {@code vm.mips / tier.mips}（数据中心提交前的
     * {@code projectComputeExecutionLength} 以 effectiveExecutionLengthMi 为真源重构
     * Job 长度，故此处同时写入 effective 值与 Cloudlet 长度）。stage-in 注入的
     * 传输 MI 发生在其后，按 VM 声明速执行（网络传输不随 CPU 频档伸缩）。</p>
     *
     * <p>非计算 Job（stage-in 平台准备）与无频档 VM（v1 平台或混合平台的无档 VM）
     * 不获得频档：tierMips 保持 null，长度不变——v1 运行逐位不变。</p>
     *
     * @param job 刚被调度算法指派 VM 的计算 Job
     */
    private void assignFrequencyTier(Job job) {
        if (job.getClassType() != Parameters.ClassType.COMPUTE.value) {
            // 非计算 Job（stage-in/stage-out/cleanup 平台簿记）不占用计算频档。
            return;
        }
        CondorVM vm = findCreatedVm(job.getVmId());
        if (!vm.hasFrequencyTiers()) {
            return;
        }
        List<PlatformProfile.FrequencyTier> tiers = vm.getFrequencyTiers();
        PlatformProfile.FrequencyTier selected = selectFrequencyTier(job, tiers);
        long scaledWork = scaledTierWorkMi(job, vm, selected);
        // 证据链：Job 在报告捕获时把该值冻结进 JobOutcome.tierMips。
        job.setTierMips(selected.getMips());
        job.setEffectiveExecutionLengthMi(scaledWork);
        job.setCloudletLength(scaledWork);
    }

    /**
     * BAT-D2：按会话频档策略选择 VM 频档表中的唯一档（表序=降序、首档=默认档）。
     *
     * <p>BAT-D6（2026-10-29，docs/advanced/DVFS_DIMENSION_DESIGN.md §2.2 注册面）：活动
     * 调度策略实例实现 {@link FrequencyTierSelector} 时，逐 Job 档选择权归算法——第一类
     * 调度时刻 API 的消费点，注册 DVFS-aware 算轨（如 EDP_GREEDY 示范）表达异质档位
     * 策略的唯一通道；会话级策略（BASE 路径）退为无 selector 算法的缺省斜切。两条
     * 路径的返回值都送 {@link #validateDeclaredTier(Job, PlatformProfile.FrequencyTier, List)}
     * 做声明档成员校验（档按 MIPS 唯一），表外/空档按证据链断裂显式拒绝（不静默回退）。</p>
     */
    private PlatformProfile.FrequencyTier selectFrequencyTier(Job job, List<PlatformProfile.FrequencyTier> tiers) {
        BaseSchedulingAlgorithm policy = getSchedulingPolicy();
        if (policy instanceof FrequencyTierSelector) {
            return validateDeclaredTier(job, ((FrequencyTierSelector) policy).selectFrequencyTier(job, tiers), tiers);
        }
        SimulationConfig.FrequencyPolicy policyValue = Parameters.getFrequencyPolicy();
        switch (policyValue) {
            case MIN_POWER:
                // 节电上限：每个 Job 都在最低频档（表末档）执行。
                return tiers.get(tiers.size() - 1);
            case EDP_HEURISTIC:
                return selectMinEdpTier(job, tiers);
            case DEFAULT:
                // 缺省（含未配置）：首档=平台声明的默认档（绝不静默"挑最快"——
                // 默认档身份来自表序声明，而非隐式 max()）。
                return tiers.get(0);
            default:
                throw new IllegalStateException("Unknown frequency policy " + policyValue);
        }
    }

    /**
     * BAT-D6：selector 返回档的声明成员校验——null 或不在本 VM 声明频档表内
     * （按 mips 精确身份，档按 mips 唯一）= 频档证据链断裂，显式失败。
     *
     * <p>本校验与 Java/Python 台账的同名条款（EnergyAccounting.tierByMips、
     * verify-study-evidence.py）同源：自由构造的档身份在这里被挡下，不会以
     * "伪声明档"流入 JobOutcome.tierMips 污染守恒律复算。</p>
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

    /** BAT-D6：selector 拒绝消息引用的活动策略身份（注册标签优先于枚举名）。 */
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
     * BAT-D2：v1 诚实贪心 EDP——派发时刻逐档估值，取 EDP 最小的档。
     *
     * <p>每个候选档 t 估计（只用派发可观测状态，无千里眼）：
     * {@code T_t = work/档速}；
     * makespan 估计取贪心关键路径下界 {@code clock + T_t}（本 Job 至少把日程推到自身
     * 完成，任何更小的估计都在否认本 Job 自己的运行区间）。按任务契约：
     * {@code EDP_t = (P_max(t)·T_t + P_idle(default)·makespan_est) × T_t}。全局最优 EDP
     * 是 NP-hard 跨 Job 问题（设计 §3 诚实弃项），v2 目标是每 Job 独立贪心；并列时
     * 严格 {@code <} 保表序首位（确定性 tie-break，AGENTS #9）。</p>
     *
     * @param job 待派发的计算 Job（提供声明的每 PE 计算 MI）
     * @param tiers 候选频档表（降序）
     * @return EDP 估计最小的档
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
     * BAT-D2：把声明的每 PE 计算 MI 按档速折算为 CloudSim 长度。
     *
     * <p>{@code scaled = round(work × vm.mips / tier.mips)}：CloudSim 以声明 MIPS 执行
     * 该长度所得时长恰为 {@code work / tier.mips}（work/档速）。工作不变——Job 的工作
     * 语义量（MI）只随档速换算计时基准，声明任务长度（TaskOutcome.lengthMi）不变。
     * 溢出/塌缩到不可表示值显式失败，不静默截断。</p>
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
     * 处理一个作业返回事件。
     *
     * <p>数据中心已在提交输出副本之前判定 compute Job 的故障结果。本路径只负责释放 VM、
     * 记录事件，再向工作流引擎回传作业。后处理延迟按当前作业深度从开销模型抽样。</p>
     *
     * @param ev 作业返回事件
     */
    @Override
    protected void processCloudletReturn(SimEvent ev) {
        Cloudlet cloudlet = (Cloudlet) ev.getData();
        Job job = (Job) cloudlet;

        getCloudletReceivedList().add(cloudlet);
        getCloudletSubmittedList().remove(cloudlet);

        CondorVM vm = findCreatedVm(cloudlet.getVmId());
        // 作业已返回，虚拟机重新可接收后续作业。
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
        // PLAT-12：仅在有就绪作业等待时才触发后续调度周期。历史实现对每个作业返回
        // 无条件补发 CLOUDLET_UPDATE，同刻多个返回会产生多个 readyJobCount=0 的
        // 空转周期，扭曲周期/开销类指标。就绪作业后续到达时由 processCloudletSubmit
        // 自行触发调度周期，跳过空转不会造成作业停滞。
        if (!getCloudletList().isEmpty()) {
            schedule(this.getId(), 0.0, WorkflowSimTags.CLOUDLET_UPDATE);
        }
    }

    /** CloudSim VM 编号可由外部配置，不能假设它等于列表下标。 */
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

    /** 启动调度实体并注册到 CloudSim 信息服务。 */
    @Override
    public void startEntity() {
        Log.printLine(getName() + " is starting...");
        // 未配置区域 GIS 时，使用系统默认的 CloudInformationService。
        // int gisID = CloudSim.getEntityId(regionalCisName);
        int gisID = -1;
        if (gisID == -1) {
            gisID = CloudSim.getCloudInfoServiceEntityId();
        }

        // 向 CloudSim 信息服务注册本调度实体。
        sendNow(gisID, CloudSimTags.REGISTER_RESOURCE, getId());
    }

    /** 关闭调度实体并释放数据中心资源。 */
    @Override
    public void shutdownEntity() {
        clearDatacenters();
        Log.printLine(getName() + " is shutting down...");
    }

    /** VM 创建完成后通知工作流引擎开始提交作业。 */
    @Override
    protected void submitCloudlets() {
        sendNow(this.workflowEngineId, CloudSimTags.CLOUDLET_SUBMIT, null);
    }

    /**
     * 接收一批来自工作流引擎的就绪作业并触发调度周期。
     *
     * <p>引擎可能向未分配到作业的调度器发送空批次；空批次与 {@code null} 负载
     * 不触发调度周期，避免产生空转周期事件（PLAT-12）。历史死代码标记
     * {@code processCloudletSubmitHasShown} 已移除（PLAT-20：置位后无任何效果）。</p>
     *
     * @param ev 包含作业列表的提交事件
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
     * 请求已绑定数据中心的资源特征。
     *
     * @param ev 资源特征请求事件
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
