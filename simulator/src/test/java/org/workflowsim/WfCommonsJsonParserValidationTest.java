package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

class WfCommonsJsonParserValidationTest {

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void initializeParserState() {
        Parameters.reset();
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.SHARED);
    }

    @AfterEach
    void clearParserState() {
        Parameters.reset();
        ReplicaCatalog.reset();
    }

    @Test
    void rejectsMissingWorkflowStructureBeforeCreatingTasks() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"schemaVersion\":\"1.5\"}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> parse("{\"schemaVersion\":\"1.5\",\"workflow\":{"
                        + "\"specification\":{\"tasks\":[],\"files\":[]},\"execution\":{\"tasks\":[]}}}"));
    }

    @Test
    void rejectsDuplicateTaskIdsAndMissingExecutionRuntimes() throws Exception {
        assertThrows(
                IllegalArgumentException.class, () -> parse(document("[{\"id\":\"a\"},{\"id\":\"a\"}]", "[]", "[]")));
        assertThrows(IllegalArgumentException.class, () -> parse(document("[{\"id\":\"a\"}]", "[]", "[]")));
    }

    @Test
    void rejectsInvalidRuntimeAndFileSizeValues() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> parse(document("[{\"id\":\"a\"}]", "[]", "[{\"id\":\"a\",\"runtimeInSeconds\":-1}]")));
        assertThrows(
                WorkflowValidationException.class,
                () -> parse(document(
                        "[{\"id\":\"a\"}]",
                        "[{\"id\":\"f\",\"sizeInBytes\":-1}]",
                        "[{\"id\":\"a\",\"runtimeInSeconds\":1}]")));
    }

    @Test
    void rejectsUndeclaredFilesAndUnknownDependencyEndpoints() throws Exception {
        assertThrows(
                WorkflowValidationException.class,
                () -> parse(document(
                        "[{\"id\":\"a\",\"inputFiles\":[\"missing\"]}]",
                        "[]",
                        "[{\"id\":\"a\",\"runtimeInSeconds\":1}]")));
        assertThrows(
                IllegalArgumentException.class,
                () -> parse(document(
                        "[{\"id\":\"a\",\"parents\":[\"missing\"]}]",
                        "[]",
                        "[{\"id\":\"a\",\"runtimeInSeconds\":1}]")));
    }

    @Test
    void rejectsInconsistentRawParentAndChildDeclarations() throws Exception {
        assertThrows(
                WorkflowValidationException.class,
                () -> parse(document(
                        "[{\"id\":\"a\",\"children\":[\"b\"]},{\"id\":\"b\"}]",
                        "[]",
                        "[{\"id\":\"a\",\"runtimeInSeconds\":1},{\"id\":\"b\",\"runtimeInSeconds\":1}]")));
        assertThrows(
                WorkflowValidationException.class,
                () -> parse(document(
                        "[{\"id\":\"a\"},{\"id\":\"b\",\"parents\":[\"a\"]}]",
                        "[]",
                        "[{\"id\":\"a\",\"runtimeInSeconds\":1},{\"id\":\"b\",\"runtimeInSeconds\":1}]")));
    }

    @Test
    void rejectsCyclesInTheDeclaredTaskGraph() throws Exception {
        assertThrows(
                WorkflowValidationException.class,
                () -> parse(document(
                        "[{\"id\":\"a\",\"parents\":[\"b\"],\"children\":[\"b\"]},"
                                + "{\"id\":\"b\",\"parents\":[\"a\"],\"children\":[\"a\"]}]",
                        "[]",
                        "[{\"id\":\"a\",\"runtimeInSeconds\":1},{\"id\":\"b\",\"runtimeInSeconds\":1}]")));
    }

    @Test
    void rejectsConflictingDuplicateFileAndExecutionTaskDeclarations() throws Exception {

        assertThrows(
                WorkflowValidationException.class,
                () -> parse(document(
                        "[{\"id\":\"a\",\"inputFiles\":[\"f\"]}]",
                        "[{\"id\":\"f\",\"sizeInBytes\":100},{\"id\":\"f\",\"sizeInBytes\":200}]",
                        "[{\"id\":\"a\",\"runtimeInSeconds\":1}]")));

        assertThrows(
                WorkflowValidationException.class,
                () -> parse(document(
                        "[{\"id\":\"a\"}]",
                        "[]",
                        "[{\"id\":\"a\",\"runtimeInSeconds\":1},{\"id\":\"a\",\"runtimeInSeconds\":2}]")));

        parse(document(
                "[{\"id\":\"a\",\"inputFiles\":[\"f\"]}]",
                "[{\"id\":\"f\",\"sizeInBytes\":100},{\"id\":\"f\",\"sizeInBytes\":100}]",
                "[{\"id\":\"a\",\"runtimeInSeconds\":1},{\"id\":\"a\",\"runtimeInSeconds\":1}]"));
    }

    @Test
    void rejectsRuntimeThatOverflowsCloudletLength() throws Exception {
        assertThrows(
                WorkflowValidationException.class,
                () -> parse(document("[{\"id\":\"a\"}]", "[]", "[{\"id\":\"a\",\"runtimeInSeconds\":1.0e308}]")));
    }

    @Test
    void acceptsWfFormat16AndRejectsUnknownVersionsAndFields() throws Exception {
        parse(document("1.6", "[{\"id\":\"a\"}]", "[]", "[{\"id\":\"a\",\"runtimeInSeconds\":1}]"));
        assertThrows(
                WorkflowValidationException.class,
                () -> parse(document("1.7", "[{\"id\":\"a\"}]", "[]", "[{\"id\":\"a\",\"runtimeInSeconds\":1}]")));
        com.google.gson.JsonObject document = com.google.gson.JsonParser.parseString(
                        document("1.6", "[{\"id\":\"a\"}]", "[]", "[{\"id\":\"a\",\"runtimeInSeconds\":1}]"))
                .getAsJsonObject();
        document.addProperty("unrecognizedFutureField", true);
        WorkflowValidationException failure =
                assertThrows(WorkflowValidationException.class, () -> parse(document.toString()));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("unrecognizedFutureField"));
    }

    @Test
    void acceptsRegisteredNextflowProvenanceExtensions() throws Exception {
        com.google.gson.JsonObject document = com.google.gson.JsonParser.parseString(
                        document("1.5", "[{\"id\":\"a\"}]", "[]", "[{\"id\":\"a\",\"runtimeInSeconds\":1}]"))
                .getAsJsonObject();
        document.getAsJsonObject("workflow").addProperty("repo", "https://github.com/nf-core/bacass");
        document.getAsJsonObject("workflow").addProperty("runName", "test-run");
        parse(document.toString());
    }

    private void parse(String contents) throws Exception {
        Path input = temporaryDirectory.resolve("workflow.json");
        Files.write(input, contents.getBytes(StandardCharsets.UTF_8));
        new WfCommonsJsonParser().parse(input.toString(), 0, 0);
    }

    private static String document(String tasks, String files, String executionTasks) {
        return document("1.5", tasks, files, executionTasks);
    }

    private static String document(String version, String tasks, String files, String executionTasks) {
        com.google.gson.JsonArray specificationTasks =
                com.google.gson.JsonParser.parseString(tasks).getAsJsonArray();
        for (com.google.gson.JsonElement element : specificationTasks) {
            com.google.gson.JsonObject task = element.getAsJsonObject();
            if (!task.has("name")) {
                task.addProperty("name", task.get("id").getAsString());
            }
            if (!task.has("parents")) {
                task.add("parents", new com.google.gson.JsonArray());
            }
            if (!task.has("children")) {
                task.add("children", new com.google.gson.JsonArray());
            }
        }
        com.google.gson.JsonObject specification = new com.google.gson.JsonObject();
        specification.add("tasks", specificationTasks);
        specification.add("files", com.google.gson.JsonParser.parseString(files).getAsJsonArray());
        com.google.gson.JsonObject execution = new com.google.gson.JsonObject();
        execution.addProperty("makespanInSeconds", 1.0);
        execution.addProperty("executedAt", "2026-01-01T00:00:00Z");
        execution.add(
                "tasks", com.google.gson.JsonParser.parseString(executionTasks).getAsJsonArray());
        com.google.gson.JsonObject workflow = new com.google.gson.JsonObject();
        workflow.add("specification", specification);
        workflow.add("execution", execution);
        com.google.gson.JsonObject document = new com.google.gson.JsonObject();
        document.addProperty("name", "schema-fixture");
        document.addProperty("schemaVersion", version);
        document.add("workflow", workflow);
        return document.toString();
    }
}
