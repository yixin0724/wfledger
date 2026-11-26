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

/** Communication-to-computation ratio (CCR) control for the legacy SHARED storage model. */
public final class CcrCalibrator {

    public static final double[] STANDARD_CCR_TARGETS = {0.1, 0.5, 1.0, 2.0, 5.0, 10.0};

    private CcrCalibrator() {}

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

    public static double computeSeconds(List<Task> tasks, double mips) {
        requirePositiveFinite(mips, "mips");
        double seconds = 0.0;
        for (Task task : ordered(tasks)) {
            seconds += task.getCloudletLength() / mips;
        }
        return seconds;
    }

    public static double ccr(List<Task> tasks, double storageRateMbPerSecond, double meanMips) {
        requirePositiveFinite(storageRateMbPerSecond, "storage rate");
        return realInputMegabytes(tasks) / storageRateMbPerSecond / computeSeconds(tasks, meanMips);
    }

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
