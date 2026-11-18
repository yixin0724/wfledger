package org.workflowsim.experiments.reference.p7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.WfCommonsJsonParser;
import org.workflowsim.WorkflowDagValidator;
import org.workflowsim.WorkflowInputReport;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class MontageStructuralTestSupport {

    private MontageStructuralTestSupport() {}

    static void verify(Path input) throws Exception {
        JsonObject document = JsonParser.parseString(new String(Files.readAllBytes(input), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject specification = document.getAsJsonObject("workflow").getAsJsonObject("specification");
        JsonObject execution = document.getAsJsonObject("workflow").getAsJsonObject("execution");
        JsonArray specificationTasks = specification.getAsJsonArray("tasks");
        Map<String, Double> runtimes = new HashMap<String, Double>();
        for (JsonElement element : execution.getAsJsonArray("tasks")) {
            JsonObject task = element.getAsJsonObject();
            runtimes.put(
                    task.get("id").getAsString(),
                    Double.valueOf(task.get("runtimeInSeconds").getAsDouble()));
            assertFalse(task.has("coreCount"), "source coreCount must not be inferred for " + input);
            assertFalse(task.has("readBytes"), "source readBytes must not be inferred for " + input);
            assertFalse(task.has("writtenBytes"), "source writtenBytes must not be inferred for " + input);
        }
        Map<String, Double> files = new HashMap<String, Double>();
        for (JsonElement element : specification.getAsJsonArray("files")) {
            JsonObject file = element.getAsJsonObject();
            files.put(
                    file.get("id").getAsString(),
                    Double.valueOf(file.get("sizeInBytes").getAsDouble()));
        }

        try (SimulationSession session = SimulationSession.open(
                SimulationConfig.builder(input.toString(), 1).build())) {
            WfCommonsJsonParser.ParseResult result = new WfCommonsJsonParser().parse(input.toString(), 0, 0);
            assertEquals(specificationTasks.size(), result.getTasks().size());
            assertEquals(specificationTasks.size(), result.getNextTaskId());
            assertEquals(specificationTasks.size(), result.getInputReport().getTaskCount());
            assertEquals(
                    document.get("schemaVersion").getAsString(),
                    result.getInputReport().getDeclaredVersion());
            assertEquals(
                    WorkflowInputReport.Format.WFCOMMONS_JSON,
                    result.getInputReport().getFormat());
            WorkflowDagValidator.validateAndAssignDepths(result.getTasks());

            Map<String, Double> convertedFiles = new HashMap<String, Double>();
            for (int index = 0; index < result.getTasks().size(); index++) {
                Task task = result.getTasks().get(index);
                String sourceId = specificationTasks
                        .get(index)
                        .getAsJsonObject()
                        .get("id")
                        .getAsString();
                assertEquals(index, task.getCloudletId());
                long expectedLength =
                        Math.max(100L, (long) (runtimes.get(sourceId).doubleValue() * 1000.0));
                assertEquals(expectedLength, task.getCloudletLength(), "runtime conversion for " + sourceId);
                for (FileItem file : task.getFileList()) {
                    convertedFiles.putIfAbsent(file.getName(), Double.valueOf(file.getSize()));
                }
            }
            assertEquals(files, convertedFiles, "file identity and byte preservation for " + input);
        }
    }
}
