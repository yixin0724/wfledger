package org.workflowsim.planning;

import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.ClusteringParameters.ClusteringMethod;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * Planning context supplied only by {@code SimulationRunner}, consistent with the
 * controlled execution model.
 *
 * <p>Maintained shared-storage static DAG planners must read storage throughput and the
 * minimum event interval through this object, and must validate the file system,
 * clustering, failure, overhead, and VM scheduler modes before planning. It does not
 * infer network topology, link contention, or real-platform parameters.</p>
 */
public final class PlanningContext {

    private final SimulationConfig config;
    private final PlatformProfile platform;

    public PlanningContext(SimulationConfig config, PlatformProfile platform) {
        if (config == null || platform == null) {
            throw new IllegalArgumentException("Planning configuration and platform are required");
        }
        this.config = config;
        this.platform = platform;
    }

    public int getSharedStorageTransferRateMbPerSecond() {
        return platform.getStorage().getMaxTransferRateMbPerSecond();
    }

    public double getCloudSimMinEventIntervalSeconds() {
        return config.getCloudSimMinEventIntervalSeconds();
    }

    public double getDeadlineSeconds() {
        return config.getDeadline();
    }

    public PlatformProfile getPlatform() {
        return platform;
    }

    /**
     * Validates the subset of the execution model actually estimated by the maintained
     * static DAG planner.
     */
    public void validateSharedStorageStaticDag() {
        if (config.getFileSystem() != ReplicaCatalog.FileSystem.SHARED) {
            throw new IllegalArgumentException(
                    "Shared-storage static DAG planning requires ReplicaCatalog.FileSystem.SHARED");
        }
        if (config.getClusteringParameters().getClusteringMethod() != ClusteringMethod.NONE) {
            throw new IllegalArgumentException("Shared-storage static DAG planning requires clustering method NONE");
        }
        if (config.getFailureModel().isEnabled()) {
            throw new IllegalArgumentException(
                    "Shared-storage static DAG planning requires the failure model to be disabled");
        }
        if (!isNoOverhead()) {
            throw new IllegalArgumentException(
                    "Shared-storage static DAG planning requires OverheadModelConfig.none()");
        }
        for (PlatformProfile.VmSpec vm : platform.getVms()) {
            if (vm.getSchedulerMode() != PlatformProfile.CloudletSchedulerMode.SPACE_SHARED) {
                throw new IllegalArgumentException("Shared-storage static DAG planning requires SPACE_SHARED VMs");
            }
        }
    }

    /**
     * Validates the subset of the execution model actually estimated by the LOCAL
     * file-system communication-aware static DAG planner.
     *
     * <p>The planning-side AST of LOCAL_HEFT/LOCAL_CPOP/LOCAL_PEFT (per-parent parallel
     * transfer, transfer overlapping the VM busy interval) mirrors the runtime
     * preExecutionTransferDelayV1 pre-execution transfer delay model bit for bit, so it
     * requires the LOCAL file system, NONE clustering, no failure, and no modeled
     * overhead. The data movement model may be preExecutionTransferDelayV1 (planning and
     * execution bit-aligned), the link contention model
     * preExecutionTransferDelayWithContentionV1 (the planning side still estimates against
     * a contention-free AST, while at runtime concurrent transfers fairly share the VM
     * endpoint bandwidth), or the Fat-tree topology contention model fatTreeContentionV1
     * (at runtime fair sharing of path links and endpoints along deterministic routes,
     * under the same divergence declaration).</p>
     */
    public void validateLocalStaticDag() {
        if (config.getFileSystem() != ReplicaCatalog.FileSystem.LOCAL) {
            throw new IllegalArgumentException("LOCAL static DAG planning requires ReplicaCatalog.FileSystem.LOCAL");
        }
        if (config.getClusteringParameters().getClusteringMethod() != ClusteringMethod.NONE) {
            throw new IllegalArgumentException("LOCAL static DAG planning requires clustering method NONE");
        }
        if (config.getFailureModel().isEnabled()) {
            throw new IllegalArgumentException("LOCAL static DAG planning requires the failure model to be disabled");
        }
        if (!isNoOverhead()) {
            throw new IllegalArgumentException("LOCAL static DAG planning requires OverheadModelConfig.none()");
        }
        if (!config.getDataMovementModel().isPreExecutionTransferDelayV1()
                && !config.getDataMovementModel().isPreExecutionTransferDelayWithContentionV1()
                && !config.getDataMovementModel().isFatTreeContentionV1()) {
            throw new IllegalArgumentException("LOCAL static DAG planning requires "
                    + "DataMovementModel.preExecutionTransferDelayV1() (planning AST and runtime "
                    + "transfer delays bit-aligned), preExecutionTransferDelayWithContentionV1() "
                    + "(runtime endpoint link contention, documented divergence under concurrency), "
                    + "or fatTreeContentionV1() (runtime fat-tree path link contention, same "
                    + "divergence policy)");
        }
        for (PlatformProfile.VmSpec vm : platform.getVms()) {
            if (vm.getSchedulerMode() != PlatformProfile.CloudletSchedulerMode.SPACE_SHARED) {
                throw new IllegalArgumentException("LOCAL static DAG planning requires SPACE_SHARED VMs");
            }
        }
    }

    private boolean isNoOverhead() {
        return config.getOverheadModel().getWorkflowEngineDelayInterval() == 0
                && config.getOverheadModel().getBandwidth() == 0.0
                && config.getOverheadModel().getWorkflowEngineDelays().isEmpty()
                && config.getOverheadModel().getQueueDelays().isEmpty()
                && config.getOverheadModel().getPostDelays().isEmpty()
                && config.getOverheadModel().getClusteringDelays().isEmpty();
    }
}
