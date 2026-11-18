package org.workflowsim.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.workflowsim.data.TransferContentionEngine;

public final class FatTreeTopology {

    private final int k;
    private final int halfK;
    private final double linkBandwidthBytesPerSecond;
    private final int coreSwitchCount;
    private final int availableAggregateCount;
    private final Map<Integer, int[]> hostToPodEdge;
    private final Map<String, Double> linkCapacities;

    private FatTreeTopology(
            int k,
            double linkBandwidthBytesPerSecond,
            int coreSwitchCount,
            Map<Integer, int[]> hostToPodEdge,
            Map<String, Double> linkCapacities) {
        this.k = k;
        this.halfK = k / 2;
        this.linkBandwidthBytesPerSecond = linkBandwidthBytesPerSecond;
        this.coreSwitchCount = coreSwitchCount;
        this.availableAggregateCount = (coreSwitchCount + halfK - 1) / halfK;
        this.hostToPodEdge = hostToPodEdge;
        this.linkCapacities = linkCapacities;
    }

    public static FatTreeTopology fromSpec(NetworkTopologySpec spec, List<Integer> hostIds) {
        if (spec == null || spec.getKind() != NetworkTopologySpec.Kind.FAT_TREE) {
            throw new IllegalArgumentException("FatTreeTopology requires a FAT_TREE topology spec");
        }
        if (hostIds == null || hostIds.isEmpty()) {
            throw new IllegalArgumentException("FatTreeTopology requires at least one host");
        }
        int k = spec.getK();
        int halfK = k / 2;
        List<Integer> sorted = new ArrayList<Integer>(new LinkedHashSet<Integer>(hostIds));
        if (sorted.size() != hostIds.size()) {
            throw new IllegalArgumentException("Duplicate host ids in FatTreeTopology placement: " + hostIds);
        }
        Collections.sort(sorted);
        int hostCapacity = k * k * k / 4;
        if (sorted.size() > hostCapacity) {
            throw new IllegalArgumentException(
                    "A k=" + k + " fat-tree hosts at most k^3/4=" + hostCapacity + " hosts, received " + sorted.size());
        }
        int coreSwitchCount =
                spec.getCoreSwitchCount() != null ? spec.getCoreSwitchCount().intValue() : k * k / 4;

        int availableAggregateCount = (coreSwitchCount + halfK - 1) / halfK;
        if (availableAggregateCount < 1) {
            throw new IllegalArgumentException(
                    "Fat-tree core switch count too small for k=" + k + ": at least 1 core switch must be reachable");
        }

        int edgeSwitchCount = k * halfK;
        Map<Integer, int[]> hostToPodEdge = resolveHostPlacements(spec, sorted, halfK, edgeSwitchCount);

        Map<String, Double> linkCapacities = buildLinkCapacities(
                k, halfK, coreSwitchCount, hostToPodEdge, spec.getLinkBandwidthMbPerSecond() * 1_000_000.0);
        return new FatTreeTopology(
                k,
                spec.getLinkBandwidthMbPerSecond() * 1_000_000.0,
                coreSwitchCount,
                Collections.unmodifiableMap(hostToPodEdge),
                Collections.unmodifiableMap(linkCapacities));
    }

    private static Map<Integer, int[]> resolveHostPlacements(
            NetworkTopologySpec spec, List<Integer> sorted, int halfK, int edgeSwitchCount) {
        Map<Integer, int[]> hostToPodEdge = new LinkedHashMap<Integer, int[]>();
        Map<Integer, Integer> placements = spec.getHostEdgePlacements();
        if (placements == null) {
            for (int i = 0; i < sorted.size(); i++) {
                int edgeSwitchIndex = i % edgeSwitchCount;
                hostToPodEdge.put(sorted.get(i), new int[] {edgeSwitchIndex / halfK, edgeSwitchIndex % halfK});
            }
        } else {
            Map<Integer, Integer> perEdgeCount = new LinkedHashMap<Integer, Integer>();
            for (Integer hostId : sorted) {
                Integer edgeSwitchIndex = placements.get(hostId);
                if (edgeSwitchIndex == null) {
                    throw new IllegalArgumentException("Explicit fat-tree host placement must cover host " + hostId);
                }
                int idx = edgeSwitchIndex.intValue();
                Integer count = perEdgeCount.get(Integer.valueOf(idx));
                int next = (count == null ? 0 : count.intValue()) + 1;
                if (next > halfK) {
                    throw new IllegalArgumentException(
                            "Fat-tree edge switch " + idx + " hosts at most k/2=" + halfK + " hosts");
                }
                perEdgeCount.put(Integer.valueOf(idx), Integer.valueOf(next));
                hostToPodEdge.put(hostId, new int[] {idx / halfK, idx % halfK});
            }
            for (Integer placedHost : placements.keySet()) {
                if (!hostToPodEdge.containsKey(placedHost)) {
                    throw new IllegalArgumentException(
                            "Explicit fat-tree host placement references unknown host " + placedHost);
                }
            }
        }
        return hostToPodEdge;
    }

    private static Map<String, Double> buildLinkCapacities(
            int k, int halfK, int coreSwitchCount, Map<Integer, int[]> hostToPodEdge, double bandwidthBytesPerSecond) {
        Map<String, Double> capacities = new LinkedHashMap<String, Double>();
        for (Map.Entry<Integer, int[]> entry : hostToPodEdge.entrySet()) {
            int hostId = entry.getKey().intValue();
            String edge = "EDGE:" + entry.getValue()[0] + ":" + entry.getValue()[1];
            capacities.put("LINK:ACC:" + hostId + "->" + edge, Double.valueOf(bandwidthBytesPerSecond));
            capacities.put("LINK:" + edge + "->ACC:" + hostId, Double.valueOf(bandwidthBytesPerSecond));
        }
        for (int pod = 0; pod < k; pod++) {
            for (int edge = 0; edge < halfK; edge++) {
                for (int agg = 0; agg < halfK; agg++) {
                    capacities.put(
                            "LINK:EDGE:" + pod + ":" + edge + "->AGG:" + pod + ":" + agg,
                            Double.valueOf(bandwidthBytesPerSecond));
                    capacities.put(
                            "LINK:AGG:" + pod + ":" + agg + "->EDGE:" + pod + ":" + edge,
                            Double.valueOf(bandwidthBytesPerSecond));
                }
            }

            for (int agg = 0; agg < halfK; agg++) {
                for (int j = 0; j < halfK && agg * halfK + j < coreSwitchCount; j++) {
                    int core = agg * halfK + j;
                    capacities.put(
                            "LINK:AGG:" + pod + ":" + agg + "->CORE:" + core, Double.valueOf(bandwidthBytesPerSecond));
                    capacities.put(
                            "LINK:CORE:" + core + "->AGG:" + pod + ":" + agg, Double.valueOf(bandwidthBytesPerSecond));
                }
            }
        }
        return capacities;
    }

    public List<String> route(int srcHostId, int dstHostId) {
        int[] src = requirePlaced(srcHostId);
        int[] dst = requirePlaced(dstHostId);
        return routingLinks(srcHostId, src[0], src[1], dstHostId, dst[0], dst[1], coreSwitchCount, halfK);
    }

    public static List<String> routingLinks(
            int srcHostId,
            int srcPod,
            int srcEdge,
            int dstHostId,
            int dstPod,
            int dstEdge,
            int coreSwitchCount,
            int halfK) {
        List<String> path = new ArrayList<String>();
        if (srcHostId == dstHostId) {
            return path;
        }
        String srcEdgeName = "EDGE:" + srcPod + ":" + srcEdge;
        String dstEdgeName = "EDGE:" + dstPod + ":" + dstEdge;
        path.add("LINK:ACC:" + srcHostId + "->" + srcEdgeName);
        if (!srcEdgeName.equals(dstEdgeName)) {
            int availableAggregates = (coreSwitchCount + halfK - 1) / halfK;
            int agg = srcEdge % availableAggregates;
            path.add("LINK:" + srcEdgeName + "->AGG:" + srcPod + ":" + agg);
            if (srcPod != dstPod) {
                int uplinks = Math.min(halfK, coreSwitchCount - agg * halfK);
                int core = agg * halfK + (srcEdge + dstEdge + srcPod + dstPod) % uplinks;
                path.add("LINK:AGG:" + srcPod + ":" + agg + "->CORE:" + core);
                path.add("LINK:CORE:" + core + "->AGG:" + dstPod + ":" + agg);
            }
            path.add("LINK:AGG:" + dstPod + ":" + agg + "->" + dstEdgeName);
        }
        path.add("LINK:" + dstEdgeName + "->ACC:" + dstHostId);
        return path;
    }

    public void registerCapacities(TransferContentionEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("Contention engine cannot be null");
        }
        for (Map.Entry<String, Double> entry : linkCapacities.entrySet()) {
            engine.setEndpointCapacity(entry.getKey(), entry.getValue().doubleValue());
        }
    }

    public Set<String> getLinkIds() {
        return java.util.Collections.unmodifiableSet(linkCapacities.keySet());
    }

    private int[] requirePlaced(int hostId) {
        int[] podEdge = hostToPodEdge.get(Integer.valueOf(hostId));
        if (podEdge == null) {
            throw new IllegalArgumentException("Host " + hostId + " is not placed in the fat-tree topology");
        }
        return podEdge;
    }

    private int coreUplinkCount(int agg) {
        return Math.min(halfK, coreSwitchCount - agg * halfK);
    }

    public int getK() {
        return k;
    }

    public int getCoreSwitchCount() {
        return coreSwitchCount;
    }

    public int getSwitchCount() {
        return coreSwitchCount + k * k;
    }

    public int getLinkResourceCount() {
        return linkCapacities.size();
    }

    public int getPlacedHostCount() {
        return hostToPodEdge.size();
    }

    public int getHostCapacity() {
        return k * k * k / 4;
    }

    public double getOversubscriptionRatio() {
        return (k * k / 4.0) / coreSwitchCount;
    }

    public double getLinkBandwidthBytesPerSecond() {
        return linkBandwidthBytesPerSecond;
    }

    public Map<Integer, int[]> getHostPlacements() {
        Map<Integer, int[]> copy = new LinkedHashMap<Integer, int[]>();
        for (Map.Entry<Integer, int[]> entry : hostToPodEdge.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(copy);
    }
}
