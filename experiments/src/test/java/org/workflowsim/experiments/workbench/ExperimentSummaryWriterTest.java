package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class ExperimentSummaryWriterTest {

    @TempDir
    Path directory;

    @Test
    void writesSortedJsonAndEscapedCsv() throws Exception {
        Map<String, Object> second = new LinkedHashMap<String, Object>();
        second.put("workflow", "b.dax");
        second.put("algorithm", "fcfs");
        second.put("candidate", "b__fcfs");
        second.put("seed", 7L);
        second.put("status", "COMPLETED_SUCCESSFULLY");
        second.put("makespanSeconds", 2.0);
        Map<String, Object> first = new LinkedHashMap<String, Object>();
        first.put("workflow", "a.dax");
        first.put("algorithm", "heft");
        first.put("candidate", "a__heft");
        first.put("seed", 7L);
        first.put("status", "FAILED");
        first.put("error", "input, invalid");
        ExperimentSummaryWriter.write("Study", Arrays.asList(second, first), directory);
        JsonObject summary = JsonParser.parseString(
                        new String(Files.readAllBytes(directory.resolve("summary.json")), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(2, summary.get("runCount").getAsInt());
        assertEquals(1, summary.get("successfulRuns").getAsInt());
        assertEquals(
                "a.dax",
                summary.getAsJsonArray("runs")
                        .get(0)
                        .getAsJsonObject()
                        .get("workflow")
                        .getAsString());
        String csv = new String(Files.readAllBytes(directory.resolve("summary.csv")), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("workflow,algorithm,candidate,seed,status"));
        assertTrue(csv.contains("\"input, invalid\""));
        String html = new String(Files.readAllBytes(directory.resolve("summary.html")), StandardCharsets.UTF_8);
        assertTrue(html.contains("a.dax"));
        assertTrue(html.contains("Mean wait (s)"));
        assertTrue(html.contains("EDP (J·s)"));
    }
}
