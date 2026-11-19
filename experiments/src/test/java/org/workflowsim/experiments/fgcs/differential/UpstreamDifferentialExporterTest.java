package org.workflowsim.experiments.fgcs.differential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class UpstreamDifferentialExporterTest {

    @TempDir
    Path directory;

    @Test
    void exporterWritesCompleteCurrentObservationCases() throws Exception {
        Path output = directory.resolve("current.json");
        UpstreamDifferentialExporter.main(new String[] {output.toString()});
        JsonArray rows = JsonParser.parseString(
                        new String(java.nio.file.Files.readAllBytes(output), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonArray();
        assertEquals(12, rows.size());
        for (com.google.gson.JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            assertTrue(row.get("completed").getAsBoolean());
            assertEquals(4, row.get("vmCount").getAsInt());
            assertEquals(2, row.get("speedRatio").getAsInt());
            assertTrue(row.getAsJsonArray("tasks").size() > 0);
            assertTrue(row.get("label").getAsString().endsWith("-s7"));
        }
    }

    @Test
    void comparatorReportsBothMatchingAndDivergentReferenceObservations() throws Exception {
        java.util.List<org.workflowsim.experiments.fgcs.FgcStudySupport.Measurement> results =
                UpstreamDifferentialStudy.run(directory.resolve("inputs"));
        JsonArray matching = new JsonArray();
        JsonArray divergent = new JsonArray();
        for (org.workflowsim.experiments.fgcs.FgcStudySupport.Measurement result : results) {
            JsonObject expected = new JsonObject();
            expected.addProperty("label", result.getLabel());
            expected.addProperty("makespan", result.getReport().getMakespan());
            JsonObject mapping = new JsonObject();
            for (java.util.Map.Entry<Integer, Integer> entry :
                    result.getAudit().getMapping().entrySet()) {
                mapping.addProperty(String.valueOf(entry.getKey()), entry.getValue());
            }
            expected.add("mapping", mapping);
            matching.add(expected);
            JsonObject changed = expected.deepCopy();
            changed.addProperty("makespan", 0.0);
            changed.add("mapping", new JsonObject());
            divergent.add(changed);
        }
        Path matchingFile = directory.resolve("matching.json");
        Path divergentFile = directory.resolve("divergent.json");
        java.nio.file.Files.write(matchingFile, matching.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(
                divergentFile, divergent.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path matchingOutput = directory.resolve("matching-diff.json");
        Path divergentOutput = directory.resolve("divergent-diff.json");
        UpstreamDifferentialStudy.compareToUpstream(results, matchingFile, matchingOutput);
        UpstreamDifferentialStudy.compareToUpstream(results, divergentFile, divergentOutput);
        JsonArray same = JsonParser.parseString(new String(
                        java.nio.file.Files.readAllBytes(matchingOutput), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonArray();
        JsonArray different = JsonParser.parseString(new String(
                        java.nio.file.Files.readAllBytes(divergentOutput), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonArray();
        assertEquals(results.size(), same.size());
        for (int index = 0; index < same.size(); index++) {
            assertEquals(
                    0.0,
                    same.get(index).getAsJsonObject().get("makespanResidual").getAsDouble(),
                    1.0e-12);
            assertTrue(same.get(index).getAsJsonObject().get("mappingMatches").getAsBoolean());
            assertTrue(different
                            .get(index)
                            .getAsJsonObject()
                            .get("makespanResidual")
                            .getAsDouble()
                    > 0.0);
            assertEquals(
                    false,
                    different.get(index).getAsJsonObject().get("mappingMatches").getAsBoolean());
        }
        Path missing = directory.resolve("missing.json");
        java.nio.file.Files.write(missing, "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> UpstreamDifferentialStudy.compareToUpstream(
                        results, missing, directory.resolve("missing-labels.json")));
    }
}
