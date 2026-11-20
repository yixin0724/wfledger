package org.workflowsim.experiments.fgcs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
import com.google.gson.JsonElement;
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
        try (HeapSampler heapSampler = new HeapSampler(memory)) {
            long start = System.nanoTime();
            SimulationReport report = new SimulationRunner().run(config, platform);
            long simulationNanos = System.nanoTime() - start;
            long auditStart = System.nanoTime();
            AlgorithmMetricAudit.AuditResult audit = AlgorithmMetricAudit.evaluate(report, config, platform, label);
            long auditNanos = System.nanoTime() - auditStart;
            long bytes = 0L;
            long manifestBytes = 0L;
            long metricsBytes = 0L;
            long eventStreamBytes = 0L;
            long lifecycleEvidenceBytes = 0L;
            long writeStart = System.nanoTime();
            try {
                if (artifactDirectory != null) {
                    Files.createDirectories(artifactDirectory);
                    ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                            ExperimentArtifactWriter.write(report, artifactDirectory, "result");
                    bytes = directoryBytes(artifactDirectory);
                    manifestBytes = Files.size(artifacts.getManifest());
                    metricsBytes = Files.size(artifacts.getMetrics());
                    eventStreamBytes = Files.size(artifacts.getEvents());
                    if (artifacts.getStorageLifecycle() != null) {
                        lifecycleEvidenceBytes = Files.size(artifacts.getStorageLifecycle());
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot write FGCS evidence for " + label, exception);
            }
            // R-round2: size accounting ends before memory instrumentation is read.
            long artifactWriteNanos = System.nanoTime() - writeStart;
            long heapPoolPeakSumBytes = 0L;
            for (java.lang.management.MemoryPoolMXBean pool : heapPools) {
                heapPoolPeakSumBytes += pool.getPeakUsage().getUsed();
            }
            long threadAllocatedBytes = threadAllocatedBefore < 0L
                    ? -1L
                    : ((com.sun.management.ThreadMXBean) threads)
                                    .getThreadAllocatedBytes(
                                            Thread.currentThread().getId())
                            - threadAllocatedBefore;
            heapSampler.sampleNow();
            return new Measurement(
                    label,
                    report,
                    audit,
                    simulationNanos,
                    artifactWriteNanos,
                    auditNanos,
                    bytes,
                    manifestBytes,
                    metricsBytes,
                    eventStreamBytes,
                    lifecycleEvidenceBytes,
                    heapBeforeBytes,
                    heapPoolPeakSumBytes,
                    heapSampler.getSampledPeakBytes(),
                    heapSampler.getSampleCount(),
                    threadAllocatedBytes);
        }
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

    /** Samples total heap used at one instant; this deliberately does not aggregate asynchronous pool peaks. */
    private static final class HeapSampler implements AutoCloseable {
        private static final long REQUESTED_INTERVAL_NANOS = 1000000L;
        private final java.lang.management.MemoryMXBean memory;
        private final Thread thread;
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        private volatile boolean running = true;
        private long sampledPeakBytes;
        private long sampleCount;

        private HeapSampler(java.lang.management.MemoryMXBean memory) {
            this.memory = memory;
            sampleNow();
            thread = new Thread(
                    () -> {
                        try {
                            while (running) {
                                sampleNow();
                                Thread.sleep(1L);
                            }
                        } catch (InterruptedException interrupted) {
                            if (running) {
                                failure.compareAndSet(null, interrupted);
                            }
                        } catch (Throwable throwable) {
                            failure.compareAndSet(null, throwable);
                            running = false;
                        }
                    },
                    "fgcs-total-heap-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        private synchronized void sampleNow() {
            long used = memory.getHeapMemoryUsage().getUsed();
            sampledPeakBytes = Math.max(sampledPeakBytes, used);
            sampleCount++;
        }

        private synchronized long getSampledPeakBytes() {
            return sampledPeakBytes;
        }

        private synchronized long getSampleCount() {
            return sampleCount;
        }

        @Override
        public void close() {
            running = false;
            thread.interrupt();
            try {
                thread.join(5000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping FGCS heap sampler", interrupted);
            }
            if (thread.isAlive()) {
                throw new IllegalStateException("FGCS heap sampler did not stop");
            }
            Throwable problem = failure.get();
            if (problem != null) {
                throw new IllegalStateException("FGCS heap sampler failed", problem);
            }
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
        private final long manifestBytes;
        private final long metricsBytes;
        private final long eventStreamBytes;
        private final long lifecycleEvidenceBytes;
        private final long heapBeforeBytes;
        private final long heapPoolPeakSumBytes;
        private final long sampledTotalHeapPeakBytes;
        private final long heapSampleCount;
        private final long threadAllocatedBytes;
        private final JsonObject protocolMetadata = new JsonObject();

        private Measurement(
                String label,
                SimulationReport report,
                AlgorithmMetricAudit.AuditResult audit,
                long simulationNanos,
                long artifactWriteNanos,
                long auditNanos,
                long artifactBytes,
                long manifestBytes,
                long metricsBytes,
                long eventStreamBytes,
                long lifecycleEvidenceBytes,
                long heapBeforeBytes,
                long heapPoolPeakSumBytes,
                long sampledTotalHeapPeakBytes,
                long heapSampleCount,
                long threadAllocatedBytes) {
            this.label = label;
            this.report = report;
            this.audit = audit;
            this.simulationNanos = simulationNanos;
            this.artifactWriteNanos = artifactWriteNanos;
            this.auditNanos = auditNanos;
            this.artifactBytes = artifactBytes;
            this.manifestBytes = manifestBytes;
            this.metricsBytes = metricsBytes;
            this.eventStreamBytes = eventStreamBytes;
            this.lifecycleEvidenceBytes = lifecycleEvidenceBytes;
            this.heapBeforeBytes = heapBeforeBytes;
            this.heapPoolPeakSumBytes = heapPoolPeakSumBytes;
            this.sampledTotalHeapPeakBytes = sampledTotalHeapPeakBytes;
            this.heapSampleCount = heapSampleCount;
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

        /** Adds overhead-only measurements without changing the existing callers' simulation or audit path. */
        public void addProtocolMetadata(JsonObject metadata) {
            for (java.util.Map.Entry<String, JsonElement> entry : metadata.entrySet()) {
                protocolMetadata.add(entry.getKey(), entry.getValue());
            }
        }

        public JsonObject toJson() {
            SimulationMetrics metrics = report.getMetrics();
            JsonObject value = new JsonObject();
            value.addProperty("label", label);
            value.addProperty("makespanSeconds", report.getMakespan());
            boolean eventDependentMetricsAvailable =
                    report.getConfig().isEventRecordingEnabled() && metrics.getTrueSlowdownObservationCount() > 0;
            // R-round2: VM submission is not dependency-ready time. Disabled event recording therefore
            // makes these event-derived observations unavailable rather than numeric zero measurements.
            value.addProperty(
                    "meanWaitingSeconds",
                    eventDependentMetricsAvailable
                            ? Double.valueOf(metrics.getMeanComputeTotalWaitingTimeSeconds())
                            : null);
            value.addProperty(
                    "meanTrueSlowdown",
                    eventDependentMetricsAvailable ? Double.valueOf(metrics.getMeanComputeTrueSlowdown()) : null);
            value.addProperty("meanWaitingSecondsAvailable", eventDependentMetricsAvailable);
            value.addProperty("meanTrueSlowdownAvailable", eventDependentMetricsAvailable);
            value.addProperty(
                    "eventDependentMetricAvailability",
                    eventDependentMetricsAvailable
                            ? "AVAILABLE"
                            : !report.getConfig().isEventRecordingEnabled()
                                    ? "UNAVAILABLE_EVENT_RECORDING_DISABLED"
                                    : "UNAVAILABLE_NO_TRUE_SLOWDOWN_OBSERVATIONS");
            value.addProperty("totalWaitingTimeObservationCount", metrics.getTotalWaitingTimeObservationCount());
            value.addProperty("trueSlowdownObservationCount", metrics.getTrueSlowdownObservationCount());
            value.addProperty("throughputJobsPerSecond", metrics.getComputeJobOutcomeThroughputPerSecond());
            value.addProperty("meanVmUtilization", metrics.getMeanVmModeledIntervalUtilization());
            value.addProperty("energyJoules", report.getEnergyAccounting().getTotalEnergyJoules());
            value.addProperty(
                    "energyDelayProduct", report.getEnergyAccounting().getEnergyMakespanProductJouleSeconds());
            value.addProperty("modeledProcessingCost", metrics.getTotalModeledProcessingCost());
            value.addProperty("logicalTasks", metrics.getLogicalTaskCount());
            value.addProperty("completedTasks", metrics.getSuccessfullyCompletedLogicalTaskCount());
            value.addProperty("simulationNanos", simulationNanos);
            value.addProperty("serializationAndSizeAccountingNanos", artifactWriteNanos);
            // Retained as a compatibility alias for older FGCS callers; both values have the same timed scope.
            value.addProperty("artifactWriteNanos", artifactWriteNanos);
            value.addProperty("auditNanos", auditNanos);
            value.addProperty("eventCount", report.getEvents().size());
            value.addProperty("artifactBytes", artifactBytes);
            value.addProperty("manifestBytes", manifestBytes);
            value.addProperty("metricsBytes", metricsBytes);
            value.addProperty("eventStreamBytes", eventStreamBytes);
            value.addProperty("lifecycleEvidenceBytes", lifecycleEvidenceBytes);
            value.addProperty("lifecycleEvidenceAvailable", report.getStorageLifecycleEvidence() != null);
            value.addProperty("heapBeforeBytes", heapBeforeBytes);
            value.addProperty("heapPoolPeakSumBytes", heapPoolPeakSumBytes);
            value.addProperty("sampledTotalHeapPeakBytes", sampledTotalHeapPeakBytes);
            value.addProperty("heapSampleCount", heapSampleCount);
            value.addProperty("heapSampleRequestedIntervalNanos", HeapSampler.REQUESTED_INTERVAL_NANOS);
            value.addProperty(
                    "sampledTotalHeapPeakScope",
                    "SAMPLED_APPROXIMATION_OF_SIMULTANEOUS_TOTAL_HEAP_USED_DURING_SIMULATION_"
                            + "SERIALIZATION_AND_IN_MEMORY_AUDIT_NOT_TRUE_PEAK_OR_MINIMUM_MEMORY");
            value.addProperty(
                    "heapPoolPeakSumScope", "SUM_OF_INDEPENDENT_HEAP_POOL_PEAKS_NOT_SIMULTANEOUS_NOT_MINIMUM_MEMORY");
            value.addProperty(
                    "threadAllocatedBytes", threadAllocatedBytes < 0L ? null : Long.valueOf(threadAllocatedBytes));
            value.addProperty("energyResidual", audit.getEnergyResidualJoules());
            value.addProperty("costResidual", audit.getCostResidual());
            value.addProperty("makespanResidual", audit.getMakespanResidualSeconds());
            value.addProperty("tailResidual", audit.getTailResidualSeconds());
            value.addProperty("dependencyResidual", audit.getDependencyResidualSeconds());
            value.addProperty("completed", report.isWorkflowCompletedSuccessfully());
            value.addProperty(
                    "planningAlgorithm",
                    report.getConfig().getPlanningAlgorithm().name());
            value.addProperty(
                    "schedulingAlgorithm",
                    report.getConfig().getSchedulingAlgorithm().name());
            value.addProperty(
                    "dataMovementModel",
                    report.getConfig().getDataMovementModel().getKind().name());
            value.addProperty("fileSystem", report.getConfig().getFileSystem().name());
            value.addProperty("eventRecordingEnabled", report.getConfig().isEventRecordingEnabled());
            value.addProperty(
                    "networkEvidenceMode",
                    report.getConfig().getNetworkEvidenceConfig().getMode().name());
            value.addProperty("randomSeed", report.getConfig().getRandomSeed());
            value.addProperty("vmCount", report.getPlatform().getVms().size());
            value.addProperty("eventRecorderCap", report.getConfig().getEventRecorderCap());
            JsonObject mapping = new JsonObject();
            for (java.util.Map.Entry<Integer, Integer> entry :
                    audit.getMapping().entrySet()) {
                mapping.addProperty(String.valueOf(entry.getKey()), entry.getValue());
            }
            value.add("mapping", mapping);
            for (java.util.Map.Entry<String, JsonElement> entry : protocolMetadata.entrySet()) {
                if (value.has(entry.getKey())) {
                    throw new IllegalStateException("Protocol metadata collides with base field " + entry.getKey());
                }
                value.add(entry.getKey(), entry.getValue());
            }
            return value;
        }
    }
}
