package org.workflowsim.clustering.balancing.methods;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.Task;
import org.workflowsim.clustering.TaskSet;

/**
 * F-005 (review engineering C15): order-conservation contract of the balancing-clustering
 * runtime-length comparator.
 *
 * <p>Project-rule source (conservation law): an ascending comparator must satisfy -- after
 * sorting with that comparator, the <b>comparison result of adjacent elements is
 * non-decreasing</b>; any two TaskSets with different runtimes must not be reported as
 * "equal". The historical implementation narrowed {@code (int)(long difference)} to the
 * low 32 bits, so a difference that is an exact multiple of 2^32 was judged "equal". Real
 * impact case: for large clustering runtimes (MIPS scaling MI) beyond the int domain the
 * comparison collapses and LPT bin packing degrades to the input order.</p>
 */
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
        // First inject an arrangement whose input order is the reverse of the target order,
        // so an all-zero comparator cannot pass by stability alone.
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
        // F-005 red case: (int)(1 - 2^32) keeps the low 32 bits == 1 (the direction being
        // correct is a coincidence), while (int)(2^32 - 1) == -1 has the wrong direction.
        // The conservation law requires: every distinguishable runtime order must be
        // correct. Use 1 rather than 0 as the baseline: Cloudlet silently keeps the
        // default 1 for lengths <= 0.
        List<TaskSet> pair = new ArrayList<TaskSet>(Arrays.asList(runtime(4294967296L), runtime(1L)));
        Collections.sort(pair, HorizontalRuntimeBalancing.RUNTIME_INCREASING);
        assertEquals(
                Arrays.asList(1L, 4294967296L),
                runtimes(pair),
                "two runtimes differing by 2^32 must be distinguishable (red test F-005: narrowing wrap-around)");

        // Asymmetric deviation probe: the difference between 0 and 2^32+1 narrows to 1 (the
        // direction happens to be correct), but the difference between 0 and 2^32-1 narrows to
        // -1 (the direction is wrong) -- the other face of the same defect.
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
