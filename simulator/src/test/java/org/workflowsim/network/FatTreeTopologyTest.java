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

public class FatTreeTopologyTest {

    private static List<Integer> hosts(int count) {
        List<Integer> ids = new ArrayList<Integer>();
        for (int id = 0; id < count; id++) {
            ids.add(Integer.valueOf(id));
        }
        return ids;
    }

    @Test
    public void minimalK2StructureMatchesHandComputedCounts() {
        FatTreeTopology topology = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(2, 100.0), hosts(2));
        assertEquals(2, topology.getK());
        assertEquals(1, topology.getCoreSwitchCount());
        assertEquals(5, topology.getSwitchCount());
        assertEquals(2, topology.getHostCapacity());
        assertEquals(2, topology.getPlacedHostCount());
        assertEquals(1.0, topology.getOversubscriptionRatio(), 0.0);

        assertEquals(12, topology.getLinkResourceCount());

        assertEquals(100.0 * 1_000_000.0, topology.getLinkBandwidthBytesPerSecond(), 1.0e-6);

        Map<Integer, int[]> placements = topology.getHostPlacements();
        assertEquals(0, placements.get(Integer.valueOf(0))[0]);
        assertEquals(1, placements.get(Integer.valueOf(1))[0]);
    }

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

        assertEquals(Collections.emptyList(), topology.route(0, 0));
    }

    @Test
    public void fullK4StructureMatchesHandComputedCounts() {
        FatTreeTopology topology = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), hosts(8));
        assertEquals(4, topology.getCoreSwitchCount());
        assertEquals(20, topology.getSwitchCount());
        assertEquals(16, topology.getHostCapacity());
        assertEquals(1.0, topology.getOversubscriptionRatio(), 0.0);

        assertEquals(80, topology.getLinkResourceCount());

        Map<Integer, int[]> placements = topology.getHostPlacements();
        for (int i = 0; i < 8; i++) {
            assertEquals(i / 2, placements.get(Integer.valueOf(i))[0], "host " + i + " pod");
            assertEquals(i % 2, placements.get(Integer.valueOf(i))[1], "host " + i + " edge");
        }
    }

    @Test
    public void routeClassesOnK4() {

        FatTreeTopology topology = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), hosts(9));

        assertEquals(Arrays.asList("LINK:ACC:0->EDGE:0:0", "LINK:EDGE:0:0->ACC:8"), topology.route(0, 8));

        assertEquals(
                Arrays.asList(
                        "LINK:ACC:0->EDGE:0:0",
                        "LINK:EDGE:0:0->AGG:0:0",
                        "LINK:AGG:0:0->EDGE:0:1",
                        "LINK:EDGE:0:1->ACC:1"),
                topology.route(0, 1));

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

    @Test
    public void routingIsDeterministicAcrossConstructions() {
        FatTreeTopology first = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), hosts(9));
        FatTreeTopology second = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), hosts(9));
        for (int src = 0; src < 9; src++) {
            for (int dst = 0; dst < 9; dst++) {
                assertEquals(first.route(src, dst), second.route(src, dst));

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

    @Test
    public void oversubscribedCoreReduction() {

        FatTreeTopology topology = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0, 3), hosts(8));
        assertEquals(4.0 / 3.0, topology.getOversubscriptionRatio(), 1.0e-12);

        assertEquals(19, topology.getSwitchCount());

        assertEquals(72, topology.getLinkResourceCount());

        assertEquals(
                Arrays.asList(
                        "LINK:ACC:1->EDGE:0:1",
                        "LINK:EDGE:0:1->AGG:0:1",
                        "LINK:AGG:0:1->CORE:2",
                        "LINK:CORE:2->AGG:1:1",
                        "LINK:AGG:1:1->EDGE:1:1",
                        "LINK:EDGE:1:1->ACC:3"),
                topology.route(1, 3));

        FatTreeTopology heavy = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0, 2), hosts(8));
        assertEquals(2.0, heavy.getOversubscriptionRatio(), 0.0);
        assertEquals("LINK:EDGE:0:1->AGG:0:0", heavy.route(1, 3).get(1));
    }

    @Test
    public void structuralVariantsReshapeCrossFlowSharingOnFourHosts() {

        FatTreeTopology baseline = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), hosts(4));
        List<String> cross02 = baseline.route(0, 2);
        List<String> cross13 = baseline.route(1, 3);
        assertEquals(6, cross02.size());
        assertEquals(6, cross13.size());
        assertTrue(Collections.disjoint(cross02, cross13), "baseline cross flows 0->2 and 1->3 must not share links");

        FatTreeTopology oversub = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0, 2), hosts(4));
        List<String> shared = new ArrayList<String>(oversub.route(0, 2));
        shared.retainAll(oversub.route(1, 3));
        assertEquals(Arrays.asList("LINK:AGG:0:0->CORE:1", "LINK:CORE:1->AGG:1:0"), shared);

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

    @Test
    public void explicitPlacementValidation() {

        Map<Integer, Integer> placement = new LinkedHashMap<Integer, Integer>();
        placement.put(Integer.valueOf(0), Integer.valueOf(0));
        placement.put(Integer.valueOf(1), Integer.valueOf(0));
        FatTreeTopology topology =
                FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0, null, placement), hosts(2));
        assertEquals(0, topology.getHostPlacements().get(Integer.valueOf(1))[1]);

        assertEquals(2, topology.route(0, 1).size());

        Map<Integer, Integer> partial = new LinkedHashMap<Integer, Integer>();
        partial.put(Integer.valueOf(0), Integer.valueOf(0));
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0, null, partial), hosts(2)));
        assertTrue(missing.getMessage().contains("must cover host 1"), missing.getMessage());

        Map<Integer, Integer> unknown = new LinkedHashMap<Integer, Integer>(placement);
        unknown.put(Integer.valueOf(99), Integer.valueOf(0));
        IllegalArgumentException unknownEx = assertThrows(
                IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0, null, unknown), hosts(2)));
        assertTrue(unknownEx.getMessage().contains("unknown host 99"), unknownEx.getMessage());

        Map<Integer, Integer> overCapacity = new LinkedHashMap<Integer, Integer>();
        overCapacity.put(Integer.valueOf(0), Integer.valueOf(0));
        overCapacity.put(Integer.valueOf(1), Integer.valueOf(0));
        IllegalArgumentException over = assertThrows(
                IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(2, 100.0, null, overCapacity), hosts(2)));
        assertTrue(over.getMessage().contains("at most k/2"), over.getMessage());
    }

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

    @Test
    public void sharedAndDisjointRoutesFairSharing() {
        FatTreeTopology topology = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), hosts(8));
        TransferContentionEngine engine = new TransferContentionEngine();
        topology.registerCapacities(engine);
        double linkBw = 100.0 * 1_000_000.0;

        engine.addTransfer(1L, 1000L, topology.route(0, 2), 1.0e12, 0.0);
        engine.addTransfer(2L, 1000L, topology.route(5, 7), 1.0e12, 0.0);
        assertEquals(linkBw, engine.currentRateBytesPerSecond(1L), 1.0e-6);
        assertEquals(linkBw, engine.currentRateBytesPerSecond(2L), 1.0e-6);

        engine.addTransfer(3L, 1000L, topology.route(0, 2), 1.0e12, 0.0);
        assertEquals(linkBw / 2.0, engine.currentRateBytesPerSecond(1L), 1.0e-6);
        assertEquals(linkBw / 2.0, engine.currentRateBytesPerSecond(3L), 1.0e-6);
        assertEquals(linkBw, engine.currentRateBytesPerSecond(2L), 1.0e-6);
    }

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

        IllegalArgumentException tooMany = assertThrows(
                IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(2, 100.0), hosts(3)));
        assertTrue(tooMany.getMessage().contains("k^3/4"), tooMany.getMessage());

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

        FatTreeTopology topology = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0), hosts(4));
        IllegalArgumentException unplaced = assertThrows(IllegalArgumentException.class, () -> topology.route(0, 77));
        assertTrue(unplaced.getMessage().contains("not placed"), unplaced.getMessage());

        assertThrows(IllegalArgumentException.class, () -> FatTreeTopology.fromSpec(null, hosts(2)));
        IllegalArgumentException nullEngine =
                assertThrows(IllegalArgumentException.class, () -> topology.registerCapacities(null));
        assertTrue(nullEngine.getMessage().contains("cannot be null"), nullEngine.getMessage());
    }
}
