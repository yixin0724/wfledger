package org.workflowsim.scheduling.ebpsm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** SFTD budget distribution and actual-cost budget-update checks. */
class EbpsmBudgetDistributorTest {

    @Test
    void distributesAndUpgradesTaskBudgetsInEstimatedOrder() {
        EbpsmVmType slow = new EbpsmVmType(0, 1.0, 1.0, 0.0);
        EbpsmVmType fast = new EbpsmVmType(1, 2.0, 2.0, 0.0);
        Map<Integer, Double> processing = new LinkedHashMap<Integer, Double>();
        processing.put(0, 2.0);
        processing.put(1, 1.5);
        EbpsmTask first = new EbpsmTask(1, Collections.<Integer>emptyList(), processing);
        EbpsmTask second = new EbpsmTask(2, Collections.singletonList(1), processing);

        Map<Integer, Double> budgets =
                new EbpsmBudgetDistributor().distribute(Arrays.asList(first, second), Arrays.asList(slow, fast), 10.0);

        assertEquals(4.0, budgets.get(1), 1.0e-9);
        assertEquals(4.0, budgets.get(2), 1.0e-9);
        assertEquals(4.0, new EbpsmBudgetDistributor().updateRemainingBudget(10.0, 7.0, 1.0), 1.0e-9);
        assertEquals(-2.0, new EbpsmBudgetDistributor().updateRemainingBudget(10.0, 12.0, 0.0), 1.0e-9);
    }
}
