package org.workflowsim.network;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable declaration of a platform network topology.
 *
 * <p>v1 supports only the Al-Fares k-Pod Fat-tree (see {@link FatTreeTopology}
 * and {@code docs/research/FAT_TREE_DESIGN.md}). Topology parameters belong to
 * the platform description ({@code PlatformProfile}); a data movement model
 * only declares the semantics "use topology contention".</p>
 *
 * <p>Honest boundary: a structural declaration of a flow-level fluid model --
 * it contains no packet loss, queueing delay detail, ECN, adaptive routing, or
 * link failure semantics.</p>
 */
public final class NetworkTopologySpec {

    /** Topology family. */
    public enum Kind {
        /** Al-Fares SIGCOMM 2008 k-Pod Fat-tree. */
        FAT_TREE
    }

    private final Kind kind;
    private final int k;
    private final double linkBandwidthMbPerSecond;
    private final Integer coreSwitchCount;
    private final Map<Integer, Integer> hostEdgePlacements;

    private NetworkTopologySpec(
            Kind kind,
            int k,
            double linkBandwidthMbPerSecond,
            Integer coreSwitchCount,
            Map<Integer, Integer> hostEdgePlacements) {
        this.kind = kind;
        this.k = k;
        this.linkBandwidthMbPerSecond = linkBandwidthMbPerSecond;
        this.coreSwitchCount = coreSwitchCount;
        this.hostEdgePlacements = hostEdgePlacements;
    }

    /**
     * Declares a fully provisioned (no oversubscription) k-Pod Fat-tree, with
     * hosts laid out round-robin over all edge switches in ascending hostId
     * order (deterministic default placement).
     *
     * @param k k-Pod parameter, must be an even integer >= 2
     * @param linkBandwidthMbPerSecond uniform link bandwidth, MB/s, must be
     *        positive and finite
     * @return immutable topology declaration
     */
    public static NetworkTopologySpec fatTree(int k, double linkBandwidthMbPerSecond) {
        return fatTree(k, linkBandwidthMbPerSecond, null, null);
    }

    /**
     * Declares a k-Pod Fat-tree that may be oversubscribed.
     *
     * @param k k-Pod parameter, must be an even integer >= 2
     * @param linkBandwidthMbPerSecond uniform link bandwidth, MB/s, must be
     *        positive and finite
     * @param coreSwitchCount number of core switches, 1 &lt;= m &lt;= k*k/4;
     *        m &lt; k*k/4 means oversubscribed
     * @return immutable topology declaration
     */
    public static NetworkTopologySpec fatTree(int k, double linkBandwidthMbPerSecond, int coreSwitchCount) {
        return fatTree(k, linkBandwidthMbPerSecond, Integer.valueOf(coreSwitchCount), null);
    }

    /**
     * Declares a k-Pod Fat-tree with explicit host placement.
     *
     * @param k k-Pod parameter, must be an even integer >= 2
     * @param linkBandwidthMbPerSecond uniform link bandwidth, MB/s, must be
     *        positive and finite
     * @param coreSwitchCount number of core switches; null means fully
     *        provisioned k*k/4
     * @param hostEdgePlacements hostId to global edge switch index
     *        (idx = pod*(k/2) + edge in [0, k*k/2)); null means the default
     *        round-robin placement; when supplied explicitly, the mapping must
     *        cover every host and each edge switch holds at most k/2 hosts
     * @return immutable topology declaration
     */
    public static NetworkTopologySpec fatTree(
            int k, double linkBandwidthMbPerSecond, Integer coreSwitchCount, Map<Integer, Integer> hostEdgePlacements) {
        if (k < 2 || k % 2 != 0) {
            throw new IllegalArgumentException("Fat-tree k must be an even integer >= 2: " + k);
        }
        if (!(linkBandwidthMbPerSecond > 0.0)
                || Double.isInfinite(linkBandwidthMbPerSecond)
                || Double.isNaN(linkBandwidthMbPerSecond)) {
            throw new IllegalArgumentException(
                    "Fat-tree link bandwidth must be positive and finite: " + linkBandwidthMbPerSecond);
        }
        int maxCore = k * k / 4;
        if (coreSwitchCount != null) {
            if (coreSwitchCount.intValue() < 1 || coreSwitchCount.intValue() > maxCore) {
                throw new IllegalArgumentException(
                        "Fat-tree core switch count must be in [1, k*k/4=" + maxCore + "]: " + coreSwitchCount);
            }
        }
        Map<Integer, Integer> placements = null;
        if (hostEdgePlacements != null) {
            int edgeSwitchCount = k * k / 2;
            placements = new LinkedHashMap<Integer, Integer>();
            for (Map.Entry<Integer, Integer> entry : hostEdgePlacements.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw new IllegalArgumentException("Fat-tree host placement entries cannot contain null");
                }
                if (entry.getValue().intValue() < 0 || entry.getValue().intValue() >= edgeSwitchCount) {
                    throw new IllegalArgumentException("Fat-tree edge switch index must be in [0, k*k/2="
                            + edgeSwitchCount + "): " + entry.getValue());
                }
                placements.put(entry.getKey(), entry.getValue());
            }
            placements = Collections.unmodifiableMap(placements);
        }
        return new NetworkTopologySpec(Kind.FAT_TREE, k, linkBandwidthMbPerSecond, coreSwitchCount, placements);
    }

    public Kind getKind() {
        return kind;
    }

    /** k-Pod parameter. */
    public int getK() {
        return k;
    }

    /** Uniform link bandwidth, MB/s. */
    public double getLinkBandwidthMbPerSecond() {
        return linkBandwidthMbPerSecond;
    }

    /** Number of core switches; null means fully provisioned k*k/4. */
    public Integer getCoreSwitchCount() {
        return coreSwitchCount;
    }

    /** hostId to global edge switch index; null means the default round-robin placement. Immutable. */
    public Map<Integer, Integer> getHostEdgePlacements() {
        return hostEdgePlacements;
    }
}
