package org.workflowsim.experiments.fgcs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.workflowsim.data.DataMovementModel;
import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationMetrics;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.experiments.audit.AlgorithmMetricAudit;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class FgcStudySupport {

    private FgcStudySupport() {}

    public static SimulationConfig.Builder base(String workflow, int vmCount) {
        return SimulationConfig.builder(workflow, vmCount)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .costModel(Parameters.CostModel.VM)
                .runtimeReferenceMips(1000.0)
                .eventRecorderCap(200000)
                .randomSeed(7L);
    }

    public static Measurement run(
            String label, SimulationConfig config, PlatformProfile platform, Path artifactDirectory)
            throws SimulationExecutionException {
        java.lang.management.MemoryMXBean memory = java.lang.management.ManagementFactory.getMemoryMXBean();
        long heapBeforeBytes = memory.getHeapMemoryUsage().getUsed();
        java.lang.management.ThreadMXBean threads = java.lang.management.ManagementFactory.getThreadMXBean();
        long threadAllocatedBefore = threads instanceof com.sun.management.ThreadMXBean
                ? ((com.sun.management.ThreadMXBean) threads)
                        .getThreadAllocatedBytes(Thread.currentThread().getId())
                : -1L;
        java.util.List<java.lang.management.MemoryPoolMXBean> heapPools =
                new java.util.ArrayList<java.lang.management.MemoryPoolMXBean>();
        for (java.lang.management.MemoryPoolMXBean pool :
                java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == java.lang.management.MemoryType.HEAP) {
                pool.resetPeakUsage();
                heapPools.add(pool);
            }
        }
        long start = System.nanoTime();
        SimulationReport report = new SimulationRunner().run(config, platform);
        long simulationNanos = System.nanoTime() - start;
        long auditStart = System.nanoTime();
        AlgorithmMetricAudit.AuditResult audit = AlgorithmMetricAudit.evaluate(report, config, platform, label);
        long auditNanos = System.nanoTime() - auditStart;
        long bytes = 0L;
        long writeStart = System.nanoTime();
        try {
            if (artifactDirectory != null) {
                Files.createDirectories(artifactDirectory);
                ExperimentArtifactWriter.write(report, artifactDirectory, "result");
                bytes = directoryBytes(artifactDirectory);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write FGCS evidence for " + label, exception);
        }
        long peakHeapBytes = 0L;
        for (java.lang.management.MemoryPoolMXBean pool : heapPools) {
            peakHeapBytes += pool.getPeakUsage().getUsed();
        }
        long threadAllocatedBytes = threadAllocatedBefore < 0L
                ? -1L
                : ((com.sun.management.ThreadMXBean) threads)
                                .getThreadAllocatedBytes(Thread.currentThread().getId())
                        - threadAllocatedBefore;
        return new Measurement(
                label,
                report,
                audit,
                simulationNanos,
                System.nanoTime() - writeStart,
                auditNanos,
                bytes,
                heapBeforeBytes,
                peakHeapBytes,
                threadAllocatedBytes);
    }

    public static void writeResults(Path output, List<Measurement> measurements) throws IOException {
        JsonArray results = new JsonArray();
        for (Measurement measurement : measurements) {
            results.add(measurement.toJson());
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(
                output,
                (new GsonBuilder().setPrettyPrinting().create().toJson(results) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static long directoryBytes(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .sum();
        }
    }

    public static final class Measurement {
        private final String label;
        private final SimulationReport report;
        private final AlgorithmMetricAudit.AuditResult audit;
        private final long simulationNanos;
        private final long artifactWriteNanos;
        private final long auditNanos;
        private final long artifactBytes;
        private final long heapBeforeBytes;
        private final long peakHeapBytes;
        private final long threadAllocatedBytes;

        private Measurement(
                String label,
                SimulationReport report,
                AlgorithmMetricAudit.AuditResult audit,
                long simulationNanos,
                long artifactWriteNanos,
                long auditNanos,
                long artifactBytes,
                long heapBeforeBytes,
                long peakHeapBytes,
                long threadAllocatedBytes) {
            this.label = label;
            this.report = report;
            this.audit = audit;
            this.simulationNanos = simulationNanos;
            this.artifactWriteNanos = artifactWriteNanos;
            this.auditNanos = auditNanos;
            this.artifactBytes = artifactBytes;
            this.heapBeforeBytes = heapBeforeBytes;
            this.peakHeapBytes = peakHeapBytes;
            this.threadAllocatedBytes = threadAllocatedBytes;
        }

        public SimulationReport getReport() {
            return report;
        }

        public AlgorithmMetricAudit.AuditResult getAudit() {
            return audit;
        }

        public long getSimulationNanos() {
            return simulationNanos;
        }

        public long getArtifactWriteNanos() {
            return artifactWriteNanos;
        }

        public long getAuditNanos() {
            return auditNanos;
        }

        public long getArtifactBytes() {
            return artifactBytes;
        }

        public String getLabel() {
            return label;
        }

        public JsonObject toJson() {
            SimulationMetrics metrics = report.getMetrics();
            JsonObject value = new JsonObject();
            value.addProperty("label", label);
            value.addProperty("makespanSeconds", report.getMakespan());
            value.addProperty("meanWaitingSeconds", metrics.getMeanComputeTotalWaitingTimeSeconds());
            value.addProperty("meanTrueSlowdown", metrics.getMeanComputeTrueSlowdown());
            value.addProperty("throughputJobsPerSecond", metrics.getComputeJobOutcomeThroughputPerSecond());
            value.addProperty("meanVmUtilization", metrics.getMeanVmModeledIntervalUtilization());
            value.addProperty("energyJoules", report.getEnergyAccounting().getTotalEnergyJoules());
            value.addProperty(
                    "energyDelayProduct", report.getEnergyAccounting().getEnergyMakespanProductJouleSeconds());
            value.addProperty("modeledProcessingCost", metrics.getTotalModeledProcessingCost());
            value.addProperty("logicalTasks", metrics.getLogicalTaskCount());
            value.addProperty("completedTasks", metrics.getSuccessfullyCompletedLogicalTaskCount());
            value.addProperty("simulationNanos", simulationNanos);
            value.addProperty("artifactWriteNanos", artifactWriteNanos);
            value.addProperty("auditNanos", auditNanos);
            value.addProperty("eventCount", report.getEvents().size());
            value.addProperty("artifactBytes", artifactBytes);
            value.addProperty("heapBeforeBytes", heapBeforeBytes);
            value.addProperty("peakHeapBytes", peakHeapBytes);
            value.addProperty("threadAllocatedBytes", threadAllocatedBytes);
            value.addProperty("energyResidual", audit.getEnergyResidualJoules());
            value.addProperty("costResidual", audit.getCostResidual());
            value.addProperty("makespanResidual", audit.getMakespanResidualSeconds());
            value.addProperty("tailResidual", audit.getTailResidualSeconds());
            value.addProperty("dependencyResidual", audit.getDependencyResidualSeconds());
            value.addProperty("completed", report.isWorkflowCompletedSuccessfully());
            JsonObject mapping = new JsonObject();
            for (java.util.Map.Entry<Integer, Integer> entry :
                    audit.getMapping().entrySet()) {
                mapping.addProperty(String.valueOf(entry.getKey()), entry.getValue());
            }
            value.add("mapping", mapping);
            return value;
        }
    }
}
