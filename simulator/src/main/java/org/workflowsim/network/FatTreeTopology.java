package org.workflowsim.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.workflowsim.data.TransferContentionEngine;

/**
 * Al-Fares SIGCOMM 2008 k-Pod Fat-tree topology: structural construction plus deterministic
 * routing.
 *
 * <p>Structure (k even): k Pods, each Pod with k/2 edge switches and k/2 aggregate switches (full
 * bipartite connection inside a Pod); the core layer is fully populated with k^2/4 switches, and
 * core (i,j) (c = i*(k/2)+j) connects aggregate i of every Pod. A core reduction of
 * m &lt; k^2/4 means oversubscription, with oversubscription ratio (k^2/4)/m. Bandwidth
 * conservation layer by layer (the link totals of edge-&gt;agg, agg-&gt;core, core downlink, and
 * host access are all k^3/4) yields full bisection bandwidth; see
 * {@code docs/research/FAT_TREE_DESIGN.md} for details.</p>
 *
 * <p><b>Deterministic routing (v1 rules, all written into auditable evidence)</b>:</p>
 * <ul>
 *   <li>Same host: empty path (local switching between VMs, occupying no topology link);</li>
 *   <li>Uplink aggregate choice: a = srcEdge mod availA (availA = number of aggregates that own a
 *       core uplink = ceil(2m/k); availA = k/2 when fully populated);</li>
 *   <li>Cross-Pod core choice: among the available cores of aggregate a,
 *       {(a,j) : a*(k/2)+j &lt; m}, take j = (srcEdge + dstEdge + srcPod + dstPod) mod
 *       jCount;</li>
 *   <li>The structural property forces the aggregate on the destination Pod side to be the same
 *       index a (core (a,j) connects aggregate a of every Pod); the downlink is unique.</li>
 * </ul>
 *
 * <p>Links are modelled as duplex: each direction is an independent capacity resource (keys such
 * as {@code LINK:EDGE:0:1->AGG:0:0}), and reverse traffic does not share capacity. Host access
 * links are likewise directional ({@code LINK:ACC:h->EDGE:p:e} /
 * {@code LINK:EDGE:p:e->ACC:h}).</p>
 *
 * <p>Honest boundaries: forwarding inside a switch has no capacity constraint (only links and
 * endpoints are constrained); uniform link bandwidth; link delay is not modelled; no adaptive
 * routing, ECMP hashing, or link failure. For the rationale and trade-offs see the model
 * boundaries of {@code docs/research/FAT_TREE_DESIGN.md}.</p>
 */
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

    /**
     * Builds a Fat-tree from the topology spec and the platform host IDs.
     *
     * @param spec topology spec ({@link NetworkTopologySpec#fatTree})
     * @param hostIds all platform host IDs (order irrelevant, normalized to ascending order
     *        internally)
     * @return immutable topology instance
     * @throws IllegalArgumentException if the host count exceeds k^3/4, a placement is out of
     *         range, duplicated, or over capacity, or excessive oversubscription leaves some
     *         aggregate without a core uplink
     */
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
        // Oversubscription check: every aggregate (only index a < availA is used by routing)
        // must have at least one core uplink, that is a*halfK < coreSwitchCount for a = availA-1.
        int availableAggregateCount = (coreSwitchCount + halfK - 1) / halfK;
        if (availableAggregateCount < 1) {
            throw new IllegalArgumentException(
                    "Fat-tree core switch count too small for k=" + k + ": at least 1 core switch must be reachable");
        }
        // Placement resolution: explicit map or default rotation (ascending hostId -> rotating
        // edge switch index).
        int edgeSwitchCount = k * halfK;
        Map<Integer, int[]> hostToPodEdge = resolveHostPlacements(spec, sorted, halfK, edgeSwitchCount);
        // Unit contract: the link bandwidth of the spec is interpreted as MB/s (x 1e6 = bytes
        // per second), the same convention as the VM endpoint capacity (vm.getBw() x 1e6) and
        // the legacy estimation formula (bytes / 1e6 / bw). Under the same declared value, link
        // capacity and endpoint capacity are of the same order of magnitude (R8 audit fix:
        // previously it was mistakenly treated as Mb/s and divided by 8).
        Map<String, Double> linkCapacities = buildLinkCapacities(
                k, halfK, coreSwitchCount, hostToPodEdge, spec.getLinkBandwidthMbPerSecond() * 1_000_000.0);
        return new FatTreeTopology(
                k,
                spec.getLinkBandwidthMbPerSecond() * 1_000_000.0,
                coreSwitchCount,
                Collections.unmodifiableMap(hostToPodEdge),
                Collections.unmodifiableMap(linkCapacities));
    }

    /**
     * 2026-10-26 complexity extraction from fromSpec; statements verbatim; order unchanged.
     * Scope: resolves only the host placement table (default rotation in ascending hostId when
     * placement is not declared; when it is declared, validates coverage and per-edge capacity).
     */
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

    /** Builds the full physical link capacity table (duplex, per direction; access links of placed hosts only). */
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
            // Core uplinks of an aggregate: core c = agg*halfK + j, present only while c < coreSwitchCount.
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

    /**
     * Deterministic routing: returns the sequence of link resource keys occupied by a transfer
     * from the source host to the destination host (uplinks first, downlinks after, including the
     * access links at both ends).
     *
     * @param srcHostId source host ID
     * @param dstHostId destination host ID
     * @return list of resource keys; empty list for the same host
     * @throws IllegalArgumentException if a host is not placed in the topology
     */
    public List<String> route(int srcHostId, int dstHostId) {
        int[] src = requirePlaced(srcHostId);
        int[] dst = requirePlaced(dstHostId);
        List<String> path = new ArrayList<String>();
        if (srcHostId == dstHostId) {
            return path;
        }
        String srcEdge = "EDGE:" + src[0] + ":" + src[1];
        String dstEdge = "EDGE:" + dst[0] + ":" + dst[1];
        path.add("LINK:ACC:" + srcHostId + "->" + srcEdge);
        if (srcEdge.equals(dstEdge)) {
            path.add("LINK:" + dstEdge + "->ACC:" + dstHostId);
            return path;
        }
        int agg = src[1] % availableAggregateCount;
        path.add("LINK:" + srcEdge + "->AGG:" + src[0] + ":" + agg);
        if (src[0] != dst[0]) {
            int uplinks = coreUplinkCount(agg);
            int j = (src[1] + dst[1] + src[0] + dst[0]) % uplinks;
            int core = agg * halfK + j;
            path.add("LINK:AGG:" + src[0] + ":" + agg + "->CORE:" + core);
            path.add("LINK:CORE:" + core + "->AGG:" + dst[0] + ":" + agg);
        }
        path.add("LINK:AGG:" + dst[0] + ":" + agg + "->" + dstEdge);
        path.add("LINK:" + dstEdge + "->ACC:" + dstHostId);
        return path;
    }

    /**
     * Registers every link capacity into the contention engine (as generic resource keys; the
     * engine is agnostic to the semantics of a resource key).
     *
     * @param engine fluid contention engine
     */
    public void registerCapacities(TransferContentionEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("Contention engine cannot be null");
        }
        for (Map.Entry<String, Double> entry : linkCapacities.entrySet()) {
            engine.setEndpointCapacity(entry.getKey(), entry.getValue().doubleValue());
        }
    }

    /**
     * Read-only view of every link resource key (R8 audit F8: lets the test lock the closure
     * contract that "every link of any route already has a registered capacity", preventing the
     * route-naming side and the capacity-registration side from drifting apart).
     *
     * @return unmodifiable set of link resource keys
     */
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

    /** Number of available core uplinks of aggregate a (at least 1, guaranteed by the construction check). */
    private int coreUplinkCount(int agg) {
        return Math.min(halfK, coreSwitchCount - agg * halfK);
    }

    /** k-Pod parameter. */
    public int getK() {
        return k;
    }

    /** Number of core switches (fully populated = k^2/4). */
    public int getCoreSwitchCount() {
        return coreSwitchCount;
    }

    /** Total switch count = core + k Pods x k switches. */
    public int getSwitchCount() {
        return coreSwitchCount + k * k;
    }

    /** Total number of physical link resource keys (duplex, counted per direction). */
    public int getLinkResourceCount() {
        return linkCapacities.size();
    }

    /** Number of placed hosts. */
    public int getPlacedHostCount() {
        return hostToPodEdge.size();
    }

    /** Host capacity limit k^3/4. */
    public int getHostCapacity() {
        return k * k * k / 4;
    }

    /** Oversubscription ratio (k^2/4)/m; 1.0 when fully populated. */
    public double getOversubscriptionRatio() {
        return (k * k / 4.0) / coreSwitchCount;
    }

    /** Uniform link bandwidth, bytes per second. */
    public double getLinkBandwidthBytesPerSecond() {
        return linkBandwidthBytesPerSecond;
    }

    /** Immutable placement view host -> {pod, edgeIndex} (for tests and evidence). */
    public Map<Integer, int[]> getHostPlacements() {
        Map<Integer, int[]> copy = new LinkedHashMap<Integer, int[]>();
        for (Map.Entry<Integer, int[]> entry : hostToPodEdge.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(copy);
    }
}
