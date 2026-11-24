package org.workflowsim.scheduling.ebpsm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Hand-derived checks of Algorithm 1 (Hilman, Rodriguez and Buyya, arXiv:1903.01113).
 *
 * <p>FGCS-R5 (2026-11-24): fixture used by every test. Two VM types: slow type 0 (price 1 per second) and fast
 * type 1 (price 3 per second, half the processing time). Container delay {@code prov_c = 1} s, VM provisioning
 * delay 0. DAG: t1 -> t2, t1 -> t3, t2 -> t4, t3 -> t4. Processing seconds (slow / fast): t1 4/2, t2 6/3, t3 2/1,
 * t4 4/2. Eq. 5 costs {@code ceil(prov_c + PT) * c} (slow / fast): t1 5/9, t2 7/12, t3 3/6, t4 5/9; the cheapest
 * type of every task is slow. Eq. 7 levels: t1 0, t2 1, t3 1, t4 2. Eq. 8 EFTs on the slow type: t1 4, t2 10, t3 6,
 * so level 1 sorts t3 (EFT 6) before t2 (EFT 10) although t2 has the smaller ID; the order is S = t1, t3, t2, t4.
 */
class EbpsmBudgetDistributorTest {

    private static final EbpsmVmType SLOW = new EbpsmVmType(0, 100.0, 1.0, 0.0);
    private static final EbpsmVmType FAST = new EbpsmVmType(1, 200.0, 3.0, 0.0);

    /**
     * Budget 25: the cheapest allocation costs 5 + 3 + 7 + 5 = 20, leaving 5. SFTD walks S: t1 slow -> fast costs
     * 9 - 5 = 4 <= 5 (left 1); t3 needs 3, t2 needs 5, t4 needs 4, none fits 1. Sub-budgets: t1 9, t3 3, t2 7, t4 5,
     * residual 1 (conservation: 9 + 3 + 7 + 5 + 1 = 25).
     */
    @Test
    void distributesCheapestFirstThenUpgradesInDtlEftOrder() {
        EbpsmBudgetDistributor.Allocation allocation =
                new EbpsmBudgetDistributor(1.0).distribute(diamond(), Arrays.asList(FAST, SLOW), 25.0);

        assertEquals(
                Arrays.asList(1, 3, 2, 4),
                new ArrayList<Integer>(allocation.getBudgets().keySet()));
        assertEquals(9.0, allocation.getBudgets().get(1), 1.0e-9);
        assertEquals(3.0, allocation.getBudgets().get(3), 1.0e-9);
        assertEquals(7.0, allocation.getBudgets().get(2), 1.0e-9);
        assertEquals(5.0, allocation.getBudgets().get(4), 1.0e-9);
        assertEquals(1, allocation.getVmTypes().get(1).intValue());
        assertEquals(0, allocation.getVmTypes().get(2).intValue());
        assertEquals(1.0, allocation.getResidual(), 1.0e-9);
        assertTrue(allocation.isEveryTaskReached());
    }

    /**
     * Budget 12, Algorithm 1 lines 12–17 ({@code while beta > 0}): t1 takes 5 (beta 7), t3 takes 3 (beta 4), t2
     * takes 7 (beta -3); the loop stops, t4 keeps its initiated budget 0, nothing is upgraded, and the residual is
     * -3.
     */
    @Test
    void stopsAllocatingWhenTheBudgetIsExhausted() {
        EbpsmBudgetDistributor.Allocation allocation =
                new EbpsmBudgetDistributor(1.0).distribute(diamond(), Arrays.asList(SLOW, FAST), 12.0);

        assertEquals(5.0, allocation.getBudgets().get(1), 1.0e-9);
        assertEquals(3.0, allocation.getBudgets().get(3), 1.0e-9);
        assertEquals(7.0, allocation.getBudgets().get(2), 1.0e-9);
        assertEquals(0.0, allocation.getBudgets().get(4), 1.0e-9);
        assertEquals(-3.0, allocation.getResidual(), 1.0e-9);
        assertFalse(allocation.isEveryTaskReached());
    }

    /**
     * On a budget update the distributed set is {@code T_u}; parents outside it do not count. With t1 and t3 gone,
     * t2 is level 0 (EFT 6) and t4 level 1, so with beta 30: 7 + 5 = 12, residual 18; t2 upgrades for 5 (13 left),
     * t4 upgrades for 4 (9 left).
     */
    @Test
    void treatsParentsOutsideTheResidualSetAsCompleted() {
        List<EbpsmTask> residual = new ArrayList<EbpsmTask>();
        for (EbpsmTask task : diamond()) {
            if (task.getTaskId() == 2 || task.getTaskId() == 4) {
                residual.add(task);
            }
        }
        EbpsmBudgetDistributor.Allocation allocation =
                new EbpsmBudgetDistributor(1.0).distribute(residual, Arrays.asList(SLOW, FAST), 30.0);

        assertEquals(12.0, allocation.getBudgets().get(2), 1.0e-9);
        assertEquals(9.0, allocation.getBudgets().get(4), 1.0e-9);
        assertEquals(9.0, allocation.getResidual(), 1.0e-9);
    }

    static List<EbpsmTask> diamond() {
        return Arrays.asList(
                task(1, Collections.<Integer>emptyList(), 4.0, 2.0),
                task(2, Collections.singletonList(1), 6.0, 3.0),
                task(3, Collections.singletonList(1), 2.0, 1.0),
                task(4, Arrays.asList(2, 3), 4.0, 2.0));
    }

    static List<EbpsmVmType> types() {
        return Arrays.asList(SLOW, FAST);
    }

    private static EbpsmTask task(int id, List<Integer> parents, double slow, double fast) {
        Map<Integer, Double> processing = new LinkedHashMap<Integer, Double>();
        processing.put(0, slow);
        processing.put(1, fast);
        return new EbpsmTask(id, parents, processing);
    }
}
