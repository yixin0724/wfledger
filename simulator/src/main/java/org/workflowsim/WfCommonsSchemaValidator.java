package org.workflowsim;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Version and shape gate for the supported WfFormat 1.5/1.6 input family (Q05, 2026-11-04).
 * The official schemas are the primary references; this small validator covers their required
 * fields, types, ranges, and the fields consumed by the Java parser. It is not a claim to
 * implement every JSON Schema keyword. Unknown versions and unknown fields fail explicitly
 * instead of being silently discarded by Gson. The project's existing fractional-byte input
 * contract is the documented numeric extension to the official integer file-size type;
 * Nextflow's workflow-level `repo` and `runName` provenance fields are the other explicitly
 * registered compatibility extensions used by the retained WfInstances snapshot.
 */
final class WfCommonsSchemaValidator {

    private static final Set<String> SUPPORTED_VERSIONS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("1.5", "1.6")));

    private WfCommonsSchemaValidator() {}

    static void validate(JsonObject root) {
        requireKeys(root, "name", "schemaVersion", "workflow");
        allowKeys(root, "name", "description", "createdAt", "schemaVersion", "runtimeSystem", "author", "workflow");
        nonEmptyString(root, "name", "WfFormat name");
        String version = nonEmptyString(root, "schemaVersion", "WfFormat schemaVersion");
        if (!SUPPORTED_VERSIONS.contains(version)) {
            throw new WorkflowValidationException(
                    "Unsupported WfFormat schemaVersion " + version + "; supported versions are 1.5 and 1.6");
        }
        if (root.has("runtimeSystem")) {
            JsonObject runtime = object(root, "runtimeSystem", "runtimeSystem");
            requireKeys(runtime, "name", "version");
            allowKeys(runtime, "name", "version", "url");
            nonEmptyString(runtime, "name", "runtimeSystem.name");
            nonEmptyString(runtime, "version", "runtimeSystem.version");
        }
        if (root.has("author")) {
            JsonObject author = object(root, "author", "author");
            requireKeys(author, "name", "email");
            allowKeys(author, "name", "email", "institution", "country");
            nonEmptyString(author, "name", "author.name");
            nonEmptyString(author, "email", "author.email");
        }
        JsonObject workflow = object(root, "workflow", "workflow");
        requireKeys(workflow, "specification");
        allowKeys(workflow, "specification", "execution", "repo", "runName");
        if (workflow.has("repo")) {
            nonEmptyString(workflow, "repo", "workflow.repo");
        }
        if (workflow.has("runName")) {
            nonEmptyString(workflow, "runName", "workflow.runName");
        }
        JsonObject specification = object(workflow, "specification", "workflow.specification");
        requireKeys(specification, "tasks");
        allowKeys(specification, "tasks", "files", "metrics");
        JsonArray tasks = array(specification, "tasks", "workflow.specification.tasks");
        if (tasks.size() == 0) {
            throw new WorkflowValidationException("WfFormat specification must contain at least one task");
        }
        for (JsonElement element : tasks) {
            JsonObject task = object(element, "specification task");
            requireKeys(task, "name", "id", "parents", "children");
            allowKeys(task, "name", "id", "parents", "children", "inputFiles", "outputFiles");
            nonEmptyString(task, "name", "specification task name");
            nonEmptyString(task, "id", "specification task id");
            stringArray(task, "parents");
            stringArray(task, "children");
            if (task.has("inputFiles")) {
                stringArray(task, "inputFiles");
            }
            if (task.has("outputFiles")) {
                stringArray(task, "outputFiles");
            }
        }
        if (specification.has("files")) {
            for (JsonElement element : array(specification, "files", "workflow.specification.files")) {
                JsonObject file = object(element, "specification file");
                requireKeys(file, "id", "sizeInBytes");
                allowKeys(file, "id", "sizeInBytes");
                nonEmptyString(file, "id", "specification file id");
                finiteNumber(file, "sizeInBytes");
                if (file.get("sizeInBytes").getAsDouble() < 0.0) {
                    throw new WorkflowValidationException("WfFormat file size must be non-negative");
                }
            }
        }
        if (specification.has("metrics")) {
            JsonObject metrics = object(specification, "metrics", "workflow.specification.metrics");
            allowKeys(
                    metrics,
                    "numberOfTasks",
                    "numberOfFiles",
                    "sumOfFileSizesInBytes",
                    "numberOfLevels",
                    "minimumWidth",
                    "maximumWidth");
            for (String key : metrics.keySet()) {
                nonNegativeInteger(metrics, key);
            }
        }
        if (workflow.has("execution")) {
            validateExecution(object(workflow, "execution", "workflow.execution"));
        }
    }

    private static void validateExecution(JsonObject execution) {
        requireKeys(execution, "makespanInSeconds", "executedAt", "tasks");
        allowKeys(execution, "makespanInSeconds", "executedAt", "tasks", "machines", "metrics");
        finiteNumber(execution, "makespanInSeconds");
        nonEmptyString(execution, "executedAt", "workflow.execution.executedAt");
        JsonArray tasks = array(execution, "tasks", "workflow.execution.tasks");
        if (tasks.size() == 0) {
            throw new WorkflowValidationException("WfFormat execution must contain at least one task");
        }
        for (JsonElement element : tasks) {
            JsonObject task = object(element, "execution task");
            requireKeys(task, "id", "runtimeInSeconds");
            allowKeys(
                    task,
                    "id",
                    "runtimeInSeconds",
                    "executedAt",
                    "command",
                    "coreCount",
                    "avgCPU",
                    "readBytes",
                    "writtenBytes",
                    "memoryInBytes",
                    "energyInKWh",
                    "avgPowerInW",
                    "priority",
                    "machines");
            nonEmptyString(task, "id", "execution task id");
            finiteNumber(task, "runtimeInSeconds");
            if (task.has("coreCount")) {
                positiveNumber(task, "coreCount");
            }
            for (String key : new String[] {
                "avgCPU", "readBytes", "writtenBytes", "memoryInBytes", "energyInKWh", "avgPowerInW", "priority"
            }) {
                if (task.has(key)) {
                    finiteNumber(task, key);
                }
            }
            if (task.has("machines")) {
                stringArray(task, "machines");
            }
            if (task.has("command")) {
                JsonObject command = object(task, "command", "execution task command");
                allowKeys(command, "program", "arguments");
                if (command.has("program")) {
                    nonEmptyString(command, "program", "execution command program");
                }
                if (command.has("arguments")) {
                    stringArray(command, "arguments");
                }
            }
        }
        if (execution.has("machines")) {
            for (JsonElement element : array(execution, "machines", "workflow.execution.machines")) {
                JsonObject machine = object(element, "execution machine");
                requireKeys(machine, "nodeName");
                allowKeys(machine, "system", "architecture", "nodeName", "release", "memoryInBytes", "cpu");
                nonEmptyString(machine, "nodeName", "execution machine nodeName");
                if (machine.has("system")
                        && !Arrays.asList("linux", "macos", "windows")
                                .contains(machine.get("system").getAsString())) {
                    throw new WorkflowValidationException("Unsupported execution machine system");
                }
                if (machine.has("memoryInBytes")) {
                    positiveInteger(machine, "memoryInBytes");
                }
                if (machine.has("cpu")) {
                    JsonObject cpu = object(machine, "cpu", "execution machine cpu");
                    allowKeys(cpu, "coreCount", "speedInMHz", "vendor");
                    if (cpu.has("coreCount")) {
                        positiveInteger(cpu, "coreCount");
                    }
                    if (cpu.has("speedInMHz")) {
                        positiveInteger(cpu, "speedInMHz");
                    }
                }
            }
        }
        if (execution.has("metrics")) {
            JsonObject metrics = object(execution, "metrics", "workflow.execution.metrics");
            allowKeys(metrics, "sumTaskRuntimesInSeconds", "totalNumBytesRead", "totalNumBytesWritten");
            if (metrics.has("sumTaskRuntimesInSeconds")) {
                finiteNumber(metrics, "sumTaskRuntimesInSeconds");
            }
            if (metrics.has("totalNumBytesRead")) {
                nonNegativeInteger(metrics, "totalNumBytesRead");
            }
            if (metrics.has("totalNumBytesWritten")) {
                nonNegativeInteger(metrics, "totalNumBytesWritten");
            }
        }
    }

    private static void requireKeys(JsonObject object, String... keys) {
        for (String key : keys) {
            if (!object.has(key)) {
                throw new WorkflowValidationException("WfFormat missing required field: " + key);
            }
        }
    }

    private static void allowKeys(JsonObject object, String... keys) {
        Set<String> allowed = new HashSet<String>(Arrays.asList(keys));
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                throw new WorkflowValidationException("Unknown WfFormat field: " + key);
            }
        }
    }

    private static JsonObject object(JsonObject parent, String key, String subject) {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonObject()) {
            throw new WorkflowValidationException("WfFormat field must be an object: " + subject);
        }
        return value.getAsJsonObject();
    }

    private static JsonObject object(JsonElement value, String subject) {
        if (value == null || !value.isJsonObject()) {
            throw new WorkflowValidationException("WfFormat value must be an object: " + subject);
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject parent, String key, String subject) {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonArray()) {
            throw new WorkflowValidationException("WfFormat field must be an array: " + subject);
        }
        return value.getAsJsonArray();
    }

    private static String nonEmptyString(JsonObject parent, String key, String subject) {
        JsonElement value = parent.get(key);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isEmpty()) {
            throw new WorkflowValidationException("WfFormat field must be a non-empty string: " + subject);
        }
        return value.getAsString();
    }

    private static void stringArray(JsonObject parent, String key) {
        for (JsonElement element : array(parent, key, key)) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new WorkflowValidationException("WfFormat field must contain strings: " + key);
            }
        }
    }

    private static void finiteNumber(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new WorkflowValidationException("WfFormat field must be numeric: " + key);
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) {
            throw new WorkflowValidationException("WfFormat number must be finite: " + key);
        }
    }

    private static void positiveNumber(JsonObject parent, String key) {
        finiteNumber(parent, key);
        if (parent.get(key).getAsDouble() < 1.0) {
            throw new WorkflowValidationException("WfFormat number must be at least one: " + key);
        }
    }

    private static void nonNegativeInteger(JsonObject parent, String key) {
        try {
            long value = parent.get(key).getAsBigDecimal().longValueExact();
            if (value < 0) {
                throw new WorkflowValidationException("WfFormat integer must be non-negative: " + key);
            }
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new WorkflowValidationException("WfFormat field must be an integer: " + key, failure);
        }
    }

    private static void positiveInteger(JsonObject parent, String key) {
        nonNegativeInteger(parent, key);
        if (parent.get(key).getAsLong() < 1) {
            throw new WorkflowValidationException("WfFormat integer must be positive: " + key);
        }
    }
}
