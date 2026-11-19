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

public final class SimulationRunner {

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
        if (!config.isEventRecordingEnabled()
                && config.getNetworkEvidenceConfig().isEnabled()) {
            throw new SimulationConfigurationException(
                    "Lifecycle evidence requires event recording; explicitly disable both or enable both");
        }
        if (config.getDataMovementModel().isCoherentStorageDataflowV3() != (platform.getSourceStorage() != null)) {
            throw new SimulationConfigurationException(
                    "Bounded sourceStorage must be present exactly for the storage V3 dataflow model");
        }

        validateFrequencyDimension(config, platform);

        validateCarbonDeferralDimension(config, platform);

        org.workflowsim.network.FatTreeTopology fatTreeTopology = prepareFatTreeTopology(config, platform);

        try (SimulationSession session = SimulationSession.open(config)) {

            org.workflowsim.utils.Parameters.setCarbonWindows(platform.getCarbonWindows());
            return executeWithinSession(session, config, platform, fatTreeTopology);
        } catch (SimulationExecutionException e) {
            throw e;
        } catch (org.workflowsim.failure.RetryLimitExceededException e) {

            throw e;
        } catch (IllegalStateException e) {

            throw e;
        } catch (SimulationConfigurationException e) {

            throw e;
        } catch (IllegalArgumentException e) {

            throw e;
        } catch (Exception e) {

            throw new SimulationExecutionException("Unexpected error during simulation execution", e);
        }
    }

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

    private SimulationReport executeWithinSession(
            SimulationSession session,
            SimulationConfig config,
            PlatformProfile platform,
            org.workflowsim.network.FatTreeTopology fatTreeTopology)
            throws Exception {

        SimulationEventRecorder events = config.isEventRecordingEnabled()
                ? new SimulationEventRecorder(config.getEventRecorderCap())
                : SimulationEventRecorder.disabled();
        SimulationAssembly assembly = assembleSession(session, config, platform, events, fatTreeTopology);
        WorkflowDatacenter datacenter = assembly.datacenter;
        WorkflowPlanner planner = assembly.planner;
        WorkflowEngine engine = assembly.engine;
        org.workflowsim.data.v2.CoherentDataflowRuntime coherent = assembly.coherent;

        double makespan = CloudSim.startSimulation();

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

    private static final class SimulationAssembly {
        private WorkflowDatacenter datacenter;
        private WorkflowPlanner planner;
        private WorkflowEngine engine;
        private org.workflowsim.data.v2.CoherentDataflowRuntime coherent;
    }

    private static void validateSupportedAlgorithms(SimulationConfig config, PlatformProfile platform) {
        if (config.getRegisteredSchedulingAlgorithmProvider() != null) {

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
                            anyVmPowered(platform),
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

    private static void validateSupportedFailureModel(SimulationConfig config) {
        FailureParameters.FTCluteringAlgorithm algorithm =
                config.getFailureModel().getClusteringAlgorithm();
        if (algorithm != FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP
                && algorithm != FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_CRCH) {
            throw new SimulationConfigurationException("Failure reclustering algorithm "
                    + algorithm + " is legacy compatibility code and is not supported by SimulationRunner; "
                    + "use FTCLUSTERING_NOOP or the explicit CRCH recovery model");
        }
        if (algorithm != FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_CRCH
                && config.getFailureModel().getMonitorMode() != FailureParameters.FTCMonitor.MONITOR_NONE) {
            throw new SimulationConfigurationException(
                    "SimulationRunner supports MONITOR_NONE only outside CRCH: under the "
                            + "verified FTCLUSTERING_NOOP retry model, other monitor modes do not alter recovery "
                            + "or scheduling and must not be treated as a research factor");
        }
    }

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

    private static boolean anyVmHasPowerModel(PlatformProfile platform) {
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.hasPowerModel()) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyVmPowered(PlatformProfile platform) {
        return anyVmHasPowerModel(platform) || anyVmHasFrequencyTiers(platform);
    }

    private static boolean anyVmHasFrequencyTiers(PlatformProfile platform) {
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.hasFrequencyTiers()) {
                return true;
            }
        }
        return false;
    }

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
