package org.workflowsim.network;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NetworkTopologySpec {

    public enum Kind {
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

    public static NetworkTopologySpec fatTree(int k, double linkBandwidthMbPerSecond) {
        return fatTree(k, linkBandwidthMbPerSecond, null, null);
    }

    public static NetworkTopologySpec fatTree(int k, double linkBandwidthMbPerSecond, int coreSwitchCount) {
        return fatTree(k, linkBandwidthMbPerSecond, Integer.valueOf(coreSwitchCount), null);
    }

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

    public int getK() {
        return k;
    }

    public double getLinkBandwidthMbPerSecond() {
        return linkBandwidthMbPerSecond;
    }

    public Integer getCoreSwitchCount() {
        return coreSwitchCount;
    }

    public Map<Integer, Integer> getHostEdgePlacements() {
        return hostEdgePlacements;
    }
}
