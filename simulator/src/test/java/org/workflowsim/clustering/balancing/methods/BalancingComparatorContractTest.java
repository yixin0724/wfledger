package org.workflowsim.clustering.balancing.methods;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.Task;
import org.workflowsim.clustering.TaskSet;

class BalancingComparatorContractTest {

    private static TaskSet runtime(long cloudletLength) {
        TaskSet set = new TaskSet();
        set.addTask(new Task((int) cloudletLength, cloudletLength));
        return set;
    }

    @Test
    void runtimeIncreasingSortsInExactAscendingOrder() {
        List<TaskSet> list =
                new ArrayList<TaskSet>(Arrays.asList(runtime(1_000L), runtime(3000L), runtime(500L), runtime(2_000L)));

        Collections.reverse(list);
        List<TaskSet> sorted = new ArrayList<TaskSet>(list);
        Collections.sort(sorted, HorizontalRuntimeBalancing.RUNTIME_INCREASING);
        assertEquals(
                Arrays.asList(500L, 1_000L, 2_000L, 3_000L),
                runtimes(sorted),
                "the ascending sort must order strictly by runtime length");
    }

    @Test
    void runtimeIncreasingDistinguishesIntWrapAroundDifferences() {

        List<TaskSet> pair = new ArrayList<TaskSet>(Arrays.asList(runtime(4294967296L), runtime(1L)));
        Collections.sort(pair, HorizontalRuntimeBalancing.RUNTIME_INCREASING);
        assertEquals(
                Arrays.asList(1L, 4294967296L),
                runtimes(pair),
                "two runtimes differing by 2^32 must be distinguishable (red test F-005: narrowing wrap-around)");

        List<TaskSet> asymmetric = new ArrayList<TaskSet>(Arrays.asList(runtime(4294967295L), runtime(1L)));
        Collections.sort(asymmetric, HorizontalRuntimeBalancing.RUNTIME_INCREASING);
        assertEquals(
                Arrays.asList(1L, 4294967295L),
                runtimes(asymmetric),
                "the direction for 0 vs 2^32-1 must also be correct (the other face of the narrowing wrap-around)");
    }

    @Test
    void runtimeDecreasingSortsInExactDescendingOrderAndMirrorsWrapAround() {
        List<TaskSet> list = new ArrayList<TaskSet>(Arrays.asList(runtime(4294967296L), runtime(1L), runtime(1_000L)));
        Collections.sort(list, HorizontalRuntimeBalancing.RUNTIME_DECREASING);
        assertEquals(
                Arrays.asList(4294967296L, 1_000L, 1L),
                runtimes(list),
                "the descending sort must stay correct with large runtimes (including the 2^32 wrap-around red test)");
    }

    private static List<Long> runtimes(List<TaskSet> sets) {
        List<Long> values = new ArrayList<Long>(sets.size());
        for (TaskSet set : sets) {
            values.add(Long.valueOf(set.getJobRuntime()));
        }
        return values;
    }
}
