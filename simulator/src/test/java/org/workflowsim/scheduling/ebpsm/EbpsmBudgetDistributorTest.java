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

class EbpsmBudgetDistributorTest {

    private static final EbpsmVmType SLOW = new EbpsmVmType(0, 100.0, 1.0, 0.0);
    private static final EbpsmVmType FAST = new EbpsmVmType(1, 200.0, 3.0, 0.0);

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
