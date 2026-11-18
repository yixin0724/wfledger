package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.utils.ReshiConfig;
import org.workflowsim.utils.ReshiPredictionErrorModel;
import org.workflowsim.utils.ReshiRankTable;
import org.workflowsim.utils.ReshiTaskHistory;
import org.workflowsim.utils.SimulationRandom;

class ReshiSchedulingAlgorithmTest {

    @Test
    void reshiCUsesChildrenAndRankedNodes() {
        ReshiConfig config = config();
        ReshiSchedulingAlgorithm scheduler = new ReshiSchedulingAlgorithm(ReshiStrategy.RESHI_C, config);
        Job small = job(1, "work", 1);
        Job large = job(2, "work", 3);
        Job medium = job(3, "work", 2);
        scheduler.setCloudletList(Arrays.asList(small, large, medium));
        scheduler.setVmList(Arrays.asList(vm(0), vm(1), vm(2)));

        scheduler.run();

        assertEquals(Arrays.asList(large, medium, small), scheduler.getScheduledList());
        assertEquals(1, large.getVmId());
        assertEquals(2, medium.getVmId());
        assertEquals(0, small.getVmId());
    }

    @Test
    void reshiMUsesHistoricalAverageRuntime() {
        ReshiConfig config = config();
        ReshiSchedulingAlgorithm scheduler = new ReshiSchedulingAlgorithm(ReshiStrategy.RESHI_M, config);
        Job slow = job(1, "slow", 1);
        Job fast = job(2, "fast", 1);
        Job medium = job(3, "medium", 1);
        scheduler.setCloudletList(Arrays.asList(slow, fast, medium));
        scheduler.setVmList(Arrays.asList(vm(0), vm(1), vm(2)));

        scheduler.run();

        assertEquals(Arrays.asList(slow, medium, fast), scheduler.getScheduledList());
    }

    @Test
    void rankedSelectionRejectsPeIncompatibleVms() {
        ReshiSchedulingAlgorithm scheduler = new ReshiSchedulingAlgorithm(ReshiStrategy.RESHI_C, config());
        Job job = job(1, "work", 0);
        job.setNumberOfPes(2);
        job.getTaskList().get(0).setNumberOfPes(2);
        scheduler.setCloudletList(Arrays.asList(job));

        scheduler.setVmList(Arrays.asList(vm(0, 2), vm(1, 1), vm(2, 2)));

        scheduler.run();

        assertEquals(2, job.getVmId());
    }

    @Test
    void missingRankOnCompatibleIdleVmsFailsExplicitly() {
        ReshiSchedulingAlgorithm scheduler = new ReshiSchedulingAlgorithm(ReshiStrategy.RESHI_C, config());
        Job job = job(1, "missing", 0);
        scheduler.setCloudletList(Arrays.asList(job));
        scheduler.setVmList(Arrays.asList(vm(0)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, scheduler::run);
        assertTrue(error.getMessage().contains("missing"));
    }

    @Test
    void predictionErrorCannotChangeReshiMPriorityOrConsumeItsRandomStream() {
        ReshiConfig base = config();
        ReshiConfig noisy = new ReshiConfig(
                base.getRankTable(),
                base.getTaskHistory(),
                new ReshiPredictionErrorModel(ReshiPredictionErrorModel.DistributionFamily.NORMAL),
                10.0);
        ReshiSchedulingAlgorithm scheduler = new ReshiSchedulingAlgorithm(ReshiStrategy.RESHI_M, noisy);
        Job slow = job(1, "slow", 0);
        Job fast = job(2, "fast", 0);
        scheduler.setCloudletList(Arrays.asList(fast, slow));
        scheduler.setVmList(Arrays.asList(vm(0), vm(1), vm(2)));
        try {
            SimulationRandom.reset(7L);
            long expectedNextSeed =
                    SimulationRandom.newJavaRandom("prediction.reshi.runtime").nextLong();
            SimulationRandom.reset(7L);

            scheduler.run();

            long actualNextSeed =
                    SimulationRandom.newJavaRandom("prediction.reshi.runtime").nextLong();
            assertEquals(expectedNextSeed, actualNextSeed, "Reshi-M must not sample prediction-dependent priorities");
        } finally {
            SimulationRandom.reset(7L);
        }
        assertEquals(Arrays.asList(slow, fast), scheduler.getScheduledList());
        assertEquals(0, slow.getVmId());
        assertEquals(1, fast.getVmId());
    }

    private static ReshiConfig config() {
        ReshiRankTable table = new ReshiRankTable(Arrays.asList(
                new ReshiRankTable.Entry("work", "vm0", 5.0, 0),
                new ReshiRankTable.Entry("work", "vm1", 1.0, 1),
                new ReshiRankTable.Entry("work", "vm2", 3.0, 2),
                new ReshiRankTable.Entry("slow", "vm0", 5.0, 0),
                new ReshiRankTable.Entry("fast", "vm1", 1.0, 1),
                new ReshiRankTable.Entry("medium", "vm2", 3.0, 2)));
        Map<String, Double> history = new LinkedHashMap<String, Double>();
        history.put("work", 10.0);
        history.put("slow", 20.0);
        history.put("fast", 5.0);
        history.put("medium", 10.0);
        return new ReshiConfig(table, new ReshiTaskHistory(history));
    }

    private static Job job(int id, String type, int children) {
        Job job = new Job(id, 100L);
        Task task = new Task(id, 100L);
        task.setType(type);
        job.getTaskList().add(task);
        for (int index = 0; index < children; index++) {
            Task child = new Task(100 + id * 10 + index, 100L);
            job.addChild(child);
        }
        return job;
    }

    private static CondorVM vm(int id) {
        return vm(id, 1);
    }

    private static CondorVM vm(int id, int pes) {
        return new CondorVM(id, 0, 1000.0, pes, 512, 1L, 10000L, "Xen", new CloudletSchedulerSpaceShared());
    }
}
