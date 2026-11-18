package org.workflowsim.planning.naco;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class NacoDncEvaluatorTest {

    @Test
    void dncAddsCommunicationDiskReadAndComputeToTheFinishTime() {
        NacoTask first = new NacoTask(
                1, 0.0, 0.0, compute(1.0, 1.0), Collections.<Integer>emptyList(), Collections.singletonMap(2, 100.0));
        NacoTask second =
                new NacoTask(2, 200.0, 0.0, compute(2.0, 2.0), Collections.singletonList(1), Collections.emptyMap());
        NacoDncProblem problem = new NacoDncProblem(Arrays.asList(first, second), speeds(100.0), disks(100.0, 50.0));

        NacoSchedule schedule =
                new NacoDncEvaluator().evaluate(Arrays.asList(new NacoGene(1, 0), new NacoGene(2, 1)), problem);

        assertEquals(1.0, schedule.getTaskPlan(2).getStartSeconds(), 1.0e-9);
        assertEquals(8.0, schedule.getTaskPlan(2).getFinishSeconds(), 1.0e-9);
        assertEquals(8.0, schedule.getMakespanSeconds(), 1.0e-9);
    }

    @Test
    void heftSeedMatchesTheHandDerivedTwoVmMapping() {
        NacoTask first =
                new NacoTask(1, 0.0, 0.0, compute(2.0, 4.0), Collections.<Integer>emptyList(), Collections.emptyMap());
        NacoTask second =
                new NacoTask(2, 0.0, 0.0, compute(3.0, 1.0), Collections.singletonList(1), Collections.emptyMap());
        NacoDncProblem problem = new NacoDncProblem(Arrays.asList(first, second), speeds(100.0), disks(100.0, 100.0));

        NacoSchedule schedule = new NacoDncEvaluator().heft(problem);

        assertEquals(0, schedule.getTaskPlan(1).getVmId());
        assertEquals(1, schedule.getTaskPlan(2).getVmId());
        assertEquals(3.0, schedule.getMakespanSeconds(), 1.0e-9);
    }

    @Test
    void heftSeedUsesGraphTopologyInsteadOfNumericTaskIds() {
        NacoTask parent = new NacoTask(
                2, 0.0, 0.0, compute(1.0, 1.0), Collections.<Integer>emptyList(), Collections.singletonMap(1, 0.0));
        NacoTask child =
                new NacoTask(1, 0.0, 0.0, compute(3.0, 3.0), Collections.singletonList(2), Collections.emptyMap());
        NacoDncProblem problem = new NacoDncProblem(Arrays.asList(parent, child), speeds(100.0), disks(100.0, 100.0));

        NacoSchedule schedule = new NacoDncEvaluator().heft(problem);

        assertEquals(1.0, schedule.getTaskPlan(2).getFinishSeconds(), 1.0e-9);
        assertEquals(1.0, schedule.getTaskPlan(1).getStartSeconds(), 1.0e-9);
        assertEquals(4.0, schedule.getMakespanSeconds(), 1.0e-9);
    }

    private static Map<Integer, Double> compute(double... values) {
        Map<Integer, Double> result = new LinkedHashMap<Integer, Double>();
        for (int index = 0; index < values.length; index++) {
            result.put(index, values[index]);
        }
        return result;
    }

    private static Map<Integer, Double> speeds(double value) {
        Map<Integer, Double> result = new LinkedHashMap<Integer, Double>();
        result.put(0, value);
        result.put(1, value);
        return result;
    }

    private static Map<Integer, Double> disks(double first, double second) {
        Map<Integer, Double> result = new LinkedHashMap<Integer, Double>();
        result.put(0, first);
        result.put(1, second);
        return result;
    }
}
