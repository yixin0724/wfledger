package org.workflowsim.experiments.standard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;

/**
 * STD-V1 (2026-11-25): hand-derived CCR checks on a 3-task chain (definition in the {@link CcrCalibrator}
 * javadoc, which mirrors the SHARED read rule of docs/algorithms/CONTRACTS.md).
 *
 * <p>Chain t1 -> t2 -> t3: t1 = 1000 MI reads external x (2e6 B), writes a (1e6 B); t2 = 2000 MI reads a, writes b
 * (3e6 B); t3 = 3000 MI reads b and declares c (5e6 B) as both input and output, so c is not a real input. Hence
 * B = 2 + 1 + 3 = 6 MB, compute at mean 1250 MIPS = 6000 / 1250 = 4.8 s.
 */
class CcrCalibratorTest {

    private static List<Task> chain() {
        Task t1 = task(1, 1000L);
        t1.addFile(file("x", 2_000_000.0, Parameters.FileType.INPUT));
        t1.addFile(file("a", 1_000_000.0, Parameters.FileType.OUTPUT));
        Task t2 = task(2, 2000L);
        t2.addFile(file("a", 1_000_000.0, Parameters.FileType.INPUT));
        t2.addFile(file("b", 3_000_000.0, Parameters.FileType.OUTPUT));
        Task t3 = task(3, 3000L);
        t3.addFile(file("b", 3_000_000.0, Parameters.FileType.INPUT));
        t3.addFile(file("c", 5_000_000.0, Parameters.FileType.INPUT));
        t3.addFile(file("c", 5_000_000.0, Parameters.FileType.OUTPUT));
        return Arrays.asList(t3, t1, t2);
    }

    @Test
    void ccrOfTheChainMatchesTheHandDerivation() {
        List<Task> tasks = chain();
        assertEquals(6.0, CcrCalibrator.realInputMegabytes(tasks), 1.0e-12);
        assertEquals(4.8, CcrCalibrator.computeSeconds(tasks, 1250.0), 1.0e-12);
        // R = 10 MB/s: read 0.6 s, CCR = 0.6 / 4.8 = 0.125.
        assertEquals(0.125, CcrCalibrator.ccr(tasks, 10.0, 1250.0), 1.0e-12);
    }

    @Test
    void closedFormRateReachesEveryStandardTargetExactly() {
        List<Task> tasks = chain();
        // R = B / (target * compute) = 6 / (target * 4.8): target 1 -> 1.25 MB/s; target 10 -> 0.125 MB/s.
        assertEquals(1.25, CcrCalibrator.calibrate(tasks, 1.0, 1250.0).getStorageRateMbPerSecond(), 1.0e-12);
        assertEquals(0.125, CcrCalibrator.calibrate(tasks, 10.0, 1250.0).getStorageRateMbPerSecond(), 1.0e-12);
        for (double target : CcrCalibrator.STANDARD_CCR_TARGETS) {
            CcrCalibrator.Calibration calibration = CcrCalibrator.calibrate(tasks, target, 1250.0);
            assertEquals(target, calibration.getAchievedCcr(), 1.0e-12 * target);
            assertEquals(target, CcrCalibrator.ccr(tasks, calibration.getStorageRateMbPerSecond(), 1250.0), 1.0e-12);
        }
    }

    @Test
    void workflowWithoutRealInputsCannotReachAPositiveCcr() {
        Task only = task(1, 1000L);
        only.addFile(file("o", 1_000_000.0, Parameters.FileType.OUTPUT));
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> CcrCalibrator.calibrate(Arrays.asList(only), 1.0, 1250.0));
        assertTrue(failure.getMessage().contains("zero real input bytes"), failure.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CcrCalibrator.calibrate(chain(), Double.NaN, 1250.0));
        assertThrows(IllegalArgumentException.class, () -> CcrCalibrator.calibrate(chain(), 0.0, 1250.0));
    }

    private static Task task(int id, long mi) {
        return new Task(id, mi);
    }

    private static FileItem file(String name, double bytes, Parameters.FileType type) {
        FileItem file = new FileItem(name, bytes);
        file.setType(type);
        return file;
    }
}
