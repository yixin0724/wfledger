package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.cloudbus.cloudsim.Cloudlet;
import org.junit.jupiter.api.Test;
import org.workflowsim.utils.Parameters.FileType;

class TaskProcessingCostTest {

    @Test
    void pricesSubMegabyteRealInputsContinuouslyAndDoesNotBillProducedOutputs() throws Exception {
        Task task = new Task(1, 100L);
        task.setCloudletStatus(Cloudlet.SUCCESS);
        task.setResourceParameter(0, 0.0, 4.0);
        task.addFile(file("input", 500_000.0, FileType.INPUT));
        task.addFile(file("output", 250_000.0, FileType.OUTPUT));

        assertEquals(500_000.0, task.getModeledDeclaredFileBytes(), 0.0);
        assertEquals(2.0, task.getProcessingCost(), 1.0e-12);
    }

    @Test
    void chainWithSharedIntermediateBillsEachTransferOnce() throws Exception {
        Task stageIn = priced(new Task(0, 110L));
        stageIn.addFile(file("x", 1_000_000.0, FileType.INPUT));
        Task a = priced(new Task(1, 100L));
        a.addFile(file("x", 1_000_000.0, FileType.INPUT));
        a.addFile(file("f", 2_000_000.0, FileType.OUTPUT));
        Task b = priced(new Task(2, 100L));
        b.addFile(file("f", 2_000_000.0, FileType.INPUT));
        b.addFile(file("y", 3_000_000.0, FileType.OUTPUT));
        b.addFile(file("log", 5_000_000.0, FileType.INPUT));
        b.addFile(file("log", 5_000_000.0, FileType.OUTPUT));

        assertEquals(1.0, stageIn.getModeledDeclaredFileBandwidthCost(), 1.0e-12);
        assertEquals(1.0, a.getModeledDeclaredFileBandwidthCost(), 1.0e-12);
        assertEquals(2.0, b.getModeledDeclaredFileBandwidthCost(), 1.0e-12);
        assertEquals(
                4.0,
                stageIn.getModeledDeclaredFileBandwidthCost()
                        + a.getModeledDeclaredFileBandwidthCost()
                        + b.getModeledDeclaredFileBandwidthCost(),
                1.0e-12);
    }

    private static Task priced(Task task) throws Exception {
        task.setCloudletStatus(Cloudlet.SUCCESS);
        task.setResourceParameter(0, 0.0, 1.0);
        return task;
    }

    private static FileItem file(String name, double bytes, FileType type) {
        FileItem file = new FileItem(name, bytes);
        file.setType(type);
        return file;
    }
}
