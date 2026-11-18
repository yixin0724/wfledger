package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

class MultiWorkflowArrivalConfigTest {

    private static final String PATH_A = "/tmp/workflow-a.dax";
    private static final String PATH_B = "/tmp/workflow-b.dax";

    @Test
    void defaultArrivalsAreZeroForEveryInput() {
        SimulationConfig single = SimulationConfig.builder(PATH_A, 2).build();
        assertEquals(
                Collections.singletonList(0.0),
                single.getWorkflowArrivalSeconds(),
                "a single input defaults to a submission instant of 0");
        SimulationConfig multi =
                SimulationConfig.builder(Arrays.asList(PATH_A, PATH_B), 2).build();
        assertEquals(
                Arrays.asList(0.0, 0.0),
                multi.getWorkflowArrivalSeconds(),
                "every input defaults to t=0 when several inputs are given");
    }

    @Test
    void explicitArrivalsArePreservedInOrderAndImmutable() {
        SimulationConfig config = SimulationConfig.builder(Arrays.asList(PATH_A, PATH_B), 2)
                .workflowArrivalSeconds(Arrays.asList(0.0, 42.5))
                .build();
        assertEquals(Arrays.asList(0.0, 42.5), config.getWorkflowArrivalSeconds());
        assertThrows(
                UnsupportedOperationException.class,
                () -> config.getWorkflowArrivalSeconds().set(0, 1.0),
                "the arrival-instant list must be unmodifiable");
    }

    @Test
    void arrivalCountMustMatchInputCount() {
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(Arrays.asList(PATH_A, PATH_B), 2)
                .workflowArrivalSeconds(Collections.singletonList(0.0))
                .build());
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(PATH_A, 2)
                .workflowArrivalSeconds(Arrays.asList(0.0, 1.0))
                .build());
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(PATH_A, 2)
                .workflowArrivalSeconds(null)
                .build());
    }

    @Test
    void arrivalValuesMustBeFiniteAndNonNegative() {
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(PATH_A, 2)
                .workflowArrivalSeconds(Collections.singletonList(-0.1))
                .build());
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(PATH_A, 2)
                .workflowArrivalSeconds(Collections.singletonList(Double.NaN))
                .build());
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(PATH_A, 2)
                .workflowArrivalSeconds(Collections.singletonList(Double.POSITIVE_INFINITY))
                .build());
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder(PATH_A, 2)
                .workflowArrivalSeconds(Collections.<Double>singletonList(null))
                .build());
    }

    @Test
    void toBuilderRoundTripPreservesArrivals() {
        SimulationConfig original = SimulationConfig.builder(Arrays.asList(PATH_A, PATH_B), 2)
                .workflowArrivalSeconds(Arrays.asList(0.0, 7.25))
                .build();
        SimulationConfig copy = original.toBuilder().randomSeed(123L).build();
        assertEquals(
                Arrays.asList(0.0, 7.25),
                copy.getWorkflowArrivalSeconds(),
                "toBuilder must preserve the arrival instants");
        assertEquals(123L, copy.getRandomSeed());
    }

    @Test
    void nonZeroArrivalRequiresClusteringNone() {

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> SimulationConfig.builder(Arrays.asList(PATH_A, PATH_B), 2)
                        .workflowArrivalSeconds(Arrays.asList(0.0, 10.0))
                        .clusteringParameters(
                                new ClusteringParameters(2, 0, ClusteringParameters.ClusteringMethod.HORIZONTAL, null))
                        .build());
        assertTrue(failure.getMessage().contains("clustering"), failure.getMessage());

        SimulationConfig zeroArrivalWithClustering = SimulationConfig.builder(Arrays.asList(PATH_A, PATH_B), 2)
                .clusteringParameters(
                        new ClusteringParameters(2, 0, ClusteringParameters.ClusteringMethod.HORIZONTAL, null))
                .build();
        assertEquals(Arrays.asList(0.0, 0.0), zeroArrivalWithClustering.getWorkflowArrivalSeconds());
    }
}
