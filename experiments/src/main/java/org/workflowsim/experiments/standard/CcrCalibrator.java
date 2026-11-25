package org.workflowsim.experiments.standard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.cloudbus.cloudsim.Consts;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.WorkflowParser;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

/**
 * Communication-to-computation ratio (CCR) control for the legacy SHARED storage model.
 *
 * <p>STD-V1 (2026-11-25). Root cause: in the historical FGCS matrices the CCR was an uncontrolled by-product of
 * each workflow's file sizes (CyberShake about 9, the other families at most 0.13), so the I/O regime was
 * confounded with the application. The standard study declares CCR as a factor and solves the storage rate.
 *
 * <p>Definition (declared; it mirrors the runtime read rule of {@code WorkflowDatacenter} and
 * {@code SharedStorageDagPlanner}, docs/algorithms/CONTRACTS.md "The Compute Envelope Model of SHARED"):
 *
 * <pre>
 *   B           = sum over compute tasks t, over real input files f of t, of bytes(f) / 1e6     [decimal MB]
 *   read(R)     = B / R                                                                         [s]
 *   compute     = sum over compute tasks t of L(t) / meanMips                                   [s]
 *   CCR(R)      = read(R) / compute
 *   R(target)   = B / (target * compute)                                                         (closed form)
 * </pre>
 *
 * <p>A real input file is {@link FileItem#isRealInputFile}: an input not also declared as an output of the same
 * task. {@code L(t)} is the parsed integer MI ({@code runtime * runtimeReferenceMips * runtimeScale}, floored at
 * the parser minimum). The model-generated stage-in Job is excluded, exactly as the runtime charges no envelope
 * read to it. The per-VM integer-MI truncation {@code floor(mips * inputSeconds)} of the runtime envelope is
 * deliberately not part of the definition: it depends on the VM, it is below one MI per task, and including it
 * would make CCR a property of the mapping. The rate is a finite positive double (STD-V1 storage widening), so
 * the achieved CCR equals the target up to binary64 rounding; {@link Calibration#getAchievedCcr()} recomputes it
 * from the solved rate.
 */
public final class CcrCalibrator {

    /** Declared target levels of the standard study. */
    public static final double[] STANDARD_CCR_TARGETS = {0.1, 0.5, 1.0, 2.0, 5.0, 10.0};

    private CcrCalibrator() {}

    /**
     * Parses a workflow under the given configuration's runtime conversion and returns its compute tasks.
     *
     * @param workflowPath input path
     * @param conversion configuration whose runtime reference MIPS and scale define {@code L(t)}
     * @return parsed tasks in parser order
     */
    public static List<Task> parse(String workflowPath, SimulationConfig conversion) {
        SimulationConfig config = SimulationConfig.builder(workflowPath, 1)
                .runtimeReferenceMips(conversion.getRuntimeReferenceMips())
                .runtimeScale(conversion.getRuntimeScale())
                .fileSystem(conversion.getFileSystem())
                .build();
        try (SimulationSession ignored = SimulationSession.open(config)) {
            WorkflowParser parser = new WorkflowParser(0);
            parser.parse();
            return Collections.unmodifiableList(new ArrayList<Task>(parser.getTaskList()));
        }
    }

    /**
     * Sums the real-input megabytes {@code B} in ascending task order.
     *
     * @param tasks parsed tasks
     * @return decimal MB
     */
    public static double realInputMegabytes(List<Task> tasks) {
        double megabytes = 0.0;
        for (Task task : ordered(tasks)) {
            for (FileItem file : task.getFileList()) {
                if (file.isRealInputFile(task.getFileList())) {
                    megabytes += file.getSize() / (double) Consts.MILLION;
                }
            }
        }
        return megabytes;
    }

    /**
     * Sums the compute seconds at the given speed in ascending task order.
     *
     * @param tasks parsed tasks
     * @param mips speed, finite positive
     * @return seconds
     */
    public static double computeSeconds(List<Task> tasks, double mips) {
        requirePositiveFinite(mips, "mips");
        double seconds = 0.0;
        for (Task task : ordered(tasks)) {
            seconds += task.getCloudletLength() / mips;
        }
        return seconds;
    }

    /**
     * Computes {@code CCR(R)}.
     *
     * @param tasks parsed tasks
     * @param storageRateMbPerSecond SHARED rate
     * @param meanMips mean fleet speed
     * @return CCR
     */
    public static double ccr(List<Task> tasks, double storageRateMbPerSecond, double meanMips) {
        requirePositiveFinite(storageRateMbPerSecond, "storage rate");
        return realInputMegabytes(tasks) / storageRateMbPerSecond / computeSeconds(tasks, meanMips);
    }

    /**
     * Solves the storage rate for a target CCR.
     *
     * @param tasks parsed tasks
     * @param targetCcr finite positive target
     * @param meanMips mean fleet speed
     * @return calibration
     * @throws IllegalArgumentException when the workflow reads no real input bytes (CCR is identically zero and
     *     no positive target is reachable) or a value is not finite and positive
     */
    public static Calibration calibrate(List<Task> tasks, double targetCcr, double meanMips) {
        requirePositiveFinite(targetCcr, "target CCR");
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("CCR calibration requires at least one task");
        }
        double megabytes = realInputMegabytes(tasks);
        if (!(megabytes > 0.0)) {
            throw new IllegalArgumentException("Workflow has zero real input bytes: CCR is 0 at every storage rate, "
                    + "so target " + targetCcr + " is unreachable");
        }
        double compute = computeSeconds(tasks, meanMips);
        double rate = megabytes / (targetCcr * compute);
        requirePositiveFinite(rate, "solved storage rate");
        double achieved = megabytes / rate / compute;
        return new Calibration(targetCcr, rate, achieved, megabytes, compute, meanMips);
    }

    private static List<Task> ordered(List<Task> tasks) {
        List<Task> sorted = new ArrayList<Task>(tasks);
        Collections.sort(sorted, (a, b) -> Integer.compare(a.getCloudletId(), b.getCloudletId()));
        return sorted;
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!(value > 0.0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and positive: " + value);
        }
    }

    /** Result of one calibration. */
    public static final class Calibration {
        private final double targetCcr;
        private final double storageRateMbPerSecond;
        private final double achievedCcr;
        private final double realInputMegabytes;
        private final double computeSecondsAtMeanMips;
        private final double meanMips;

        Calibration(
                double targetCcr,
                double storageRateMbPerSecond,
                double achievedCcr,
                double realInputMegabytes,
                double computeSecondsAtMeanMips,
                double meanMips) {
            this.targetCcr = targetCcr;
            this.storageRateMbPerSecond = storageRateMbPerSecond;
            this.achievedCcr = achievedCcr;
            this.realInputMegabytes = realInputMegabytes;
            this.computeSecondsAtMeanMips = computeSecondsAtMeanMips;
            this.meanMips = meanMips;
        }

        public double getTargetCcr() {
            return targetCcr;
        }

        public double getStorageRateMbPerSecond() {
            return storageRateMbPerSecond;
        }

        public double getAchievedCcr() {
            return achievedCcr;
        }

        public double getRealInputMegabytes() {
            return realInputMegabytes;
        }

        public double getComputeSecondsAtMeanMips() {
            return computeSecondsAtMeanMips;
        }

        public double getMeanMips() {
            return meanMips;
        }
    }
}
