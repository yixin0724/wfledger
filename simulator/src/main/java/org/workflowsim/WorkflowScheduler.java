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
import org.workflowsim.scheduling.CrchSchedulingAlgorithm;
import org.workflowsim.scheduling.CwsRankMinRoundRobinSchedulingAlgorithm;
import org.workflowsim.scheduling.DataAwareSchedulingAlgorithm;
import org.workflowsim.scheduling.DeferralAwareSchedulingAlgorithm;
import org.workflowsim.scheduling.DvfsSlackSchedulingAlgorithm;
import org.workflowsim.scheduling.EbpsmSchedulingAlgorithm;
import org.workflowsim.scheduling.FCFSSchedulingAlgorithm;
import org.workflowsim.scheduling.FastestVmSchedulingAlgorithm;
import org.workflowsim.scheduling.FirstFitIdleSchedulingAlgorithm;
import org.workflowsim.scheduling.FrequencyTierSelector;
import org.workflowsim.scheduling.LjfFastestIdleSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchMCTSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchMaxMinSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchMinMinSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchRoundRobinSchedulingAlgorithm;
import org.workflowsim.scheduling.ReshiSchedulingAlgorithm;
import org.workflowsim.scheduling.ReshiStrategy;
import org.workflowsim.scheduling.RlPolicySchedulingAlgorithm;
import org.workflowsim.scheduling.SptFastestIdleSchedulingAlgorithm;
import org.workflowsim.scheduling.StaticSchedulePlan;
import org.workflowsim.scheduling.StaticSchedulingAlgorithm;
import org.workflowsim.scheduling.TimedStaticSchedulingAlgorithm;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;

public class WorkflowScheduler extends DatacenterBroker {

    private int workflowEngineId;

    private BaseSchedulingAlgorithm activeSchedulingPolicy;

    private SchedulingAlgorithm activeSchedulingPolicyType;

    private SimulationEventRecorder eventRecorder = SimulationEventRecorder.disabled();
    private StaticSchedulePlan staticSchedulePlan = StaticSchedulePlan.empty();
    private boolean strictDataflowBindings;

    public void enableDataflowBindingGuard() {
        strictDataflowBindings = true;
    }

    private final Map<Integer, Integer> createdVmHostAssignments = new LinkedHashMap<Integer, Integer>();

    public WorkflowScheduler(String name) throws Exception {
        super(name);
    }

    public void bindSchedulerDatacenter(int datacenterId) {
        if (datacenterId <= 0) {
            Log.printLine("Error in data center id");
            return;
        }
        this.datacenterIdsList.add(datacenterId);
    }

    public void setWorkflowEngineId(int workflowEngineId) {
        this.workflowEngineId = workflowEngineId;
    }

    public void setEventRecorder(SimulationEventRecorder value) {
        if (value == null) {
            throw new IllegalArgumentException("Event recorder cannot be null");
        }
        this.eventRecorder = value;
    }

    public Map<Integer, Integer> getCreatedVmHostAssignments() {
        return Collections.unmodifiableMap(new LinkedHashMap<Integer, Integer>(createdVmHostAssignments));
    }

    public void setStaticSchedulePlan(StaticSchedulePlan value) {
        if (value == null) {
            throw new IllegalArgumentException("Static schedule plan cannot be null");
        }
        this.staticSchedulePlan = value;
        if (activeSchedulingPolicy instanceof StaticSchedulingAlgorithm) {
            ((StaticSchedulingAlgorithm) activeSchedulingPolicy).setStaticSchedulePlan(value);
        }
    }

    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case CloudSimTags.RESOURCE_CHARACTERISTICS_REQUEST:
                processResourceCharacteristicsRequest(ev);
                break;

            case CloudSimTags.RESOURCE_CHARACTERISTICS:
                processResourceCharacteristics(ev);
                break;

            case CloudSimTags.VM_CREATE_ACK:
                processVmCreate(ev);
                break;

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

    private BaseSchedulingAlgorithm createSchedulingPolicy(SchedulingAlgorithm name) {
        BaseSchedulingAlgorithm algorithm;

        switch (name) {
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
                algorithm = new RlPolicySchedulingAlgorithm();
                break;
            case MINMIN:
                requireLegacyAliasDeclaration(name, "SPT-fastest-idle");
                algorithm = new SptFastestIdleSchedulingAlgorithm();
                break;
            case MAXMIN:
                requireLegacyAliasDeclaration(name, "LJF-fastest-idle");
                algorithm = new LjfFastestIdleSchedulingAlgorithm();
                break;
            case MCT:
                requireLegacyAliasDeclaration(name, "fastest-VM");
                algorithm = new FastestVmSchedulingAlgorithm();
                break;
            case ROUNDROBIN:
                requireLegacyAliasDeclaration(name, "First-Fit-Idle");
                algorithm = new FirstFitIdleSchedulingAlgorithm();
                break;
            case DATA:
                algorithm = new DataAwareSchedulingAlgorithm();
                break;
            case CARBON_WINDOW_DEFER:
                algorithm = new CarbonWindowDeferSchedulingAlgorithm();
                break;
            case CWS_RANKMIN_RR:
                algorithm = new CwsRankMinRoundRobinSchedulingAlgorithm();
                break;
            case RESHI_C:
                algorithm = new ReshiSchedulingAlgorithm(ReshiStrategy.RESHI_C);
                break;
            case RESHI_M:
                algorithm = new ReshiSchedulingAlgorithm(ReshiStrategy.RESHI_M);
                break;
            case EBPSM:
                algorithm = new EbpsmSchedulingAlgorithm();
                break;
            case DVFS_SLACK:
                algorithm = new DvfsSlackSchedulingAlgorithm();
                break;
            case CRCH:
                algorithm = new CrchSchedulingAlgorithm();
                break;
            case STATIC:
                if (Parameters.getPlanningAlgorithm() == Parameters.PlanningAlgorithm.CAWOSCHED_SLACK
                        || Parameters.getPlanningAlgorithm() == Parameters.PlanningAlgorithm.CAWOSCHED_PRESSURE) {
                    algorithm = new TimedStaticSchedulingAlgorithm();
                } else {
                    algorithm = new StaticSchedulingAlgorithm();
                }
                break;
            case INVALID:
                algorithm = new StaticSchedulingAlgorithm();
                break;
            default:
                throw new IllegalStateException("SchedulingAlgorithm." + name + " has no policy "
                        + "binding in WorkflowScheduler; add an explicit case when the enum grows "
                        + "(AUD-F21).");
        }
        return algorithm;
    }

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

    protected BaseSchedulingAlgorithm getSchedulingPolicy() {

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

    @Override
    protected void processVmCreate(SimEvent ev) {
        int[] data = (int[]) ev.getData();
        int datacenterId = data[0];
        int vmId = data[1];
        int result = data[2];

        if (result == CloudSimTags.TRUE) {
            getVmsToDatacentersMap().put(vmId, datacenterId);

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

            Log.printLine(CloudSim.clock() + ": " + getName() + ": Creation of VM #" + vmId + " failed in Datacenter #"
                    + datacenterId);
        }

        incrementVmsAcks();

        if (getVmsCreatedList().size() == getVmList().size() - getVmsDestroyed()) {
            submitCloudlets();

            if (!getCloudletList().isEmpty()) {
                sendNow(this.getId(), WorkflowSimTags.CLOUDLET_UPDATE);
            }
        } else {

            if (getVmsRequested() == getVmsAcks()) {

                for (int nextDatacenterId : getDatacenterIdsList()) {
                    if (!getDatacenterRequestedIdsList().contains(nextDatacenterId)) {
                        createVmsInDatacenter(nextDatacenterId);
                        return;
                    }
                }

                throw new IllegalStateException(getName() + " could create only "
                        + getVmsCreatedList().size() + " of " + getVmList().size()
                        + " requested VMs; refusing to run on a degraded platform");
            }
        }
    }

    protected void processCloudletUpdate(SimEvent ev) {

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

    private void armDeferralWakeUp(BaseSchedulingAlgorithm policy, List<Cloudlet> scheduledList) {
        if (!(policy instanceof DeferralAwareSchedulingAlgorithm)) {
            return;
        }
        if (!scheduledList.isEmpty() || getCloudletList().isEmpty()) {

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

            assignFrequencyTier(job);
            schedule(getVmsToDatacentersMap().get(vmId), delay, CloudSimTags.CLOUDLET_SUBMIT, cloudlet);
            eventRecorder.record(
                    SimulationEventType.JOB_DISPATCHED,
                    CloudSim.clock(),
                    job,
                    SimulationEventRecorder.attributes("queueDelaySeconds", delay));
        }
    }

    private void assignFrequencyTier(Job job) {
        if (job.getClassType() != Parameters.ClassType.COMPUTE.value) {

            return;
        }
        CondorVM vm = findCreatedVm(job.getVmId());
        if (!vm.hasFrequencyTiers()) {
            return;
        }
        List<PlatformProfile.FrequencyTier> tiers = vm.getFrequencyTiers();
        PlatformProfile.FrequencyTier selected = selectFrequencyTier(job, tiers);
        long scaledWork = scaledTierWorkMi(job, vm, selected);

        job.setTierMips(selected.getMips());
        job.setEffectiveExecutionLengthMi(scaledWork);
        job.setCloudletLength(scaledWork);
    }

    private PlatformProfile.FrequencyTier selectFrequencyTier(Job job, List<PlatformProfile.FrequencyTier> tiers) {
        BaseSchedulingAlgorithm policy = getSchedulingPolicy();
        if (policy instanceof FrequencyTierSelector) {
            return validateDeclaredTier(job, ((FrequencyTierSelector) policy).selectFrequencyTier(job, tiers), tiers);
        }
        SimulationConfig.FrequencyPolicy policyValue = Parameters.getFrequencyPolicy();
        switch (policyValue) {
            case MIN_POWER:
                return tiers.get(tiers.size() - 1);
            case EDP_HEURISTIC:
                return selectMinEdpTier(job, tiers);
            case DEFAULT:
                return tiers.get(0);
            default:
                throw new IllegalStateException("Unknown frequency policy " + policyValue);
        }
    }

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

    private String policyLabel() {
        if (Parameters.getRegisteredSchedulingAlgorithmProvider() != null) {
            String label = Parameters.getRegisteredSchedulingAlgorithmProvider().label();
            if (label != null) {
                return label;
            }
        }
        return String.valueOf(Parameters.getSchedulingAlgorithm());
    }

    private PlatformProfile.FrequencyTier selectMinEdpTier(Job job, List<PlatformProfile.FrequencyTier> tiers) {
        long workMi = job.getEffectiveExecutionLengthMi();
        double clock = CloudSim.clock();
        PlatformProfile.FrequencyTier defaultTier = tiers.get(0);
        PlatformProfile.FrequencyTier best = null;
        double bestEdp = Double.NaN;
        for (PlatformProfile.FrequencyTier tier : tiers) {
            double estimatedSeconds = workMi / tier.getMips();
            double makespanEstimate = clock + estimatedSeconds;
            double energyEstimate = tier.getMaxPowerWatts() * estimatedSeconds
                    + defaultTier.getIdlePowerWatts() * (makespanEstimate - estimatedSeconds);
            double edp = energyEstimate * estimatedSeconds;
            if (best == null || edp < bestEdp) {
                best = tier;
                bestEdp = edp;
            }
        }
        return best;
    }

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

    @Override
    protected void processCloudletReturn(SimEvent ev) {
        Cloudlet cloudlet = (Cloudlet) ev.getData();
        Job job = (Job) cloudlet;

        getCloudletReceivedList().add(cloudlet);
        getCloudletSubmittedList().remove(cloudlet);

        CondorVM vm = findCreatedVm(cloudlet.getVmId());

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

        if (!getCloudletList().isEmpty()) {
            schedule(this.getId(), 0.0, WorkflowSimTags.CLOUDLET_UPDATE);
        }
    }

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

    @Override
    public void startEntity() {
        Log.printLine(getName() + " is starting...");

        int gisID = -1;
        if (gisID == -1) {
            gisID = CloudSim.getCloudInfoServiceEntityId();
        }

        sendNow(gisID, CloudSimTags.REGISTER_RESOURCE, getId());
    }

    @Override
    public void shutdownEntity() {
        clearDatacenters();
        Log.printLine(getName() + " is shutting down...");
    }

    @Override
    protected void submitCloudlets() {
        sendNow(this.workflowEngineId, CloudSimTags.CLOUDLET_SUBMIT, null);
    }

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
