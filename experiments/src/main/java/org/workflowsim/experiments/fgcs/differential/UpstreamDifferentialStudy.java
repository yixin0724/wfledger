package org.workflowsim.experiments.fgcs.differential;

import java.io.IOException;
import java.nio.file.Files;
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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class UpstreamDifferentialStudy {

    private UpstreamDifferentialStudy() {}

    public static List<FgcStudySupport.Measurement> run(Path generatedInputs) throws SimulationExecutionException {
        List<FgcStudySupport.Measurement> results = new ArrayList<FgcStudySupport.Measurement>();
        for (String shape : new String[] {"chain", "fan", "independent"}) {
            try {
                String workflow = FgcDatasets.smallDag(generatedInputs, shape).toString();
                for (Parameters.SchedulingAlgorithm scheduler : new Parameters.SchedulingAlgorithm[] {
                    Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN, Parameters.SchedulingAlgorithm.STATIC
                }) {
                    for (long seed : new long[] {7L, 11L}) {
                        SimulationConfig config = FgcStudySupport.base(workflow, 4)
                                .planningAlgorithm(
                                        scheduler == Parameters.SchedulingAlgorithm.STATIC
                                                ? Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT
                                                : Parameters.PlanningAlgorithm.INVALID)
                                .schedulingAlgorithm(scheduler)
                                .randomSeed(seed)
                                .build();
                        results.add(FgcStudySupport.run(
                                shape + "-" + scheduler + "-s" + seed, config, FgcPlatforms.flat(4, 2), null));
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot generate differential DAG " + shape, exception);
            }
        }
        for (String workflow : new String[] {FgcDatasets.MONTAGE_50, FgcDatasets.CYBERSHAKE_50, FgcDatasets.SIPHT_60}) {
            for (Parameters.SchedulingAlgorithm scheduler : new Parameters.SchedulingAlgorithm[] {
                Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN, Parameters.SchedulingAlgorithm.STATIC
            }) {
                for (long seed : new long[] {7L, 11L}) {
                    SimulationConfig config = FgcStudySupport.base(FgcDatasets.real(workflow), 4)
                            .planningAlgorithm(
                                    scheduler == Parameters.SchedulingAlgorithm.STATIC
                                            ? Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT
                                            : Parameters.PlanningAlgorithm.INVALID)
                            .schedulingAlgorithm(scheduler)
                            .randomSeed(seed)
                            .build();
                    results.add(FgcStudySupport.run(
                            workflow.substring(workflow.lastIndexOf('/') + 1) + "-" + scheduler + "-s" + seed,
                            config,
                            FgcPlatforms.flat(4, 2),
                            null));
                }
            }
        }
        return results;
    }

    public static void compareToUpstream(List<FgcStudySupport.Measurement> current, Path upstream, Path output)
            throws IOException {
        JsonArray reference = JsonParser.parseString(
                        new String(Files.readAllBytes(upstream), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonArray();
        JsonObject byLabel = new JsonObject();
        for (com.google.gson.JsonElement element : reference) {
            JsonObject item = element.getAsJsonObject();
            byLabel.add(item.get("label").getAsString(), item);
        }
        JsonArray differences = new JsonArray();
        for (FgcStudySupport.Measurement measurement : current) {
            String label = measurement.getLabel();
            if (!byLabel.has(label)) {
                throw new IllegalArgumentException("Missing upstream differential label " + label);
            }
            JsonObject expected = byLabel.get(label).getAsJsonObject();
            JsonObject difference = new JsonObject();
            difference.addProperty("label", label);
            difference.addProperty("currentMakespan", measurement.getReport().getMakespan());
            difference.addProperty("upstreamMakespan", expected.get("makespan").getAsDouble());
            difference.addProperty(
                    "makespanResidual",
                    Math.abs(measurement.getReport().getMakespan()
                            - expected.get("makespan").getAsDouble()));
            difference.addProperty("mappingMatches", mappingMatches(measurement, expected));
            differences.add(difference);
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(
                output,
                (new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(differences) + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static boolean mappingMatches(FgcStudySupport.Measurement measurement, JsonObject expected) {
        JsonObject upstream = expected.getAsJsonObject("mapping");
        if (upstream == null) {
            return false;
        }
        for (java.util.Map.Entry<Integer, Integer> entry :
                measurement.getAudit().getMapping().entrySet()) {
            if (!upstream.has(String.valueOf(entry.getKey()))
                    || upstream.get(String.valueOf(entry.getKey())).getAsInt() != entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) throws Exception {
        Path output =
                args.length == 0 ? Paths.get("paper/tmp/experiments/differential/results.json") : Paths.get(args[0]);
        List<FgcStudySupport.Measurement> results = run(Paths.get("paper/tmp/experiments/differential/inputs"));
        FgcStudySupport.writeResults(output, results);
        if (args.length > 2) {
            compareToUpstream(results, Paths.get(args[1]), Paths.get(args[2]));
        }
        System.out.println("FGCS differential current runs=" + results.size());
    }
}
