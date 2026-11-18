package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class WfCommonsRuntimeReferenceTest {

    @Test
    void montageExecutionRuntimesConvertIndependentlyToCloudletLengths() throws Exception {
        Path input = Paths.get(
                        "..",
                        "datasets",
                        "wfinstances",
                        "v1.5",
                        "pegasus",
                        "montage",
                        "montage-chameleon-2mass-005d-001.json")
                .toAbsolutePath()
                .normalize();
        JsonObject document = JsonParser.parseString(new String(Files.readAllBytes(input), StandardCharsets.UTF_8))
                .getAsJsonObject();
        Map<String, Double> expectedRuntimes = new HashMap<String, Double>();
        com.google.gson.JsonArray specificationTasks = document.getAsJsonObject("workflow")
                .getAsJsonObject("specification")
                .getAsJsonArray("tasks");
        for (JsonElement element : document.getAsJsonObject("workflow")
                .getAsJsonObject("execution")
                .getAsJsonArray("tasks")) {
            JsonObject task = element.getAsJsonObject();
            expectedRuntimes.put(
                    task.get("id").getAsString(),
                    Double.valueOf(task.get("runtimeInSeconds").getAsDouble()));
        }
        try (SimulationSession session = SimulationSession.open(
                SimulationConfig.builder(input.toString(), 1).build())) {
            WfCommonsJsonParser.ParseResult result = new WfCommonsJsonParser().parse(input.toString(), 0, 0);
            assertEquals(58, result.getTasks().size());
            for (int index = 0; index < result.getTasks().size(); index++) {
                Task task = result.getTasks().get(index);
                String sourceId = specificationTasks
                        .get(index)
                        .getAsJsonObject()
                        .get("id")
                        .getAsString();
                long expected =
                        Math.max(100L, (long) (expectedRuntimes.get(sourceId).doubleValue() * 1000.0));
                assertEquals(expected, task.getCloudletLength(), "runtime conversion for " + sourceId);
            }
        }
    }
}
