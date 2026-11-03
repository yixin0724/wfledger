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
import org.workflowsim.planning.LocalCpopPlanningAlgorithm;
import org.workflowsim.planning.LocalHeftPlanningAlgorithm;
import org.workflowsim.planning.LocalPeftPlanningAlgorithm;
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
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;

/**
 * Parsing and planning entry-point entity of the workflow simulation.
 *
 * <p>A simulation starts at this entity: it parses the input task DAG, optionally runs offline
 * planning according to the configuration, computes the impact factor consumed by balanced
 * clustering, and then hands the tasks to {@link ClusteringEngine}. The planner owns only the
 * static task-to-VM mapping or the static order decision; the actual dispatch is still performed
 * by the workflow engine and the scheduler.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 *
 */
public final class WorkflowPlanner extends SimEntity {

    /** Tasks currently waiting for planning and clustering. */
    protected List<Task> taskList;
    /** Current workflow input parser. */
    protected WorkflowParser parser;
    /** Entity ID of the downstream clustering engine. */
    private int clusteringEngineId;

    private ClusteringEngine clusteringEngine;
    private SimulationEventRecorder eventRecorder = SimulationEventRecorder.disabled();
    private PlanningContext planningContext;
    private List<Task> parsedTaskSnapshot = Collections.emptyList();
    private SharedStorageDagPlanTrace sharedStorageDagPlanTrace;
    /** Optional task-by-VM heterogeneous execution cost matrix; when null the platform keeps MI/mips scaling. */
    private org.workflowsim.utils.TaskCostMatrix taskCostMatrix;

    private org.workflowsim.data.v2.CoherentDataflowRuntime coherentDataflow;

    /** @param value run-owned V2 coordinator; installed before parsing */
    public void setCoherentDataflowRuntime(org.workflowsim.data.v2.CoherentDataflowRuntime value) {
        if (value == null || coherentDataflow != null) {
            throw new IllegalArgumentException("V2 runtime must be installed once");
        }
        coherentDataflow = value;
    }

    /**
     * Creates a workflow planner entity that uses a single runtime scheduler.
     *
     * @param name CloudSim entity name
     * @throws Exception when the CloudSim entity cannot be created
     */
    public WorkflowPlanner(String name) throws Exception {
        this(name, 1);
    }

    /**
     * Creates a workflow planner entity together with its downstream clustering and execution
     * chain.
     *
     * @param name CloudSim entity name
     * @param schedulers number of downstream runtime schedulers
     * @throws Exception when the CloudSim entity cannot be created
     */
    public WorkflowPlanner(String name, int schedulers) throws Exception {
        super(name);

        setTaskList(new ArrayList<>());
        this.clusteringEngine = new ClusteringEngine(name + "_Merger_", schedulers);
        this.clusteringEngineId = this.clusteringEngine.getId();
        this.parser =
                new WorkflowParser(getClusteringEngine().getWorkflowEngine().getSchedulerId(0));
    }

    /** @return CloudSim entity ID of the downstream clustering engine */
    public int getClusteringEngineId() {
        return this.clusteringEngineId;
    }

    /** @return downstream clustering engine */
    public ClusteringEngine getClusteringEngine() {
        return this.clusteringEngine;
    }

    /** @return workflow input parser */
    public WorkflowParser getWorkflowParser() {
        return this.parser;
    }

    /** @return CloudSim entity ID of the workflow engine */
    public int getWorkflowEngineId() {
        return getClusteringEngine().getWorkflowEngineId();
    }

    /** @return downstream workflow engine */
    public WorkflowEngine getWorkflowEngine() {
        return getClusteringEngine().getWorkflowEngine();
    }

    /**
     * Install the event recorder owned by this run and propagate it to the downstream execution
     * chain.
     *
     * @param value event recorder of this run
     */
    public void setEventRecorder(SimulationEventRecorder value) {
        if (value == null) {
            throw new IllegalArgumentException("Event recorder cannot be null");
        }
        this.eventRecorder = value;
        getClusteringEngine().setEventRecorder(value);
    }

    /**
     * Provide the context required by planners that model platform storage.
     *
     * @param value explicit planning context
     */
    public void setPlanningContext(PlanningContext value) {
        if (value == null) {
            throw new IllegalArgumentException("Planning context cannot be null");
        }
        this.planningContext = value;
    }

    /**
     * Provide the optional task-by-VM heterogeneous execution cost matrix (supports paper
     * reproduction).
     *
     * <p>After parsing, the matrix is projected onto every logical task: planners read the task
     * projection to estimate cost, and STATIC dispatch converts it before submission as
     * {@code MI = round(seconds * vm.mips)}. When it is not set, the platform keeps the existing
     * MI/mips scaling behavior.</p>
     *
     * @param value task-by-VM execution cost matrix; null clears the projection
     */
    public void setTaskCostMatrix(org.workflowsim.utils.TaskCostMatrix value) {
        this.taskCostMatrix = value;
    }

    /**
     * Process a CloudSim event in the planning lifecycle.
     *
     * @param ev received event
     */
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
                // R5 dynamic arrival: register the submission instant and the task attribution of
                // every input with the engine; when all instants are t=0, the engine behavior is
                // bit-identical to the historical single-instant submission.
                getWorkflowEngine()
                        .setWorkflowArrivals(
                                Parameters.getWorkflowArrivalSeconds(),
                                getWorkflowParser().getTaskWorkflowIndices());
                sendNow(getClusteringEngineId(), WorkflowSimTags.JOB_SUBMIT, getTaskList());
                break;
            case CloudSimTags.END_OF_SIMULATION:
                shutdownEntity();
                break;
                // An unrecognized event always enters explicit error handling instead of being
                // dropped silently.
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
            // R8 audit fix (P1-7): a configuration exception must not be wrapped into the
            // execution semantics of "planning algorithm failed".
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Planning algorithm " + Parameters.getPlanningAlgorithm() + " failed", exception);
        }
    }

    /**
     * Validate failure model coordinate coverage after parsing has written the actual DAG depths
     * but before any task is planned or submitted.
     *
     * <p>The real VM IDs declared by the platform are used instead of VM list indices, so a
     * configuration error with non-contiguous IDs terminates before any Job is dispatched.</p>
     */
    private void validateFailureModelCoverage() {
        List<Integer> vmIds = new ArrayList<Integer>();
        for (Vm vm : getWorkflowEngine().getAllVmList()) {
            vmIds.add(Integer.valueOf(vm.getId()));
        }
        FailureParameters.validateRuntimeCoverage(getTaskList(), vmIds);
    }

    /**
     * Project the optional task-by-VM cost matrix onto every parsed logical task.
     *
     * <p>The matrix must cover every combination of task ID and VM ID: a missing entry fails fast
     * before any Job is dispatched, with no silent fallback. After the projection, planners can
     * read {@code task.getVmExecutionCostSeconds(vmId)} to estimate cost.</p>
     */
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

    /**
     * Create the planning algorithm implementation selected by the configuration.
     *
     * <p>The mapping from enum value to implementation is part of the experiment protocol. A
     * shared-storage DAG planner receives an explicit {@link PlanningContext}. R9 (2026-09-16)
     * removed the historical HEFT/DHEFT dispatch branches that did not align with the execution
     * model; static DAG planning always uses the maintained SHARED_STORAGE_* or LOCAL_* series.</p>
     *
     * @param name planning algorithm enum
     * @return corresponding planning algorithm; {@code null} for an invalid or unknown enum
     */
    private BasePlanningAlgorithm getPlanningAlgorithm(PlanningAlgorithm name) {
        BasePlanningAlgorithm planner;

        // Adding a planner requires keeping the reportable enum contract in Parameters in sync.
        switch (name) {
                // INVALID never reaches this method; keep null to preserve the historical
                // fail-fast boundary.
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

    /**
     * Compute the impact factor of every task for the task-balanced clustering algorithm.
     *
     * <p>Every exit task receives the same initial impact, which is propagated back along the
     * parent edges in equal shares. This value is an internal feature of the clustering heuristic;
     * it is not a critical-path length, a real-world importance, or an experimental metric.</p>
     *
     * @param taskList all tasks
     */
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

    /**
     * Accumulate the impact into one task and propagate it back along the parent edges in equal
     * shares.
     *
     * <p>Review engineering F-004 fix (2026-10-19): the historical implementation <b>recursed</b>
     * along the parent edges, so the call-stack depth grew with the DAG depth and a
     * {@code StackOverflowError} was measured on a pure chain 50 000 deep (regression test
     * {@code PlannerImpactDeepChainTest}). This implementation simulates the same recursion with
     * an explicit frame stack: <b>the call sequence and the floating-point accumulation order are
     * preserved bit for bit</b> -- an activated frame performs its own accumulation once, then
     * pushes its parent frames one by one in the declaration order of {@code parentList} and only
     * continues with the next parent frame after the whole parent-chain subtree of the current one
     * is done, exactly matching the recursive call order; the graph is guaranteed acyclic by
     * {@link WorkflowDagValidator}.</p>
     *
     * @param start task that receives the initial impact
     * @param impact impact to accumulate and propagate
     */
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

    /**
     * Single impact propagation frame: on activation it accumulates once, then drives its parent
     * frames in declaration order.
     */
    private static final class ImpactFrame {
        /** Task that receives the impact of this frame. */
        final Task task;
        /** Impact share to accumulate into that task. */
        final double impact;
        /** Number of parent edges already dispatched downward. */
        private int nextParentIndex;
        /** Total parent edge count; computed on first activation. */
        private int parentCount;
        /** Share per parent edge (impact / parentCount); 0 when there is no parent edge. */
        private double parentShare;
        /** Whether the frame has performed its own accumulation (exactly once per frame). */
        private boolean applied;

        ImpactFrame(Task task, double impact) {
            this.task = task;
            this.impact = impact;
        }
    }

    /**
     * Process an unknown event and log the error.
     *
     * @param ev event not recognized by the current planner
     */
    protected void processOtherEvent(SimEvent ev) {
        if (ev == null) {
            Log.printLine(getName() + ".processOtherEvent(): " + "Error - an event is null.");
            return;
        }

        Log.printLine(getName() + ".processOtherEvent(): " + "Error - event unknown by this DatacenterBroker.");
    }

    /**
     * Reserved end-of-simulation notification hook; the current historical implementation sends
     * no extra event.
     */
    protected void finishExecution() {
        // sendNow(getId(), CloudSimTags.END_OF_SIMULATION);
    }

    /** Shut down the planning entity. */
    @Override
    public void shutdownEntity() {
        Log.printLine(getName() + " is shutting down...");
    }

    /** Start the planning entity and schedule the initial workflow parse event. */
    @Override
    public void startEntity() {
        Log.printLine("Starting WorkflowSim " + Parameters.getVersion());
        Log.printLine(getName() + " is starting...");
        schedule(getId(), 0, WorkflowSimTags.START_SIMULATION);
    }

    /** @return current task list waiting for planning or clustering */
    @SuppressWarnings("unchecked")
    public List<Task> getTaskList() {
        return (List<Task>) taskList;
    }

    /**
     * Return the parsed task-graph snapshot saved before clustering consumes the mutable list.
     *
     * <p>The {@link Task} objects in the snapshot are still the actual simulation objects, so a
     * report captures their final task-level outcome state rather than a deep copy of the input
     * data.</p>
     *
     * @return unmodifiable snapshot of the parsed task list
     */
    public List<Task> getParsedTaskSnapshot() {
        return parsedTaskSnapshot;
    }

    /**
     * Return the complete static DAG plan trace produced by the selected planner.
     *
     * @return static DAG plan trace; {@code null} when the corresponding planner was not used
     */
    public SharedStorageDagPlanTrace getSharedStorageDagPlanTrace() {
        return sharedStorageDagPlanTrace;
    }

    /**
     * Replace the current task list.
     *
     * @param taskList new task list
     */
    protected void setTaskList(List<Task> taskList) {
        this.taskList = taskList;
    }
}
