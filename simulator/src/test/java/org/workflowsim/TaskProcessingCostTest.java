package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.cloudbus.cloudsim.Cloudlet;
import org.junit.jupiter.api.Test;
import org.workflowsim.utils.Parameters.FileType;

class TaskProcessingCostTest {

    /**
     * FGCS-R6 (2026-11-25): only real input bytes are billed, continuously in decimal MB.
     *
     * <p>Hand derivation: 500,000 input bytes at 4 cost units per MB = 0.5 MB x 4 = 2.0. The 250,000-byte
     * output is produced, not transferred into the Job, and is billed by its consumers' reads instead. (Before
     * R6 the output was billed too, giving 0.75 MB x 4 = 3.0.)
     */
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

    /**
     * FGCS-R6 (2026-11-25): a chain A -> B sharing the intermediate file f bills every byte once per transfer.
     *
     * <p>Hand derivation: A reads the external x (1 MB) and writes f (2 MB); B reads f and writes y (3 MB); the
     * stage-in Job moves x (1 MB). Per transfer: stage-in 1 MB, A 1 MB (x), B 2 MB (f), total 4 MB; at 1 cost
     * unit per MB the bandwidth cost is 4.0. The former every-declared-file rule billed A x+f = 3 MB and B
     * f+y = 5 MB, total 9 MB = 1.5 x the 6 MB of unique bytes. An input that the same Task also declares as
     * output (an in-place update) is not a transfer and is not billed, matching
     * {@link FileItem#isRealInputFile}.
     */
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
