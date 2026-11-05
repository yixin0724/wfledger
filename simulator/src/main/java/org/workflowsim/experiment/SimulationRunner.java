package org.workflowsim.experiment;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.exception.PlatformException;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.planning.PlanningContext;
import org.workflowsim.platform.PlatformFactory;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.ClusteringParameters.ClusteringMethod;
import org.workflowsim.utils.Parameters.CostModel;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

/**
 * Runs the maintained standard WorkflowSim pipeline from an explicit configuration and platform
 * description.
 */
public final class SimulationRunner {

    /**
     * Executes one parse, clustering, planning, scheduling, and CloudSim event-processing pass, and
     * returns the immutable report before global state is released.
     *
     * @param config explicit simulation configuration covering inputs, algorithms, randomness, and
     *        model selection
     * @param platform platform description whose capacity preflight has already completed
     * @return complete immutable evidence report for this run
     * @throws SimulationConfigurationException when a precondition of the configuration, the
     *         platform, or the maintained execution model is not met (unchecked)
     * @throws PlatformException when platform resource configuration or VM-to-Host placement fails
     *         (unchecked)
     * @throws SimulationExecutionException when parsing, CloudSim execution, or report capture fails
     */
    public SimulationReport run(SimulationConfig config, PlatformProfile platform) throws SimulationExecutionException {
        if (config == null || platform == null) {
            throw new SimulationConfigurationException("Configuration and platform are required");
        }
        validateSupportedAlgorithms(config, platform);
        validateSupportedFailureModel(config);
        validateSupportedExecutionModel(config, platform);
        if (config.getVmCount() != platform.getVms().size()) {
            throw new SimulationConfigurationException("Configuration VM count " + config.getVmCount()
                    + " does not match platform VM count " + platform.getVms().size());
        }
        validateCostModel(config, platform);
        if (config.getDataMovementModel().isCoherentStorageDataflowV3() != (platform.getSourceStorage() != null)) {
            throw new SimulationConfigurationException(
                    "Bounded sourceStorage must be present exactly for the storage V3 dataflow model");
        }
        // BAT-D2 (2026-10-29): DVFS frequency-tier dimension precondition
        // (docs/advanced/DVFS_DIMENSION_DESIGN.md section 2.2).
        validateFrequencyDimension(config, platform);
        // C1 (2026-11-02 review): declared precondition of the carbon deferral engine, a rule
        // of the same family as the frequency-tier policy -- on a platform without windows the
        // deferral engine degrades silently to FCFS, and "believing we measure time shifting
        // while actually running ordinary online scheduling" is misleading evidence, so the
        // configuration must be rejected explicitly.
        validateCarbonDeferralDimension(config, platform);
        // Fat-tree link contention model: assemble the topology outside the session and validate
        // the bidirectional contract between model and topology declaration (a configuration
        // exception is raised here directly as SimulationConfigurationException and is not
        // wrapped by the execution stage; topology construction is pure configuration
        // computation and does not touch CloudSim state).
        org.workflowsim.network.FatTreeTopology fatTreeTopology = prepareFatTreeTopology(config, platform);

        try (SimulationSession session = SimulationSession.open(config)) {
            // C1 (2026-11-02): green budget windows are loaded with the session (the source of
            // truth for carbon-aware deferred scheduling). Outside a session the list is empty;
            // nothing may remain across runs (Parameters.reset already zeroes it).
            org.workflowsim.utils.Parameters.setCarbonWindows(platform.getCarbonWindows());
            return executeWithinSession(session, config, platform, fatTreeTopology);
        } catch (SimulationExecutionException e) {
            throw e;
        } catch (org.workflowsim.failure.RetryLimitExceededException e) {
            // RetryLimitExceededException is a specific runtime exception and must be rethrown
            // directly so that tests can catch it
            throw e;
        } catch (IllegalStateException e) {
            // IllegalStateException may come from the failure generator or other validation
            // logic and must be rethrown directly
            throw e;
        } catch (SimulationConfigurationException e) {
            // R8 audit fix (P1-2): a configuration exception must not degrade into an
            // execution exception -- the Javadoc contract declares
            // @throws SimulationConfigurationException, and callers that branch by type rely
            // on that guarantee.
            throw e;
        } catch (IllegalArgumentException e) {
            // R8 audit fix (P1-2): parse/validation IAEs (including WorkflowValidationException)
            // pass through unchanged, so the root-cause message reaches the caller and is no
            // longer wrapped as "Unexpected error".
            throw e;
        } catch (Exception e) {
            // Wrap any other unexpected exception as SimulationExecutionException
            throw new SimulationExecutionException("Unexpected error during simulation execution", e);
        }
    }

    /**
     * Assembly stage inside the run() session: datacenter/planner/engine/vm/coherent assembly
     * (moved verbatim during the refactor).
     */
    private SimulationAssembly assembleSession(
            SimulationSession session,
            SimulationConfig config,
            PlatformProfile platform,
            SimulationEventRecorder events,
            org.workflowsim.network.FatTreeTopology fatTreeTopology)
            throws Exception {
        session.initializeCloudSim(1, Calendar.getInstance(), false);
        WorkflowDatacenter datacenter = PlatformFactory.createDatacenter("datacenter-0", platform);
        datacenter.setEventRecorder(events);
        datacenter.setDataMovementModel(config.getDataMovementModel());
        datacenter.setNetworkEvidenceConfig(config.getNetworkEvidenceConfig());
        if (fatTreeTopology != null) {
            datacenter.setFatTreeTopology(fatTreeTopology);
        }
        WorkflowPlanner planner = new WorkflowPlanner("planner-0", 1);
        planner.setEventRecorder(events);
        planner.setPlanningContext(new PlanningContext(config, platform));
        planner.setTaskCostMatrix(config.getTaskCostMatrix());
        WorkflowEngine engine = planner.getWorkflowEngine();
        org.workflowsim.data.v2.CoherentDataflowRuntime coherent = null;
        if (config.getDataMovementModel().usesCoherentDataflowRuntime()) {
            int budget = config.getNetworkEvidenceConfig().isEnabled()
                    ? config.getNetworkEvidenceConfig().getMaxTraceRecords()
                    : 0;
            coherent = config.getDataMovementModel().isCoherentStorageDataflowV3()
                    ? org.workflowsim.data.v2.CoherentDataflowRuntime.withStorage(
                            config.getDataMovementModel().usesSharedDataflowResources(),
                            budget,
                            config.getFileSystem() == org.workflowsim.utils.ReplicaCatalog.FileSystem.SHARED)
                    : new org.workflowsim.data.v2.CoherentDataflowRuntime(
                            config.getDataMovementModel().isCoherentShared(), budget);
            if (platform.getSourceStorage() != null) {
                datacenter.setSourceStorage(platform.getSourceStorage());
            }
            planner.setCoherentDataflowRuntime(coherent);
            engine.setCoherentDataflowRuntime(coherent);
            datacenter.setCoherentDataflowRuntime(coherent, engine.getId());
            engine.setDataflowAssignmentConfig(config.getDataflowAssignmentConfig());
            if (config.getDataflowAssignmentConfig().isEnabled()) {
                engine.getScheduler(0).enableDataflowBindingGuard();
            }
        }
        List<CondorVM> vms = PlatformFactory.createVms(platform, engine.getSchedulerId(0));
        engine.submitVmList(vms, 0);
        engine.bindSchedulerDatacenter(datacenter.getId(), 0);
        SimulationAssembly assembly = new SimulationAssembly();
        assembly.datacenter = datacenter;
        assembly.planner = planner;
        assembly.engine = engine;
        assembly.coherent = coherent;
        return assembly;
    }

    /**
     * Execution/validation/capture stage inside the run() session (the PLAT-14 watchdog and the
     * evidence snapshot, moved verbatim during the refactor).
     */
    private SimulationReport executeWithinSession(
            SimulationSession session,
            SimulationConfig config,
            PlatformProfile platform,
            org.workflowsim.network.FatTreeTopology fatTreeTopology)
            throws Exception {
        // AUD-F07 follow-up (2026-11-04, post-completion review): the retention cap is run
        // configuration (SimulationConfig builder / workbench simulation.eventRecorderCap), not
        // a Java-only constructor argument -- the only enabled-recorder construction site.
        SimulationEventRecorder events = new SimulationEventRecorder(config.getEventRecorderCap());
        SimulationAssembly assembly = assembleSession(session, config, platform, events, fatTreeTopology);
        WorkflowDatacenter datacenter = assembly.datacenter;
        WorkflowPlanner planner = assembly.planner;
        WorkflowEngine engine = assembly.engine;
        org.workflowsim.data.v2.CoherentDataflowRuntime coherent = assembly.coherent;

        double makespan = CloudSim.startSimulation();
        // PLAT-14: stall watchdog. The simulation ended naturally but the engine still holds
        // unreleased or in-flight Jobs, which means the event chain broke and the workflow
        // stalled silently (no further event drives release/return). This must fail explicitly
        // instead of producing a truncated report that carries only the INCOMPLETE_LOGICAL_TASKS
        // marker.
        // Legal abort paths such as RetryLimitExceededException propagate as exceptions and
        // never reach this check.
        if (!engine.getJobsList().isEmpty() || engine.getInFlightJobCount() != 0) {
            throw new SimulationExecutionException("Simulation terminated while the workflow engine "
                    + "still holds " + engine.getJobsList().size() + " unreleased job(s) and "
                    + engine.getInFlightJobCount() + " in-flight job(s): the event chain stalled "
                    + "silently and the run would produce incomplete evidence");
        }
        if (coherent != null && !coherent.isQuiescent()) {
            throw new SimulationExecutionException(
                    "Simulation stopped before V2 file service and requested Jobs became quiescent");
        }
        List<Job> completedJobs = engine.getJobsReceivedList();
        Map<Integer, Integer> actualVmHostAssignments = engine.getScheduler(0).getCreatedVmHostAssignments();
        if (!platform.getVmHostAssignments().equals(actualVmHostAssignments)) {
            throw new PlatformException("CloudSim VM-to-Host allocation " + actualVmHostAssignments
                    + " does not match preflighted platform placement "
                    + platform.getVmHostAssignments());
        }
        // F-006 contract revision (2026-10-23, user-adjudicated): a naturally completed run
        // must terminate its event stream with the termination clock so the makespan family
        // stays event-recomputable even when a positive post-delay tail follows the last
        // JOB_RETURNED. Aborted and failed runs deliberately pass this point without the
        // marker; absence of the event therefore identifies an incomplete execution.
        events.record(
                SimulationEventType.SIMULATION_TERMINATED,
                makespan,
                null,
                SimulationEventRecorder.attributes("simulationEndSeconds", Double.valueOf(makespan)));
        try {
            return SimulationReport.capture(
                    config,
                    platform,
                    makespan,
                    planner.getWorkflowParser().getInputReports(),
                    completedJobs,
                    events.snapshot(),
                    planner.getParsedTaskSnapshot(),
                    planner.getSharedStorageDagPlanTrace(),
                    actualVmHostAssignments,
                    datacenter.captureNetworkEvidence(),
                    datacenter.captureFileLifecycleEvidence(),
                    coherent == null ? null : coherent.getFilePlanDocument(),
                    datacenter.captureStorageLifecycleEvidence());
        } catch (IOException exception) {
            throw new SimulationExecutionException(
                    "Simulation completed but its evidence record could not be created", exception);
        }
    }

    /** Private carrier for the run() session assembly (fields are read directly, no forwarding). */
    private static final class SimulationAssembly {
        private WorkflowDatacenter datacenter;
        private WorkflowPlanner planner;
        private WorkflowEngine engine;
        private org.workflowsim.data.v2.CoherentDataflowRuntime coherent;
    }

    /**
     * The standard runner accepts only algorithms with a maintained semantic contract.
     * Historical examples may still use the old static API, but they must not silently generate
     * research evidence through this entry point.
     */
    private static void validateSupportedAlgorithms(SimulationConfig config, PlatformProfile platform) {
        if (config.getRegisteredSchedulingAlgorithmProvider() != null) {
            // BAT-3 (2026-10-26): the registry track goes through the self-declared contract
            // matrix; the enum planning/scheduling algorithms must both be INVALID.
            if (config.getSchedulingAlgorithm() != SchedulingAlgorithm.INVALID
                    || (config.getPlanningAlgorithm() != null
                            && config.getPlanningAlgorithm()
                                    != org.workflowsim.utils.Parameters.PlanningAlgorithm.INVALID)) {
                throw new SimulationConfigurationException(
                        "A registry-based scheduling algorithm occupies the plain online"
                                + " scheduling layer: schedulingAlgorithm and planningAlgorithm"
                                + " must both be INVALID");
            }
            org.workflowsim.experiment.registry.AlgorithmRegistry.validate(
                    config.getRegisteredSchedulingAlgorithmProvider().label(),
                    new org.workflowsim.experiment.registry.AlgorithmRegistry.ValidationInput(
                            config.getFileSystem(),
                            config.getDataMovementModel(),
                            config.getTaskCostMatrix() != null
                                    && !config.getTaskCostMatrix().asMap().isEmpty(),
                            config.getFailureModel(),
                            config.getPlanningAlgorithm(),
                            // BAT-D6 (2026-10-29): on the registry track the power fact comes from the
                            // same source as the energy chain (the BAT-D2 powered predicate) -- a
                            // declaration of either flat power fields or a frequency-tier table
                            // makes the VM powered, so a powerModelRequired declaration on a
                            // tiered platform is no longer wrongly rejected by the flat-only
                            // reading.
                            anyVmPowered(platform),
                            // BAT-D4 (2026-10-29): the frequency-tier fact of the registry track is injected
                            // on the same interface (the gate clause of section 2.6).
                            anyVmHasFrequencyTiers(platform)));
            return;
        }
        if (!AlgorithmCatalog.isSupportedBySimulationRunner(config.getSchedulingAlgorithm())) {
            if (config.getSchedulingAlgorithm() == SchedulingAlgorithm.INVALID) {
                throw new SimulationConfigurationException(
                        "SchedulingAlgorithm.INVALID is not supported by SimulationRunner");
            }
            throw new SimulationConfigurationException("Scheduling algorithm "
                    + config.getSchedulingAlgorithm() + " is a legacy compatibility label and is "
                    + "not supported by SimulationRunner; use its READY_BATCH_* replacement");
        }
        if (!AlgorithmCatalog.isSupportedBySimulationRunner(config.getPlanningAlgorithm())) {
            throw new SimulationConfigurationException("Planning algorithm "
                    + config.getPlanningAlgorithm() + " is legacy mapping-only code and is not "
                    + "supported by SimulationRunner; use SHARED_STORAGE_HEFT, SHARED_STORAGE_CPOP, or "
                    + "SHARED_STORAGE_DLS, SHARED_STORAGE_ETF, SHARED_STORAGE_PEFT, "
                    + "CAWOSCHED_SLACK, or CAWOSCHED_PRESSURE under their controlled model");
        }
    }

    private static void validateCostModel(SimulationConfig config, PlatformProfile platform) {
        if (config.getCostModel() != CostModel.VM) {
            return;
        }
        for (PlatformProfile.VmSpec vm : platform.getVms()) {
            if (!vm.hasCosts()) {
                throw new SimulationConfigurationException(
                        "CostModel.VM requires explicit pricing for every VM; " + "VM " + vm.getId() + " has none");
            }
        }
    }

    /**
     * Assembles the Fat-tree topology and validates its bidirectional contract with the data
     * movement model.
     *
     * <p>With {@code fatTreeContentionV1()} the topology must be declared through
     * {@code PlatformProfile.networkTopology(...)}; coherent V2/V3 may optionally declare a
     * topology and then use the actual placement paths. Other models reject a superfluous
     * topology so that a declaration is never ignored silently.</p>
     *
     * @param config simulation configuration
     * @param platform platform description
     * @return the placed topology; {@code null} when the model does not use a topology
     * @throws SimulationConfigurationException when either direction of the bidirectional contract
     *         is not satisfied
     */
    private static org.workflowsim.network.FatTreeTopology prepareFatTreeTopology(
            SimulationConfig config, PlatformProfile platform) {
        boolean coherent = config.getDataMovementModel().usesCoherentDataflowRuntime();
        if (config.getDataMovementModel().isFatTreeContentionV1()
                || (coherent && platform.getNetworkTopology() != null)) {
            if (platform.getNetworkTopology() == null) {
                throw new SimulationConfigurationException(
                        config.getDataMovementModel().getKind()
                                + " requires PlatformProfile.builder(...).networkTopology("
                                + "NetworkTopologySpec.fatTree(...)) so VM-to-VM transfers can be "
                                + "routed along deterministic fat-tree paths");
            }
            if (coherent) {
                int k = platform.getNetworkTopology().getK();
                double bytes = platform.getNetworkTopology().getLinkBandwidthMbPerSecond() * 1_000_000.0;
                if (k < 2 || k > 32 || k % 2 != 0 || !Double.isFinite(bytes) || bytes < Double.MIN_NORMAL) {
                    throw new SimulationConfigurationException(
                            "Coherent V2 supports even Fat-tree k in [2,32] and normal finite converted B/s"
                                    + "capacities");
                }
            }
            List<Integer> hostIds = new java.util.ArrayList<Integer>();
            for (PlatformProfile.HostSpec host : platform.getHosts()) {
                hostIds.add(Integer.valueOf(host.getId()));
            }
            return org.workflowsim.network.FatTreeTopology.fromSpec(platform.getNetworkTopology(), hostIds);
        }
        if (platform.getNetworkTopology() != null) {
            throw new SimulationConfigurationException("Platform declares a network topology but the "
                    + "data movement model " + config.getDataMovementModel().getKind()
                    + " does not use it; declare the topology only with "
                    + "DataMovementModel.fatTreeContentionV1()");
        }
        return null;
    }

    /**
     * In the standard runner only the no-op reclustering has an execution-level retry contract.
     * The other historical reclustering heuristics still contain hard-coded, unverified tuning
     * rules, so they are reachable only through the old API.
     */
    private static void validateSupportedFailureModel(SimulationConfig config) {
        if (config.getFailureModel().getClusteringAlgorithm()
                != FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP) {
            throw new SimulationConfigurationException("Failure reclustering algorithm "
                    + config.getFailureModel().getClusteringAlgorithm()
                    + " is legacy compatibility code and is not supported by SimulationRunner; "
                    + "use FTCLUSTERING_NOOP for the verified retry model");
        }
        if (config.getFailureModel().getMonitorMode() != FailureParameters.FTCMonitor.MONITOR_NONE) {
            throw new SimulationConfigurationException("SimulationRunner supports MONITOR_NONE only: under the "
                    + "verified FTCLUSTERING_NOOP retry model, other monitor modes do not alter recovery "
                    + "or scheduling and must not be treated as a research factor");
        }
    }

    /**
     * The maintained research entry point supports a single controlled execution model.
     * Historical clustering and time-shared VM modes can still be constructed for API
     * compatibility, but their interaction with the ready-Job scheduler, task results, and
     * metrics carries no standard-runner regression contract.
     */
    private static void validateSupportedExecutionModel(SimulationConfig config, PlatformProfile platform) {
        if (config.getClusteringParameters().getClusteringMethod() != ClusteringMethod.NONE) {
            throw new SimulationConfigurationException("Task clustering method "
                    + config.getClusteringParameters().getClusteringMethod()
                    + " is not supported by SimulationRunner; use clustering method NONE for "
                    + "the verified task-level execution model");
        }
        for (PlatformProfile.VmSpec vm : platform.getVms()) {
            if (vm.getSchedulerMode() != PlatformProfile.CloudletSchedulerMode.SPACE_SHARED) {
                throw new SimulationConfigurationException("VM " + vm.getId() + " uses "
                        + vm.getSchedulerMode() + "; SimulationRunner supports SPACE_SHARED VMs "
                        + "only for the verified task-level execution model");
            }
        }
    }

    /** BAT-E3 (2026-10-27): @return whether the platform profile has any VM declaring power fields */
    private static boolean anyVmHasPowerModel(PlatformProfile platform) {
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.hasPowerModel()) {
                return true;
            }
        }
        return false;
    }

    /**
     * BAT-D6 (2026-10-29): @return whether the platform profile has any "powered" VM -- a
     * declaration of either flat power fields or a frequency-tier table (the BAT-D2 powered
     * predicate, which shares its source with the power gate of
     * {@code EnergyAccounting.fromFrozenMetrics}: under the single-source-of-truth structure
     * where the tier table replaces the flat fields, declaring tiers is what produces a real
     * energy ledger). The powerModelRequired gate of the registry track consumes this reading.
     */
    private static boolean anyVmPowered(PlatformProfile platform) {
        return anyVmHasPowerModel(platform) || anyVmHasFrequencyTiers(platform);
    }

    /** BAT-D4 (2026-10-29): @return whether the platform profile has any VM declaring frequency tiers */
    private static boolean anyVmHasFrequencyTiers(PlatformProfile platform) {
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.hasFrequencyTiers()) {
                return true;
            }
        }
        return false;
    }

    /**
     * BAT-D2 (2026-10-29): run-level preconditions of the DVFS frequency-tier dimension.
     *
     * <p>(1) A non-DEFAULT policy requires at least one VM on the platform to declare a frequency
     * tier table -- on an all-plain platform MIN_POWER/EDP_HEURISTIC would be ignored silently,
     * producing the misleading outcome of "believing we study DVFS while actually running v1";
     * (2) the task-by-VM cost matrix and the frequency tier table are mutually exclusive: the
     * STATIC dispatch path re-derives the Job length at datacenter submission from the
     * matrix-converted MI ({@code projectComputeExecutionLength}), which overwrites the
     * tier-scaled length computed at dispatch and silently discards the frequency reduction (the
     * same family as the R8 audit P1-1 matrix-versus-planner fork). Both cases are rejected
     * explicitly.</p>
     */
    /**
     * C1 (2026-11-02): declared precondition of the carbon-aware deferral engine.
     *
     * <p>Every decision of {@code CARBON_WINDOW_DEFER} comes from the {@code carbonWindows}
     * declared by the platform; without windows it degrades truthfully to ordinary online
     * scheduling while the label still says "carbon-aware" -- exactly the same class of problem as
     * the BAT-D2 "silently inert policy" (believing we study X while actually running v1). It is
     * therefore rejected explicitly, so that the caller either supplies windows or changes the
     * label instead of producing evidence whose name does not match its content.</p>
     */
    private static void validateCarbonDeferralDimension(SimulationConfig config, PlatformProfile platform) {
        if (config.getSchedulingAlgorithm() != SchedulingAlgorithm.CARBON_WINDOW_DEFER) {
            return;
        }
        if (platform.getCarbonWindows() == null || platform.getCarbonWindows().isEmpty()) {
            throw new SimulationConfigurationException("SchedulingAlgorithm.CARBON_WINDOW_DEFER requires a platform"
                    + " with declared carbonWindows (the budget model); without them the deferral engine is"
                    + " silently inert and would label an ordinary online run as carbon-aware (C1)");
        }
    }

    private static void validateFrequencyDimension(SimulationConfig config, PlatformProfile platform) {
        boolean anyTieredVm = false;
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.hasFrequencyTiers()) {
                anyTieredVm = true;
                break;
            }
        }
        if (config.getFrequencyPolicy() != SimulationConfig.FrequencyPolicy.DEFAULT && !anyTieredVm) {
            throw new SimulationConfigurationException(
                    "FrequencyPolicy " + config.getFrequencyPolicy() + " requires at least one VM with "
                            + "declared frequencyTiers; on an all-plain platform the policy would be "
                            + "silently inert (BAT-D2)");
        }
        if (config.getTaskCostMatrix() != null && anyTieredVm) {
            throw new SimulationConfigurationException("A task cost matrix cannot be combined with frequency"
                    + " tiers: the STATIC dispatch path re-derives Job MI from matrix seconds at datacenter"
                    + " submission (projectComputeExecutionLength) and would silently discard the "
                    + "tier-scaled length (BAT-D2)");
        }
    }
}
