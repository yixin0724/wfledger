package org.workflowsim.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.workflowsim.data.TransferContentionEngine;

/**
 * Structural, placement, and deterministic-routing unit tests for the Al-Fares k-Pod Fat-tree topology.
 *
 * <p>All golden values are hand-derived from the structure and routing described in
 * {@code docs/advanced/FAT_TREE_DESIGN.md}: the k=2 minimal topology (2 hosts, 5 switches, 1 core) and
 * the k=4 topology (16-host capacity, 20 switches, 4 cores). Route key sequences are computed by hand
 * under the v1 deterministic rules: the uplink aggregate = srcEdge mod availA; the cross-Pod core
 * choice j = (srcEdge + dstEdge + srcPod + dstPod) mod the number of available cores of that
 * aggregate.</p>
 */
public class FatTreeTopologyTest {

    private static List<Integer> hosts(int count) {
        List<Integer> ids = new ArrayList<Integer>();
        for (int id = 0; id < count; id++) {
            ids.add(Integer.valueOf(id));
        }
        return ids;
    }

    /** k=2 minimal structure: 2 hosts, 5 switches (1 core + 2 Pods x 2 each), 12 link resource keys. */
    @Test
    public void minimalK2StructureMatchesHandComputedCounts() {
        FatTreeTopology topology = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(2, 100.0), hosts(2));
        assertEquals(2, topology.getK());
        assertEquals(1, topology.getCoreSwitchCount());
        assertEquals(5, topology.getSwitchCount());
        assertEquals(2, topology.getHostCapacity());
        assertEquals(2, topology.getPlacedHostCount());
        assertEquals(1.0, topology.getOversubscriptionRatio(), 0.0);
        // Access 2x2 + intra-Pod edge<->agg 2 Pods x 1 x 1 x 2 + agg<->core 2 Pods x 1 x 2 = 12.
        assertEquals(12, topology.getLinkResourceCount());
        // Unit contract: a declared 100 MB/s means 100x10^6 bytes per second (the same convention as
        // VM endpoint capacity vm.getBw() x 10^6, with no division by 8; R8 audit regression test).
        assertEquals(100.0 * 1_000_000.0, topology.getLinkBandwidthBytesPerSecond(), 1.0e-6);
        // Default round-robin placement: host0 -> Pod0, host1 -> Pod1 (one edge switch each).
        Map<Integer, int[]> placements = topology.getHostPlacements();
        assertEquals(0, placements.get(Integer.valueOf(0))[0]);
        assertEquals(1, placements.get(Integer.valueOf(1))[0]);
    }

    /** k=2 cross-Pod route golden value: 6 resource keys through the single core. */
    @Test
    public void crossPodRouteOnMinimalTopology() {
        FatTreeTopology topology = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(2, 100.0), hosts(2));
        assertEquals(
                Arrays.asList(
                        "LINK:ACC:0->EDGE:0:0",
                        "LINK:EDGE:0:0->AGG:0:0",
                        "LINK:AGG:0:0->CORE:0",
                        "LINK:CORE:0->AGG:1:0",
                        "LINK:AGG:1:0->EDGE:1:0",
                        "LINK:EDGE:1:0->ACC:1"),
                topology.route(0, 1));
        // Same host: switched locally, consuming no topology link.
        assertEquals(Collections.emptyList(), topology.route(0, 0));
    }

    /**
     * k=4 fully populated structure: 20 switches, 16-host capacity, 80 link resource keys (8 hosts placed).
     */
    @Test
    public void fullK4StructureMatchesHandComputedCounts() {
        FatTreeTopology topology = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), hosts(8));
        assertEquals(4, topology.getCoreSwitchCount());
        assertEquals(20, topology.getSwitchCount());
        assertEquals(16, topology.getHostCapacity());
        assertEquals(1.0, topology.getOversubscriptionRatio(), 0.0);
        // Access 8x2 + intra-Pod 4x2x2x2 + agg<->core 4 Pods x 2 aggs x 2 cores x 2 directions = 80.
        assertEquals(80, topology.getLinkResourceCount());
        // Default round-robin: host i -> edgeSwitchIndex i mod 8 -> pod=i/2, edge=i%2.
        Map<Integer, int[]> placements = topology.getHostPlacements();
        for (int i = 0; i < 8; i++) {
            assertEquals(i / 2, placements.get(Integer.valueOf(i))[0], "host " + i + " pod");
            assertEquals(i % 2, placements.get(Integer.valueOf(i))[1], "host " + i + " edge");
        }
    }

    /** k=4 golden routes for the three classes: same edge, same Pod across edges, and cross-Pod. */
    @Test
    public void routeClassesOnK4() {
        // 9 hosts -> host0 and host8 round-robin onto the same edge (0,0).
        FatTreeTopology topology = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), hosts(9));
        // Same edge (2 keys).
        assertEquals(Arrays.asList("LINK:ACC:0->EDGE:0:0", "LINK:EDGE:0:0->ACC:8"), topology.route(0, 8));
        // Same Pod across edges: host0 (0,0) -> host1 (0,1), agg = 0%2 = 0 (4 keys).
        assertEquals(
                Arrays.asList(
                        "LINK:ACC:0->EDGE:0:0",
                        "LINK:EDGE:0:0->AGG:0:0",
                        "LINK:AGG:0:0->EDGE:0:1",
                        "LINK:EDGE:0:1->ACC:1"),
                topology.route(0, 1));
        // Cross-Pod: host0 (0,0) -> host2 (1,0). agg = 0; uplinks = min(2, 4-0) = 2;
        // j = (0+0+0+1) mod 2 = 1 -> core 1 (6 keys).
        assertEquals(
                Arrays.asList(
                        "LINK:ACC:0->EDGE:0:0",
                        "LINK:EDGE:0:0->AGG:0:0",
                        "LINK:AGG:0:0->CORE:1",
                        "LINK:CORE:1->AGG:1:0",
                        "LINK:AGG:1:0->EDGE:1:0",
                        "LINK:EDGE:1:0->ACC:2"),
                topology.route(0, 2));
    }

    /** Routing determinism: two constructions with the same parameters yield bit-identical paths. */
    @Test
    public void routingIsDeterministicAcrossConstructions() {
        FatTreeTopology first = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), hosts(9));
        FatTreeTopology second = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), hosts(9));
        for (int src = 0; src < 9; src++) {
            for (int dst = 0; dst < 9; dst++) {
                assertEquals(first.route(src, dst), second.route(src, dst));
                // Every route key is a physical link with a registered capacity (registerCapacities coverage).
                TransferContentionEngine engine = new TransferContentionEngine();
                first.registerCapacities(engine);
                engine.addTransfer(1L, 1000L, first.route(src, dst), 1.0e12, 0.0);
                double expected = src == dst ? 1.0e12 : Math.min(1.0e12, 100.0 * 1_000_000.0);
                assertEquals(
                        expected,
                        engine.currentRateBytesPerSecond(1L),
                        1.0e-6,
                        "route " + src + "->" + dst + " single-flow rate must equal the link bandwidth (no sharing)");
            }
        }
    }

    /**
     * Oversubscription: the reduced core count yields the correct ratio, routing stays deterministic,
     * and aggregates without core uplinks are not used.
     */
    @Test
    public void oversubscribedCoreReduction() {
        // k=4, m=3: availA = ceil(2x3/4) = 2; agg0 owns cores {0,1}, agg1 owns core {2}.
        FatTreeTopology topology = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0, 3), hosts(8));
        assertEquals(4.0 / 3.0, topology.getOversubscriptionRatio(), 1.0e-12);
        // Switches = 3 cores + k Pods x k switches = 19.
        assertEquals(19, topology.getSwitchCount());
        // agg<->core link resources: 4 Pods x (agg0 2 cores + agg1 1 core) x 2 directions = 24;
        // access 16 + intra-Pod 32 + 24 = 72.
        assertEquals(72, topology.getLinkResourceCount());
        // host1 (0,1) -> host3 (1,1): agg = 1%2 = 1; uplinks(1) = min(2, 3-2) = 1;
        // j = (1+1+0+1) mod 1 = 0 -> core 2.
        assertEquals(
                Arrays.asList(
                        "LINK:ACC:1->EDGE:0:1",
                        "LINK:EDGE:0:1->AGG:0:1",
                        "LINK:AGG:0:1->CORE:2",
                        "LINK:CORE:2->AGG:1:1",
                        "LINK:AGG:1:1->EDGE:1:1",
                        "LINK:EDGE:1:1->ACC:3"),
                topology.route(1, 3));
        // k=4, m=2: availA = ceil(4/4) = 1 -> all traffic goes through agg0; oversubscription ratio 2.0.
        FatTreeTopology heavy = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0, 2), hosts(8));
        assertEquals(2.0, heavy.getOversubscriptionRatio(), 0.0);
        assertEquals("LINK:EDGE:0:1->AGG:0:0", heavy.route(1, 3).get(1));
    }

    /**
     * Structural-axis contract (basis for the campaign sensitivity design): on 4 hosts, k,
     * oversubscription, and placement do reshape how the cross flows (0->2 and 1->3, which share no
     * endpoint host) share links -- the baseline shares nothing, 2x oversubscription shares 2 core
     * links, k=8 collapses to a single Pod with 4-link intra-Pod paths, and same-edge host pairs make
     * the cross flows share every intermediate link except the two ends' ACC access links.
     */
    @Test
    public void structuralVariantsReshapeCrossFlowSharingOnFourHosts() {
        // Baseline k=4 fully populated: round-robin placement (0,0),(0,1),(1,0),(1,1); the cross flows
        // take their own aggregates/cores, so the paths are fully disjoint.
        FatTreeTopology baseline = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), hosts(4));
        List<String> cross02 = baseline.route(0, 2);
        List<String> cross13 = baseline.route(1, 3);
        assertEquals(6, cross02.size());
        assertEquals(6, cross13.size());
        assertTrue(Collections.disjoint(cross02, cross13), "baseline cross flows 0->2 and 1->3 must not share links");

        // A1: k=4, m=2 (2x oversubscription) -> availA = ceil(2x2/4) = 1, all traffic goes through
        // agg0; the cross flows share the two AGG:0:0<->CORE:1 links.
        FatTreeTopology oversub = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0, 2), hosts(4));
        List<String> shared = new ArrayList<String>(oversub.route(0, 2));
        shared.retainAll(oversub.route(1, 3));
        assertEquals(Arrays.asList("LINK:AGG:0:0->CORE:1", "LINK:CORE:1->AGG:1:0"), shared);

        // A2: k=8 -> the round-robin indices 0..3 of the 4 hosts all land in pod0 (halfK=4), there is no
        // cross-Pod traffic, and the cross flows become disjoint 4-link intra-Pod paths.
        FatTreeTopology k8 = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(8, 100.0), hosts(4));
        List<String> k8Cross02 = k8.route(0, 2);
        assertEquals(
                Arrays.asList(
                        "LINK:ACC:0->EDGE:0:0",
                        "LINK:EDGE:0:0->AGG:0:0",
                        "LINK:AGG:0:0->EDGE:0:2",
                        "LINK:EDGE:0:2->ACC:2"),
                k8Cross02);
        assertTrue(Collections.disjoint(k8Cross02, k8.route(1, 3)), "k=8 single Pod cross flows must not share links");

        // A5: same-edge host pairs {0->0,1->0,2->2,3->2}: 0->1 shortens to a 2-link same-edge path;
        // the cross flows 0->2 and 1->3 share all 4 intermediate links except the two ends' ACC access links.
        Map<Integer, Integer> pairs = new LinkedHashMap<Integer, Integer>();
        pairs.put(Integer.valueOf(0), Integer.valueOf(0));
        pairs.put(Integer.valueOf(1), Integer.valueOf(0));
        pairs.put(Integer.valueOf(2), Integer.valueOf(2));
        pairs.put(Integer.valueOf(3), Integer.valueOf(2));
        FatTreeTopology clustered =
                FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0, null, pairs), hosts(4));
        assertEquals(Arrays.asList("LINK:ACC:0->EDGE:0:0", "LINK:EDGE:0:0->ACC:1"), clustered.route(0, 1));
        List<String> pairShared = new ArrayList<String>(clustered.route(0, 2));
        pairShared.retainAll(clustered.route(1, 3));
        assertEquals(
                Arrays.asList(
                        "LINK:EDGE:0:0->AGG:0:0",
                        "LINK:AGG:0:0->CORE:1",
                        "LINK:CORE:1->AGG:1:0",
                        "LINK:AGG:1:0->EDGE:1:0"),
                pairShared,
                "same-edge host pairs must make the cross flows share every intermediate link except the"
                        + " two ends' ACC links");
    }

    /** Explicit placement: coverage, capacity, and unknown-host validation. */
    @Test
    public void explicitPlacementValidation() {
        // Explicit placement: host0 and host1 share edge0 (at k=4 each edge has capacity 2, so this is legal).
        Map<Integer, Integer> placement = new LinkedHashMap<Integer, Integer>();
        placement.put(Integer.valueOf(0), Integer.valueOf(0));
        placement.put(Integer.valueOf(1), Integer.valueOf(0));
        FatTreeTopology topology =
                FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0, null, placement), hosts(2));
        assertEquals(0, topology.getHostPlacements().get(Integer.valueOf(1))[1]);
        // Same-edge route (2 keys).
        assertEquals(2, topology.route(0, 1).size());
        // Incomplete host coverage -> rejected.
        Map<Integer, Integer> partial = new LinkedHashMap<Integer, Integer>();
        partial.put(Integer.valueOf(0), Integer.valueOf(0));
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0, null, partial), hosts(2)));
        assertTrue(missing.getMessage().contains("must cover host 1"), missing.getMessage());
        // Unknown host -> rejected.
        Map<Integer, Integer> unknown = new LinkedHashMap<Integer, Integer>(placement);
        unknown.put(Integer.valueOf(99), Integer.valueOf(0));
        IllegalArgumentException unknownEx = assertThrows(
                IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0, null, unknown), hosts(2)));
        assertTrue(unknownEx.getMessage().contains("unknown host 99"), unknownEx.getMessage());
        // k=2 gives each edge a capacity of 1: two hosts on the same edge -> rejected.
        Map<Integer, Integer> overCapacity = new LinkedHashMap<Integer, Integer>();
        overCapacity.put(Integer.valueOf(0), Integer.valueOf(0));
        overCapacity.put(Integer.valueOf(1), Integer.valueOf(0));
        IllegalArgumentException over = assertThrows(
                IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(2, 100.0, null, overCapacity), hosts(2)));
        assertTrue(over.getMessage().contains("at most k/2"), over.getMessage());
    }

    /**
     * Closure contract (R8 audit F8): the route keys of any host pair must all fall inside the set of
     * link keys with a registered capacity -- this prevents the silent distortion in which route naming
     * and capacity registration evolve independently and a route crosses an unregistered link that the
     * engine then treats as having infinite capacity. Covers three configurations: baseline
     * round-robin, oversubscription, and explicit same-edge placement.
     */
    @Test
    public void everyRouteLinkIsARegisteredCapacityKey() {
        Map<Integer, Integer> pairs = new LinkedHashMap<Integer, Integer>();
        pairs.put(Integer.valueOf(0), Integer.valueOf(0));
        pairs.put(Integer.valueOf(1), Integer.valueOf(0));
        pairs.put(Integer.valueOf(2), Integer.valueOf(2));
        pairs.put(Integer.valueOf(3), Integer.valueOf(2));
        FatTreeTopology[] topologies = {
            FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), hosts(9)),
            FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0, 2), hosts(8)),
            FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0, null, pairs), hosts(4)),
        };
        for (FatTreeTopology topology : topologies) {
            java.util.Set<String> linkIds = topology.getLinkIds();
            assertEquals(topology.getLinkResourceCount(), linkIds.size());
            for (Integer src : topology.getHostPlacements().keySet()) {
                for (Integer dst : topology.getHostPlacements().keySet()) {
                    for (String link : topology.route(src.intValue(), dst.intValue())) {
                        assertTrue(
                                linkIds.contains(link),
                                "route " + src + "->" + dst + " uses unregistered link key " + link);
                    }
                }
            }
        }
    }

    /**
     * Engine-level combination: overlapping paths fairly share at half rate, and disjoint paths do not
     * interfere (no-blocking property).
     */
    @Test
    public void sharedAndDisjointRoutesFairSharing() {
        FatTreeTopology topology = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), hosts(8));
        TransferContentionEngine engine = new TransferContentionEngine();
        topology.registerCapacities(engine);
        double linkBw = 100.0 * 1_000_000.0;
        // Flow 1 (host0->host2, cross-Pod) and flow 2 (host5->host7, cross-Pod) have disjoint routes:
        // each gets the full link rate.
        engine.addTransfer(1L, 1000L, topology.route(0, 2), 1.0e12, 0.0);
        engine.addTransfer(2L, 1000L, topology.route(5, 7), 1.0e12, 0.0);
        assertEquals(linkBw, engine.currentRateBytesPerSecond(1L), 1.0e-6);
        assertEquals(linkBw, engine.currentRateBytesPerSecond(2L), 1.0e-6);
        // Flow 3 follows exactly the same route as flow 1: both get half rate on the shared links;
        // flow 2 is unaffected.
        engine.addTransfer(3L, 1000L, topology.route(0, 2), 1.0e12, 0.0);
        assertEquals(linkBw / 2.0, engine.currentRateBytesPerSecond(1L), 1.0e-6);
        assertEquals(linkBw / 2.0, engine.currentRateBytesPerSecond(3L), 1.0e-6);
        assertEquals(linkBw, engine.currentRateBytesPerSecond(2L), 1.0e-6);
    }

    /**
     * Construction and spec validation: k parity, bandwidth (including NaN/Inf), core range, null or
     * negative placement entries, host count limit, duplicate/empty/null hosts, and unplaced routing.
     */
    @Test
    public void specAndConstructionValidation() {
        assertThrows(IllegalArgumentException.class, () -> NetworkTopologySpec.fatTree(3, 100.0));
        assertThrows(IllegalArgumentException.class, () -> NetworkTopologySpec.fatTree(0, 100.0));
        assertThrows(IllegalArgumentException.class, () -> NetworkTopologySpec.fatTree(4, 0.0));
        assertThrows(IllegalArgumentException.class, () -> NetworkTopologySpec.fatTree(4, -1.0));
        assertThrows(IllegalArgumentException.class, () -> NetworkTopologySpec.fatTree(4, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> NetworkTopologySpec.fatTree(4, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> NetworkTopologySpec.fatTree(4, 100.0, 0));
        assertThrows(IllegalArgumentException.class, () -> NetworkTopologySpec.fatTree(4, 100.0, 5));
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkTopologySpec.fatTree(
                        2, 100.0, null, Collections.singletonMap(Integer.valueOf(0), Integer.valueOf(2))));
        // Explicit placement: null keys/values and negative indices -> rejected.
        Map<Integer, Integer> nullValue = new LinkedHashMap<Integer, Integer>();
        nullValue.put(Integer.valueOf(0), null);
        IllegalArgumentException nullEntry = assertThrows(
                IllegalArgumentException.class, () -> NetworkTopologySpec.fatTree(2, 100.0, null, nullValue));
        assertTrue(nullEntry.getMessage().contains("cannot contain null"), nullEntry.getMessage());
        Map<Integer, Integer> nullKey = new LinkedHashMap<Integer, Integer>();
        nullKey.put(null, Integer.valueOf(0));
        assertThrows(IllegalArgumentException.class, () -> NetworkTopologySpec.fatTree(2, 100.0, null, nullKey));
        IllegalArgumentException negativeIdx = assertThrows(
                IllegalArgumentException.class,
                () -> NetworkTopologySpec.fatTree(
                        2, 100.0, null, Collections.singletonMap(Integer.valueOf(0), Integer.valueOf(-1))));
        assertTrue(negativeIdx.getMessage().contains("k*k/2"), negativeIdx.getMessage());
        // Host count above k^3/4 -> rejected.
        IllegalArgumentException tooMany = assertThrows(
                IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(2, 100.0), hosts(3)));
        assertTrue(tooMany.getMessage().contains("k^3/4"), tooMany.getMessage());
        // Duplicate host IDs -> rejected; empty or null host list -> rejected.
        assertThrows(
                IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), Arrays.asList(0, 0, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(
                        NetworkTopologySpec.fatTree(4, 100.0), Collections.<Integer>emptyList()));
        assertThrows(
                IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), null));
        // Routing for an unplaced host -> rejected.
        FatTreeTopology topology = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), hosts(4));
        IllegalArgumentException unplaced = assertThrows(IllegalArgumentException.class, () -> topology.route(0, 77));
        assertTrue(unplaced.getMessage().contains("not placed"), unplaced.getMessage());
        // Non-FAT_TREE spec or null -> rejected; null engine registration -> rejected.
        assertThrows(IllegalArgumentException.class, () -> FatTreeTopology.fromSpec(null, hosts(2)));
        IllegalArgumentException nullEngine =
                assertThrows(IllegalArgumentException.class, () -> topology.registerCapacities(null));
        assertTrue(nullEngine.getMessage().contains("cannot be null"), nullEngine.getMessage());
    }
}
