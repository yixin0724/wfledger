package org.workflowsim.experiments.fgcs.energy;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.experiments.fgcs.FgcDatasets;
import org.workflowsim.experiments.fgcs.FgcPlatforms;
import org.workflowsim.experiments.fgcs.FgcStudySupport;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Compares tier policies under identical declared deadlines and feasibility rules. */
public final class EnergyDeadlineStudy {

    private static final double[] DEADLINE_FACTORS = {1.0, 1.1, 1.2, 1.5, 2.0};

    private EnergyDeadlineStudy() {}

    public static long deadline(double defaultMakespan, double factor) {
        if (!(defaultMakespan > 0.0)
                || !Double.isFinite(defaultMakespan)
                || !(factor >= 1.0)
                || !Double.isFinite(factor)) {
            throw new IllegalArgumentException("Deadline requires positive finite makespan and factor >= 1");
        }
        return (long) Math.ceil(defaultMakespan * factor);
    }

    public static List<JsonObject> runWorkflow(String workflow) throws SimulationExecutionException {
        SimulationConfig baselineConfig = FgcStudySupport.base(FgcDatasets.real(workflow), 4)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        FgcStudySupport.Measurement baseline = FgcStudySupport.run(
                workflow + "-deadline-default", baselineConfig, FgcPlatforms.tiered(4, 1, false), null);
        List<JsonObject> results = new ArrayList<JsonObject>();
        results.add(decorate(baseline, null, null, "DEFAULT", 1.0));
        for (double factor : DEADLINE_FACTORS) {
            long deadline = deadline(baseline.getReport().getMakespan(), factor);
            for (SimulationConfig.FrequencyPolicy policy : new SimulationConfig.FrequencyPolicy[] {
                SimulationConfig.FrequencyPolicy.MIN_POWER, SimulationConfig.FrequencyPolicy.EDP_HEURISTIC
            }) {
                SimulationConfig config = FgcStudySupport.base(FgcDatasets.real(workflow), 4)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .frequencyPolicy(policy)
                        .deadline(deadline)
                        .build();
                FgcStudySupport.Measurement result = FgcStudySupport.run(
                        workflow + "-deadline-" + factor + "-" + policy,
                        config,
                        FgcPlatforms.tiered(4, 1, false),
                        null);
                results.add(decorate(
                        result, deadline, result.getReport().getMakespan() <= deadline + 1e-9, policy.name(), factor));
            }
            SimulationConfig dvfs = FgcStudySupport.base(FgcDatasets.real(workflow), 4)
                    .planningAlgorithm(Parameters.PlanningAlgorithm.DVFS_SLACK)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.DVFS_SLACK)
                    .deadline(deadline)
                    .build();
            FgcStudySupport.Measurement result = FgcStudySupport.run(
                    workflow + "-deadline-" + factor + "-DVFS_SLACK", dvfs, FgcPlatforms.tiered(4, 1, false), null);
            results.add(decorate(
                    result, deadline, result.getReport().getMakespan() <= deadline + 1e-9, "DVFS_SLACK", factor));
        }
        return results;
    }

    private static JsonObject decorate(
            FgcStudySupport.Measurement result, Long deadline, Boolean feasible, String policy, double factor) {
        JsonObject row = result.toJson();
        row.addProperty("deadlineSeconds", deadline);
        row.addProperty("deadlineFeasible", feasible);
        row.addProperty("deadlineConstraint", deadline == null ? "NOT_REQUESTED" : "REQUIRED");
        row.addProperty("deadlineFactor", factor);
        row.addProperty("policy", policy);
        return row;
    }

    public static void main(String[] args) throws Exception {
        Path output =
                args.length == 0 ? Paths.get("paper/tmp/fgcs-round2/energy-deadline/results.json") : Paths.get(args[0]);
        JsonArray results = new JsonArray();
        for (String workflow : new String[] {
            FgcDatasets.MONTAGE_50,
            FgcDatasets.CYBERSHAKE_50,
            FgcDatasets.SIPHT_60,
            FgcDatasets.INSPIRAL_50,
            FgcDatasets.EPIGENOMICS_46
        }) {
            for (JsonObject row : runWorkflow(workflow)) {
                results.add(row);
            }
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        java.nio.file.Files.write(
                output,
                (new GsonBuilder().setPrettyPrinting().create().toJson(results) + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("FGCS common-deadline energy runs=" + results.size());
    }
}
