package org.workflowsim.experiments.fgcs.recovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.experiments.fgcs.FgcDatasets;
import org.workflowsim.experiments.fgcs.FgcPlatforms;
import org.workflowsim.experiments.fgcs.FgcStudySupport;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.CrchConfig;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Paired CRCH controls with explicit evidence of whether injection actually occurred. */
public final class FailureRecoveryStudy {

    private static final int GENERATOR_DEPTH_SLOTS = 16;
    private static final int ROOT_DEPTH = 1;
    private static final int FASTEST_VM_ID = 4;
    private static final double NO_FAILURE_SCALE = 1000000000.0;

    private FailureRecoveryStudy() {}

    public static List<FgcStudySupport.Measurement> run(Path generatedInputs) throws SimulationExecutionException {
        List<FgcStudySupport.Measurement> results = new ArrayList<FgcStudySupport.Measurement>();
        for (String shape : new String[] {"chain", "fan", "independent"}) {
            try {
                String workflow = FgcDatasets.smallDag(generatedInputs, shape).toString();
                addPair(results, shape, workflow, 0.01);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Cannot generate recovery DAG " + shape, exception);
            }
        }
        for (String workflow : new String[] {FgcDatasets.MONTAGE_50, FgcDatasets.CYBERSHAKE_50, FgcDatasets.SIPHT_60}) {
            // Montage/CyberShake have only 8/4 roots, so root-only injection stays
            // below the retry budget. SIPHT has 42 roots and retains its conservative
            // scale; a zero-injection draw is reported only as an explicit control.
            double scale = FgcDatasets.SIPHT_60.equals(workflow) ? 1000.0 : 1.0;
            addPair(results, workflow.substring(workflow.lastIndexOf('/') + 1), FgcDatasets.real(workflow), scale);
        }
        return results;
    }

    private static void addPair(
            List<FgcStudySupport.Measurement> results, String label, String workflow, double failureScale)
            throws SimulationExecutionException {
        PlatformProfile platform = FgcPlatforms.flat(4, 2);
        for (boolean failures : new boolean[] {false, true}) {
            SimulationConfig config = FgcStudySupport.base(workflow, 4)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.CRCH)
                    .crchConfig(new CrchConfig(1.0, 0.1))
                    .failureModel(failures ? singleVmFailure(failureScale) : FailureModelConfig.disabled())
                    .build();
            if (!failures) {
                FgcStudySupport.Measurement control =
                        FgcStudySupport.run(label + "-no-failure", config, platform, null);
                requireNoInjection(control, false);
                results.add(control);
                continue;
            }

            FgcStudySupport.Measurement enabled = FgcStudySupport.run(label + "-crch-failure", config, platform, null);
            int failedJobs = enabled.getReport().getFailedJobs();
            int retries = enabled.getReport().getMetrics().getRetryJobCreatedCount();
            if (failedJobs == 0 && retries == 0) {
                // R2-FGCS-RECOVERY (2026-11-20): preserve the enabled configuration but do
                // not mislabel an identical event stream as exercised recovery. The fixed
                // seed makes this repeated classification run identical to the discarded probe.
                enabled = FgcStudySupport.run(label + "-crch-no-injection-control", config, platform, null);
                requireNoInjection(enabled, true);
            } else {
                if (failedJobs <= 0 || retries <= 0 || failedJobs != retries) {
                    throw new IllegalStateException("Enabled CRCH row has incomplete recovery evidence: " + label
                            + " failedJobs=" + failedJobs + " retries=" + retries);
                }
            }
            results.add(enabled);
        }
    }

    /**
     * Targets the fastest initial CRCH destination at root depth only.
     *
     * <p>R2-FGCS-RECOVERY (2026-11-20): CRCH selects the fastest idle VM, so the former VM-3-only
     * generator could remain unused for an entire chain or dependency-heavy workflow. Task depths
     * are one-based generator indices; slot zero is padding. CRCH may reselect the failed VM when
     * no alternative is idle, so repeated injections are possible and are counted from actual reports.
     * The fixed protocol's maximum DAG depth (9) fits the declared 16 slots.
     */
    private static FailureModelConfig singleVmFailure(double failureScale) {
        Map<Integer, DistributionSpec[]> specs = new LinkedHashMap<Integer, DistributionSpec[]>();
        for (int vmId : new int[] {1, 2, 3, 4}) {
            DistributionSpec[] depths = new DistributionSpec[GENERATOR_DEPTH_SLOTS];
            Arrays.fill(depths, weibull(NO_FAILURE_SCALE));
            if (vmId == FASTEST_VM_ID) {
                depths[ROOT_DEPTH] = weibull(failureScale);
            }
            specs.put(vmId, depths);
        }
        return FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_CRCH)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_ALL)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_VM_JOB)
                .distributionFamily(DistributionGenerator.DistributionFamily.WEIBULL)
                .generatorSpecsByVmId(specs)
                .maxTotalRetryJobs(20)
                .build();
    }

    private static DistributionSpec weibull(double scale) {
        return DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, scale, 1.0);
    }

    private static void requireNoInjection(FgcStudySupport.Measurement measurement, boolean configured) {
        if (measurement.getReport().getConfig().getFailureModel().isEnabled() != configured
                || measurement.getReport().getFailedJobs() != 0
                || measurement.getReport().getMetrics().getRetryJobCreatedCount() != 0) {
            throw new IllegalStateException("Invalid no-injection control " + measurement.getLabel());
        }
    }

    private static void writeResults(Path output, List<FgcStudySupport.Measurement> measurements) throws IOException {
        JsonArray rows = new JsonArray();
        for (FgcStudySupport.Measurement measurement : measurements) {
            JsonObject row = measurement.toJson();
            String label = measurement.getLabel();
            String classification = label.endsWith("-crch-failure")
                    ? "EXERCISED_FAILURE_RETRY"
                    : label.endsWith("-crch-no-injection-control")
                            ? "ENABLED_NO_INJECTION_CONTROL"
                            : "FAILURE_DISABLED_CONTROL";
            row.addProperty("recoveryClassification", classification);
            row.addProperty(
                    "failureModelEnabled",
                    measurement.getReport().getConfig().getFailureModel().isEnabled());
            row.addProperty("failedJobs", measurement.getReport().getFailedJobs());
            row.addProperty(
                    "retryJobsCreated", measurement.getReport().getMetrics().getRetryJobCreatedCount());
            rows.add(row);
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(
                output,
                (new GsonBuilder().setPrettyPrinting().create().toJson(rows) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0 ? Paths.get("paper/tmp/fgcs-round2/recovery/results.json") : Paths.get(args[0]);
        List<FgcStudySupport.Measurement> results = run(Paths.get("paper/tmp/fgcs-round2/recovery/inputs"));
        writeResults(output, results);
        System.out.println("FGCS recovery runs=" + results.size());
    }
}
