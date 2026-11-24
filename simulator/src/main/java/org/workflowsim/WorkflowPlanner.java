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
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.experiment.SimulationEventRecorder;
import org.workflowsim.experiment.SimulationEventType;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.planning.BasePlanningAlgorithm;
import org.workflowsim.planning.CaWoSchedPlanningAlgorithm;
import org.workflowsim.planning.DheftPlanningAlgorithm;
import org.workflowsim.planning.DvfsSlackPlanningAlgorithm;
import org.workflowsim.planning.GreenHeftPlanningAlgorithm;
import org.workflowsim.planning.IcpcpPlanningAlgorithm;
import org.workflowsim.planning.LocalCpopPlanningAlgorithm;
import org.workflowsim.planning.LocalHeftPlanningAlgorithm;
import org.workflowsim.planning.LocalPeftPlanningAlgorithm;
import org.workflowsim.planning.MoHeftPlanningAlgorithm;
import org.workflowsim.planning.NacoGaHeftPlanningAlgorithm;
import org.workflowsim.planning.PSOPlanningAlgorithm;
import org.workflowsim.planning.PlanningContext;
import org.workflowsim.planning.RandomPlanningAlgorithm;
import org.workflowsim.planning.SharedStorageCpopPlanningAlgorithm;
import org.workflowsim.planning.SharedStorageDagPlanTrace;
import org.workflowsim.planning.SharedStorageDlsPlanningAlgorithm;
import org.workflowsim.planning.SharedStorageEtfPlanningAlgorithm;
import org.workflowsim.planning.SharedStorageHeftPlanningAlgorithm;
import org.workflowsim.planning.SharedStoragePeftPlanningAlgorithm;
import org.workflowsim.planning.StaticMaxMinPlanningAlgorithm;
import org.workflowsim.planning.StaticMctPlanningAlgorithm;
import org.workflowsim.planning.StaticMetPlanningAlgorithm;
import org.workflowsim.planning.StaticMinMinPlanningAlgorithm;
import org.workflowsim.planning.StaticOlbPlanningAlgorithm;
import org.workflowsim.planning.StaticRoundRobinPlanningAlgorithm;
import org.workflowsim.planning.StaticSufferagePlanningAlgorithm;
import org.workflowsim.planning.cawosched.CaWoSchedVariant;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;

public final class WorkflowPlanner extends SimEntity {

    protected List<Task> taskList;

    protected WorkflowParser parser;

    private int clusteringEngineId;

    private ClusteringEngine clusteringEngine;
    private SimulationEventRecorder eventRecorder = SimulationEventRecorder.disabled();
    private PlanningContext planningContext;
    private List<Task> parsedTaskSnapshot = Collections.emptyList();
    private SharedStorageDagPlanTrace sharedStorageDagPlanTrace;

    private org.workflowsim.utils.TaskCostMatrix taskCostMatrix;

    private org.workflowsim.data.v2.CoherentDataflowRuntime coherentDataflow;

    public void setCoherentDataflowRuntime(org.workflowsim.data.v2.CoherentDataflowRuntime value) {
        if (value == null || coherentDataflow != null) {
            throw new IllegalArgumentException("V2 runtime must be installed once");
        }
        coherentDataflow = value;
    }

    public WorkflowPlanner(String name) throws Exception {
        this(name, 1);
    }

    public WorkflowPlanner(String name, int schedulers) throws Exception {
        super(name);

        setTaskList(new ArrayList<>());
        this.clusteringEngine = new ClusteringEngine(name + "_Merger_", schedulers);
        this.clusteringEngineId = this.clusteringEngine.getId();
        this.parser =
                new WorkflowParser(getClusteringEngine().getWorkflowEngine().getSchedulerId(0));
    }

    public int getClusteringEngineId() {
        return this.clusteringEngineId;
    }

    public ClusteringEngine getClusteringEngine() {
        return this.clusteringEngine;
    }

    public WorkflowParser getWorkflowParser() {
        return this.parser;
    }

    public int getWorkflowEngineId() {
        return getClusteringEngine().getWorkflowEngineId();
    }

    public WorkflowEngine getWorkflowEngine() {
        return getClusteringEngine().getWorkflowEngine();
    }

    public void setEventRecorder(SimulationEventRecorder value) {
        if (value == null) {
            throw new IllegalArgumentException("Event recorder cannot be null");
        }
        this.eventRecorder = value;
        getClusteringEngine().setEventRecorder(value);
    }

    public void setPlanningContext(PlanningContext value) {
        if (value == null) {
            throw new IllegalArgumentException("Planning context cannot be null");
        }
        this.planningContext = value;
    }

    public void setTaskCostMatrix(org.workflowsim.utils.TaskCostMatrix value) {
        this.taskCostMatrix = value;
    }

    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case WorkflowSimTags.START_SIMULATION:
                getWorkflowParser().parse();
                setTaskList(getWorkflowParser().getTaskList());
                applyTaskCostMatrix();
                validateFailureModelCoverage();
                if (coherentDataflow != null) {
                    java.util.Set<org.workflowsim.data.v2.DataLocation> locations = new java.util.TreeSet<>();
                    locations.add(org.workflowsim.data.v2.CoherentDataflowRuntime.SOURCE);
                    for (org.cloudbus.cloudsim.Vm vm : getWorkflowEngine().getAllVmList()) {
                        locations.add(org.workflowsim.data.v2.DataLocation.vm(vm.getId()));
                    }
                    coherentDataflow.initializePlan(
                            org.workflowsim.data.v2.DataflowFilePlan.capture(
                                    getTaskList(), getWorkflowParser().getTaskWorkflowIndices()),
                            locations);
                }
                parsedTaskSnapshot = Collections.unmodifiableList(new ArrayList<Task>(getTaskList()));
                eventRecorder.record(
                        SimulationEventType.WORKFLOW_PARSED,
                        CloudSim.clock(),
                        null,
                        SimulationEventRecorder.attributes(
                                "taskCount",
                                getTaskList().size(),
                                "inputCount",
                                getWorkflowParser().getInputReports().size()));
                boolean explicitPlannerDecision = Parameters.getPlanningAlgorithm() != PlanningAlgorithm.INVALID;
                long planningElapsedNanos = 0L;
                if (explicitPlannerDecision) {
                    long planningStart = System.nanoTime();
                    processPlanning();
                    planningElapsedNanos = System.nanoTime() - planningStart;
                } else {
                    processPlanning();
                }
                eventRecorder.record(
                        SimulationEventType.PLANNING_COMPLETED,
                        CloudSim.clock(),
                        null,
                        SimulationEventRecorder.attributes(
                                "planningAlgorithm",
                                Parameters.getPlanningAlgorithm().name(),
                                "explicitPlannerDecision",
                                explicitPlannerDecision,
                                "planningDecisionElapsedNanos",
                                planningElapsedNanos,
                                "sharedStorageDagPlanTraceAvailable",
                                sharedStorageDagPlanTrace != null,
                                "plannedTaskCount",
                                sharedStorageDagPlanTrace == null
                                        ? 0
                                        : sharedStorageDagPlanTrace
                                                .getTaskPlans()
                                                .size()));
                processImpactFactors(getTaskList());

                getWorkflowEngine()
                        .setWorkflowArrivals(
                                Parameters.getWorkflowArrivalSeconds(),
                                getWorkflowParser().getTaskWorkflowIndices());
                sendNow(getClusteringEngineId(), WorkflowSimTags.JOB_SUBMIT, getTaskList());
                break;
            case CloudSimTags.END_OF_SIMULATION:
                shutdownEntity();
                break;

            default:
                processOtherEvent(ev);
                break;
        }
    }

    private void processPlanning() {
        sharedStorageDagPlanTrace = null;
        if (Parameters.getPlanningAlgorithm().equals(PlanningAlgorithm.INVALID)) {
            return;
        }
        BasePlanningAlgorithm planner = getPlanningAlgorithm(Parameters.getPlanningAlgorithm());

        planner.setTaskList(getTaskList());
        planner.setVmList(getWorkflowEngine().getAllVmList());
        try {
            planner.run();
            if (planner instanceof SharedStorageHeftPlanningAlgorithm) {
                sharedStorageDagPlanTrace = ((SharedStorageHeftPlanningAlgorithm) planner).getLastPlanTrace();
            } else if (planner instanceof SharedStorageCpopPlanningAlgorithm) {
                sharedStorageDagPlanTrace = ((SharedStorageCpopPlanningAlgorithm) planner).getLastPlanTrace();
            } else if (planner instanceof SharedStorageDlsPlanningAlgorithm) {
                sharedStorageDagPlanTrace = ((SharedStorageDlsPlanningAlgorithm) planner).getLastPlanTrace();
            } else if (planner instanceof SharedStorageEtfPlanningAlgorithm) {
                sharedStorageDagPlanTrace = ((SharedStorageEtfPlanningAlgorithm) planner).getLastPlanTrace();
            } else if (planner instanceof SharedStoragePeftPlanningAlgorithm) {
                sharedStorageDagPlanTrace = ((SharedStoragePeftPlanningAlgorithm) planner).getLastPlanTrace();
            }
        } catch (org.workflowsim.exception.SimulationConfigurationException exception) {

            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Planning algorithm " + Parameters.getPlanningAlgorithm() + " failed", exception);
        }
    }

    private void validateFailureModelCoverage() {
        List<Integer> vmIds = new ArrayList<Integer>();
        for (Vm vm : getWorkflowEngine().getAllVmList()) {
            vmIds.add(Integer.valueOf(vm.getId()));
        }
        FailureParameters.validateRuntimeCoverage(getTaskList(), vmIds);
    }

    private void applyTaskCostMatrix() {
        if (taskCostMatrix == null) {
            return;
        }
        List<Integer> taskIds = new ArrayList<Integer>();
        for (Task task : getTaskList()) {
            taskIds.add(Integer.valueOf(task.getCloudletId()));
        }
        List<Integer> vmIds = new ArrayList<Integer>();
        for (Vm vm : getWorkflowEngine().getAllVmList()) {
            vmIds.add(Integer.valueOf(vm.getId()));
        }
        if (!taskCostMatrix.covers(taskIds, vmIds)) {
            throw new IllegalStateException(
                    "Task cost matrix does not cover every parsed task ID " + taskIds + " on every VM ID " + vmIds);
        }
        for (Task task : getTaskList()) {
            java.util.Map<Integer, Double> perVm = new java.util.LinkedHashMap<Integer, Double>();
            for (Integer vmId : vmIds) {
                perVm.put(vmId, Double.valueOf(taskCostMatrix.getCostSeconds(task.getCloudletId(), vmId.intValue())));
            }
            task.setVmExecutionCostSeconds(perVm);
        }
    }

    private BasePlanningAlgorithm getPlanningAlgorithm(PlanningAlgorithm name) {
        BasePlanningAlgorithm planner;

        switch (name) {
            case INVALID:
                planner = null;
                break;
            case RANDOM:
                planner = new RandomPlanningAlgorithm();
                break;
            case STATIC_OLB:
                planner = new StaticOlbPlanningAlgorithm();
                break;
            case STATIC_MET:
                planner = new StaticMetPlanningAlgorithm();
                break;
            case STATIC_MCT:
                planner = new StaticMctPlanningAlgorithm();
                break;
            case STATIC_MINMIN:
                planner = new StaticMinMinPlanningAlgorithm();
                break;
            case STATIC_MAXMIN:
                planner = new StaticMaxMinPlanningAlgorithm();
                break;
            case STATIC_SUFFERAGE:
                planner = new StaticSufferagePlanningAlgorithm();
                break;
            case STATIC_ROUND_ROBIN:
                planner = new StaticRoundRobinPlanningAlgorithm();
                break;
            case SHARED_STORAGE_HEFT:
                planner = new SharedStorageHeftPlanningAlgorithm(planningContext);
                break;
            case SHARED_STORAGE_CPOP:
                planner = new SharedStorageCpopPlanningAlgorithm(planningContext);
                break;
            case SHARED_STORAGE_DLS:
                planner = new SharedStorageDlsPlanningAlgorithm(planningContext);
                break;
            case SHARED_STORAGE_ETF:
                planner = new SharedStorageEtfPlanningAlgorithm(planningContext);
                break;
            case SHARED_STORAGE_PEFT:
                planner = new SharedStoragePeftPlanningAlgorithm(planningContext);
                break;
            case CAWOSCHED_SLACK:
                planner = new CaWoSchedPlanningAlgorithm(planningContext, CaWoSchedVariant.SLACK);
                break;
            case CAWOSCHED_PRESSURE:
                planner = new CaWoSchedPlanningAlgorithm(planningContext, CaWoSchedVariant.PRESSURE);
                break;
            case NACO_GA_HEFT:
                planner = new NacoGaHeftPlanningAlgorithm(planningContext);
                break;
            case GREEN_HEFT:
                planner = new GreenHeftPlanningAlgorithm(planningContext);
                break;
            case MO_HEFT:
                planner = new MoHeftPlanningAlgorithm(planningContext);
                break;
            case IC_PCP:
                planner = new IcpcpPlanningAlgorithm(planningContext);
                break;
            case DVFS_SLACK:
                planner = new DvfsSlackPlanningAlgorithm(planningContext);
                break;
            case DHEFT:
                planner = new DheftPlanningAlgorithm(planningContext);
                break;
            case PSO:
                planner = new PSOPlanningAlgorithm();
                break;
            case LOCAL_HEFT:
                planner = new LocalHeftPlanningAlgorithm(planningContext);
                break;
            case LOCAL_CPOP:
                planner = new LocalCpopPlanningAlgorithm(planningContext);
                break;
            case LOCAL_PEFT:
                planner = new LocalPeftPlanningAlgorithm(planningContext);
                break;
            default:
                planner = null;
                break;
        }
        return planner;
    }

    private void processImpactFactors(List<Task> taskList) {
        List<Task> exits = new ArrayList<>();
        for (Task task : taskList) {
            if (task.getChildList().isEmpty()) {
                exits.add(task);
            }
        }
        double avg = 1.0 / exits.size();
        for (Task task : exits) {
            propagateImpactFrom(task, avg);
        }
    }

    private void propagateImpactFrom(Task start, double impact) {
        java.util.Deque<ImpactFrame> stack = new java.util.ArrayDeque<>();
        stack.push(new ImpactFrame(start, impact));
        while (!stack.isEmpty()) {
            ImpactFrame frame = stack.peek();
            if (!frame.applied) {
                frame.applied = true;
                frame.task.setImpact(frame.task.getImpact() + frame.impact);
                frame.parentCount = frame.task.getParentList().size();
                frame.parentShare = frame.parentCount > 0 ? frame.impact / frame.parentCount : 0.0;
            }
            if (frame.parentCount == 0 || frame.nextParentIndex >= frame.parentCount) {
                stack.pop();
                continue;
            }
            Task parent = frame.task.getParentList().get(frame.nextParentIndex);
            frame.nextParentIndex++;
            stack.push(new ImpactFrame(parent, frame.parentShare));
        }
    }

    private static final class ImpactFrame {

        final Task task;

        final double impact;

        private int nextParentIndex;

        private int parentCount;

        private double parentShare;

        private boolean applied;

        ImpactFrame(Task task, double impact) {
            this.task = task;
            this.impact = impact;
        }
    }

    protected void processOtherEvent(SimEvent ev) {
        if (ev == null) {
            Log.printLine(getName() + ".processOtherEvent(): " + "Error - an event is null.");
            return;
        }

        Log.printLine(getName() + ".processOtherEvent(): " + "Error - event unknown by this DatacenterBroker.");
    }

    protected void finishExecution() {}

    @Override
    public void shutdownEntity() {
        Log.printLine(getName() + " is shutting down...");
    }

    @Override
    public void startEntity() {
        Log.printLine("Starting WorkflowSim " + Parameters.getVersion());
        Log.printLine(getName() + " is starting...");
        schedule(getId(), 0, WorkflowSimTags.START_SIMULATION);
    }

    @SuppressWarnings("unchecked")
    public List<Task> getTaskList() {
        return (List<Task>) taskList;
    }

    public List<Task> getParsedTaskSnapshot() {
        return parsedTaskSnapshot;
    }

    public SharedStorageDagPlanTrace getSharedStorageDagPlanTrace() {
        return sharedStorageDagPlanTrace;
    }

    protected void setTaskList(List<Task> taskList) {
        this.taskList = taskList;
    }
}
