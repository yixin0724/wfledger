package org.workflowsim.data;

/**
 * Immutable file-transfer delay contract used by one simulation run.
 *
 * <p>The defaults strictly preserve the historical WorkflowSim transfer computation. The
 * fixed-endpoint option deliberately adopts an abstract model with in-Job serialization and no
 * inter-Job contention; it does not represent a packet-switched network, queueing, shared-link
 * competition, or a storage system calibrated against real data.</p>
 */
public final class DataMovementModel {

    /**
     * Immutable contract enumeration for the file-transfer delay families of one simulated run,
     * from the historical envelope calculation through pre-execution and fat-tree contention variants
     * to the coherent V2/V3 lifecycles; members are ordered by contract generation.
     */
    public enum Kind {
        /** Historical shared/local file-transfer computation. */
        LEGACY_WORKFLOWSIM_V1,
        /** Fixed endpoint links, per-file added delay, and no shared contention. */
        FIXED_ENDPOINT_NO_CONTENTION_V1,
        /**
         * Paper-semantics pre-execution transfer delay model (the communication model of list
         * scheduling in the Topcuoglu TPDS 2002 class): input arrival is estimated from the
         * completion instant of each parent task; external input starts when the Job becomes ready
         * and may overlap the target VM busy period; the VM is occupied by computation MI only,
         * and transfer seconds are no longer folded into the execution envelope. Transfer seconds
         * follow the bandwidth rules of {@link #LEGACY_WORKFLOWSIM_V1} (SOURCE to VM uses the
         * target VM bandwidth, VM to VM uses {@code min(bw)}, and a replica transfer is local with
         * zero transfer).
         */
        PRE_EXECUTION_TRANSFER_DELAY_V1,
        /**
         * Pre-execution transfer delay model with link contention: transfers may overlap the target
         * VM busy period, and active transfers on the same VM endpoint use max-min progressive
         * filling with a nominal cap. All transfer groups start together when the data of the
         * owning Job becomes ready, without tracing back the transfer progress of earlier parent
         * tasks. This start rule differs from the contention-free V1; the planning side still
         * estimates with the contention-free AST. Segmented integration reallocates capacity at
         * internal completion instants; makespan across the whole DAG is not promised to be
         * monotonic across models.
         */
        PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1,
        /**
         * Fat-tree topology-aware pre-execution transfer delay model with link contention: the
         * transfer-window semantics are the same as
         * {@link #PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1}, but the contention domain is
         * generalized from the VM endpoint to every shared link on the deterministic route path
         * (Al-Fares k-Pod Fat-tree, flow-level max-min progressive filling); endpoint and link
         * capacities apply simultaneously, allocating rates by progressive filling with bottleneck
         * freezing. The platform must declare the topology through
         * {@code PlatformProfile.networkTopology(...)}
         * ({@code org.workflowsim.network.NetworkTopologySpec}). External input (SOURCE) traffic
         * of v1 bypasses the topology and occupies only the target VM endpoint. Rationale and
         * design are in {@code docs/advanced/FAT_TREE_DESIGN.md}.
         */
        PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1,
        /** Versioned per-file visible-source/actual-route lifecycle with shared capacities. */
        COHERENT_FILE_DATAFLOW_V2,
        /** Same V2 lifecycle and isolated path bottlenecks, without inter-flow sharing. */
        COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2,
        /** Bounded SOURCE/shared store with mandatory successful-output materialization. */
        COHERENT_STORAGE_DATAFLOW_V3,
        /** Same storage lifecycle with independent route bottlenecks. */
        COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3
    }

    private static final DataMovementModel LEGACY = new DataMovementModel(Kind.LEGACY_WORKFLOWSIM_V1, 0.0, 0.0, 0.0);
    private static final DataMovementModel PRE_EXECUTION_TRANSFER_DELAY =
            new DataMovementModel(Kind.PRE_EXECUTION_TRANSFER_DELAY_V1, 0.0, 0.0, 0.0);
    private static final DataMovementModel PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION =
            new DataMovementModel(Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1, 0.0, 0.0, 0.0);
    private static final DataMovementModel FAT_TREE_CONTENTION =
            new DataMovementModel(Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1, 0.0, 0.0, 0.0);

    private static final DataMovementModel COHERENT_SHARED =
            new DataMovementModel(Kind.COHERENT_FILE_DATAFLOW_V2, 0, 0, 0);
    private static final DataMovementModel COHERENT_ISOLATED =
            new DataMovementModel(Kind.COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2, 0, 0, 0);

    /** @return per-file V2 lifecycle with checked shared resource service */
    public static DataMovementModel coherentFileDataflowV2() {
        return COHERENT_SHARED;
    }
    /** @return the same V2 lifecycle with isolated single-flow route bottlenecks */
    public static DataMovementModel coherentFileDataflowNoContentionV2() {
        return COHERENT_ISOLATED;
    }
    /** @return whether the dedicated V2 run coordinator is required */
    public boolean isCoherentFileDataflowV2() {
        return kind == Kind.COHERENT_FILE_DATAFLOW_V2 || kind == Kind.COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2;
    }
    /** @return whether V2 resources share capacity between active file copies */
    public boolean isCoherentShared() {
        return kind == Kind.COHERENT_FILE_DATAFLOW_V2;
    }

    private static final DataMovementModel
            STORAGE_SHARED = new DataMovementModel(Kind.COHERENT_STORAGE_DATAFLOW_V3, 0, 0, 0),
            STORAGE_ISOLATED = new DataMovementModel(Kind.COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3, 0, 0, 0);
    /** @return bounded storage and successful-output writeback model */
    public static DataMovementModel coherentStorageDataflowV3() {
        return STORAGE_SHARED;
    }
    /** @return same storage lifecycle without inter-flow sharing */
    public static DataMovementModel coherentStorageDataflowNoContentionV3() {
        return STORAGE_ISOLATED;
    }
    /** @return exact storage-version predicate */
    public boolean isCoherentStorageDataflowV3() {
        return kind == Kind.COHERENT_STORAGE_DATAFLOW_V3 || kind == Kind.COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3;
    }
    /** @return dedicated per-file coordinator required, with its explicitly selected contract */
    public boolean usesCoherentDataflowRuntime() {
        return isCoherentFileDataflowV2() || isCoherentStorageDataflowV3();
    }
    /** @return active copies share capacity under their selected coherent contract */
    public boolean usesSharedDataflowResources() {
        return isCoherentShared() || kind == Kind.COHERENT_STORAGE_DATAFLOW_V3;
    }

    private final Kind kind;
    private final double accessLinkBandwidthMbPerSecond;
    private final double accessLinkLatencySeconds;
    private final double sourceEndpointBandwidthMbPerSecond;

    private DataMovementModel(
            Kind kind,
            double accessLinkBandwidthMbPerSecond,
            double accessLinkLatencySeconds,
            double sourceEndpointBandwidthMbPerSecond) {
        this.kind = kind;
        this.accessLinkBandwidthMbPerSecond = accessLinkBandwidthMbPerSecond;
        this.accessLinkLatencySeconds = accessLinkLatencySeconds;
        this.sourceEndpointBandwidthMbPerSecond = sourceEndpointBandwidthMbPerSecond;
    }

    /**
     * Returns the strictly compatible default model used by simulation behavior before P10.
     *
     * @return shared immutable instance of the historical WorkflowSim transfer model
     */
    public static DataMovementModel legacyWorkflowsimV1() {
        return LEGACY;
    }

    /**
     * Returns the paper-semantics pre-execution transfer delay model.
     *
     * <p>The input transfer of a computed Job starts when its data becomes ready and may overlap
     * the VM busy period; the VM is occupied by computation only. This is the communication model
     * required by LOCAL_HEFT/LOCAL_CPOP/LOCAL_PEFT to reproduce the scheduling semantics of
     * Topcuoglu TPDS 2002 and Arabnejad &amp; Barbosa TPDS 2014.</p>
     *
     * @return shared immutable instance of the pre-execution transfer delay model
     */
    public static DataMovementModel preExecutionTransferDelayV1() {
        return PRE_EXECUTION_TRANSFER_DELAY;
    }

    /**
     * Returns the pre-execution transfer delay model with link contention.
     *
     * <p>Transfers may overlap the VM busy period, but all parent groups start together when the
     * Job becomes ready, without tracing back progress of earlier parent completions; the VM
     * endpoint uses max-min progressive filling, and SOURCE has no capacity cap.</p>
     *
     * @return shared immutable instance of the contention model
     */
    public static DataMovementModel preExecutionTransferDelayWithContentionV1() {
        return PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION;
    }

    /**
     * Returns the Fat-tree topology-aware pre-execution transfer delay model with link contention.
     *
     * <p>The transfer-window semantics are the same as
     * {@link #preExecutionTransferDelayWithContentionV1()}; the difference is that the contention
     * domain is generalized to every shared link on the deterministic Fat-tree route path plus the
     * VM endpoint (flow-level max-min fair sharing). The platform must declare the topology through
     * {@code PlatformProfile.Builder.networkTopology(...)}.</p>
     *
     * @return shared immutable instance of the Fat-tree contention model
     */
    public static DataMovementModel fatTreeContentionV1() {
        return FAT_TREE_CONTENTION;
    }

    /**
     * Creates a fixed-endpoint model without topology.
     *
     * <p>Every non-local file transfer is processed serially within its owning Job, and its
     * duration is a fixed delay plus the file size divided by the bottleneck rate; concurrent
     * transfers of different Jobs do not compete for capacity.</p>
     *
     * @param accessLinkBandwidthMbPerSecond per-transfer rate cap of the target endpoint access link, in MB/s
     * @param accessLinkLatencySeconds fixed delay added to each non-local file transfer, in seconds
     * @param sourceEndpointBandwidthMbPerSecond transfer rate cap of the external source endpoint, in MB/s
     * @return immutable data movement model with the given abstract endpoint parameters
     * @throws IllegalArgumentException when a bandwidth is non-positive or non-finite, or the latency is
     *         negative or non-finite
     */
    public static DataMovementModel fixedEndpointNoContention(
            double accessLinkBandwidthMbPerSecond,
            double accessLinkLatencySeconds,
            double sourceEndpointBandwidthMbPerSecond) {
        if (!positiveFinite(accessLinkBandwidthMbPerSecond)
                || !nonNegativeFinite(accessLinkLatencySeconds)
                || !positiveFinite(sourceEndpointBandwidthMbPerSecond)) {
            throw new IllegalArgumentException("Fixed endpoint data movement requires positive finite access and "
                    + "source bandwidths and a finite non-negative latency");
        }
        return new DataMovementModel(
                Kind.FIXED_ENDPOINT_NO_CONTENTION_V1,
                accessLinkBandwidthMbPerSecond,
                accessLinkLatencySeconds,
                sourceEndpointBandwidthMbPerSecond);
    }

    private static boolean positiveFinite(double value) {
        return value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static boolean nonNegativeFinite(double value) {
        return value >= 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    public Kind getKind() {
        return kind;
    }

    public double getAccessLinkBandwidthMbPerSecond() {
        return accessLinkBandwidthMbPerSecond;
    }

    public double getAccessLinkLatencySeconds() {
        return accessLinkLatencySeconds;
    }

    public double getSourceEndpointBandwidthMbPerSecond() {
        return sourceEndpointBandwidthMbPerSecond;
    }

    public boolean isLegacyWorkflowsimV1() {
        return kind == Kind.LEGACY_WORKFLOWSIM_V1;
    }

    public boolean isPreExecutionTransferDelayV1() {
        return kind == Kind.PRE_EXECUTION_TRANSFER_DELAY_V1;
    }

    public boolean isPreExecutionTransferDelayWithContentionV1() {
        return kind == Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1;
    }

    public boolean isFatTreeContentionV1() {
        return kind == Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1;
    }
}
