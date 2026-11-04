package org.workflowsim.experiments.reference.p7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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

/** Structural verification of the 17 retained Montage WfInstances v1.5 inputs (B05, 2026-11-04). */
class MontageWfInstancesStructuralTest {

    @BeforeEach
    void quiet() {
        Log.disable();
    }

    @AfterEach
    void restore() {
        Log.enable();
    }

    static Stream<Path> montageInputs() throws Exception {
        Path root = datasetRoot().resolve("wfinstances/v1.5/pegasus/montage");
        List<Path> inputs = new ArrayList<Path>();
        try (Stream<Path> paths = Files.list(root)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(inputs::add);
        }
        if (inputs.size() != 17) {
            throw new IllegalStateException("Expected 17 Montage WfInstances inputs, found " + inputs.size());
        }
        return inputs.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("montageInputs")
    void parsesStructureUnitsAndDeclaredEvidenceBoundary(Path input) throws Exception {
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

    private static Path datasetRoot() {
        return Paths.get(System.getProperty("workflowsim.datasetRoot", "../datasets"))
                .toAbsolutePath()
                .normalize();
    }
}
