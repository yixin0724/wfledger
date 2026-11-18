/*
 * WfCommons WfFormat JSON parser for WorkflowSim.
 *
 * Ported from https://github.com/yixin0724/workflowsim-fat-tree
 * (sources/org/workflowsim/WfCommonsJsonParser.java). The upstream
 * repository is licensed GPL-3.0-only; the author of both repositories
 * has approved reuse here.
 */
package org.workflowsim;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConstants;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

public final class WfCommonsJsonParser {

    private static final long MIN_CLOUDLET_LENGTH = SimulationConstants.MIN_TASK_LENGTH_MI;

    private final Gson gson;

    public WfCommonsJsonParser() {
        this.gson = new Gson();
    }

    public ParseResult parse(String path, int userId, int firstTaskId) throws IOException {
        return parse(path, userId, firstTaskId, null);
    }

    ParseResult parse(String path, int userId, int firstTaskId, String fileNamespace) throws IOException {
        if (path == null || path.trim().length() == 0) {
            throw new IllegalArgumentException("WfCommons JSON path cannot be empty");
        }
        if (firstTaskId < 0) {
            throw new IllegalArgumentException("firstTaskId cannot be negative");
        }

        File file = new File(path);
        if (!file.exists()) {
            throw new IOException("WfCommons JSON file does not exist: " + file.getAbsolutePath());
        }

        WfCommonsInstance instance;
        Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
        try {

            com.google.gson.JsonObject document =
                    com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            WfCommonsSchemaValidator.validate(document);
            instance = gson.fromJson(document, WfCommonsInstance.class);
        } catch (JsonParseException e) {
            throw new IOException("Invalid WfCommons JSON: " + file.getAbsolutePath(), e);
        } finally {
            reader.close();
        }

        validateInstance(instance, file);
        WorkflowInputReport report = new WorkflowInputReport(
                file.getPath(), WorkflowInputReport.Format.WFCOMMONS_JSON, instance.schemaVersion);
        Map<String, Double> fileSizes = indexFileSizes(instance.workflow.specification.files);
        Map<String, Double> runtimes = indexRuntimes(instance.workflow.execution.tasks);
        Map<String, SpecTask> specTasks = indexSpecTasks(instance.workflow.specification.tasks);
        Map<String, Task> tasksByWfCommonsId = new LinkedHashMap<>();
        Map<String, FileItem> fileItemsByNameAndType = new HashMap<>();

        Map<String, FileItem> firstDeclaredInputs = new LinkedHashMap<>();
        List<Task> tasks = new ArrayList<>();

        int nextTaskId = firstTaskId;
        for (SpecTask specTask : instance.workflow.specification.tasks) {
            Task task = buildTask(
                    nextTaskId++,
                    specTask,
                    runtimes,
                    fileSizes,
                    userId,
                    fileNamespace,
                    fileItemsByNameAndType,
                    firstDeclaredInputs,
                    report);
            tasksByWfCommonsId.put(specTask.id, task);
            tasks.add(task);
        }

        validateDeclaredDependencySymmetry(instance.workflow.specification.tasks, specTasks);
        connectDependencies(instance.workflow.specification.tasks, specTasks, tasksByWfCommonsId);
        WorkflowDagValidator.validateAndAssignDepths(tasks);

        report.setTaskCount(tasks.size());

        commitFirstDeclaredInputs(firstDeclaredInputs);

        Log.printLine("Parsed WfCommons JSON " + file.getName() + " with " + tasks.size() + " tasks.");
        return new ParseResult(tasks, nextTaskId, report);
    }

    private Task buildTask(
            int taskId,
            SpecTask specTask,
            Map<String, Double> runtimes,
            Map<String, Double> fileSizes,
            int userId,
            String fileNamespace,
            Map<String, FileItem> fileItemsByNameAndType,
            Map<String, FileItem> firstDeclaredInputs,
            WorkflowInputReport report) {
        double runtimeSeconds = getRequiredRuntimeSeconds(runtimes, specTask.id);
        long length = toCloudletLength(runtimeSeconds, specTask.id, report);
        Task task = new Task(taskId, length);
        task.setType(specTask.name == null ? "" : specTask.name);
        task.setUserId(userId);

        List<FileItem> taskFiles = new ArrayList<>();
        addFiles(
                taskFiles,
                fileItemsByNameAndType,
                specTask.inputFiles,
                fileSizes,
                FileType.INPUT,
                fileNamespace,
                firstDeclaredInputs);
        addFiles(
                taskFiles,
                fileItemsByNameAndType,
                specTask.outputFiles,
                fileSizes,
                FileType.OUTPUT,
                fileNamespace,
                firstDeclaredInputs);

        for (FileItem taskFile : taskFiles) {
            task.addRequiredFile(taskFile.getName());
        }
        task.setFileList(taskFiles);
        return task;
    }

    private static void validateInstance(WfCommonsInstance instance, File file) throws IOException {
        if (instance == null) {
            throw new IOException("WfCommons JSON is empty: " + file.getAbsolutePath());
        }
        if (instance.workflow == null) {
            throw new IOException("WfCommons JSON is missing workflow: " + file.getAbsolutePath());
        }
        if (instance.workflow.specification == null) {
            throw new IOException("WfCommons JSON is missing workflow.specification: " + file.getAbsolutePath());
        }
        if (instance.workflow.specification.tasks == null) {
            throw new IOException("WfCommons JSON is missing workflow.specification.tasks: " + file.getAbsolutePath());
        }
        if (instance.workflow.specification.tasks.isEmpty()) {
            throw new IOException("WfCommons JSON workflow contains no specification tasks: " + file.getAbsolutePath());
        }
        if (instance.workflow.specification.files == null) {
            throw new IOException("WfCommons JSON is missing workflow.specification.files: " + file.getAbsolutePath());
        }
        if (instance.workflow.execution == null || instance.workflow.execution.tasks == null) {
            throw new IOException("WfCommons JSON is missing workflow.execution.tasks: " + file.getAbsolutePath());
        }
    }

    private static Map<String, Double> indexFileSizes(List<SpecFile> files) {
        Map<String, Double> fileSizes = new HashMap<>();
        for (SpecFile file : files) {
            if (file.id == null || file.id.length() == 0) {
                continue;
            }
            if (file.sizeInBytes == null
                    || Double.isNaN(file.sizeInBytes)
                    || Double.isInfinite(file.sizeInBytes)
                    || file.sizeInBytes < 0.0) {
                throw new WorkflowValidationException("Invalid WfCommons file size for " + file.id);
            }

            Double previousSize = fileSizes.put(file.id, file.sizeInBytes);
            if (previousSize != null && Double.compare(previousSize.doubleValue(), file.sizeInBytes) != 0) {
                throw new WorkflowValidationException("Duplicate WfCommons file id " + file.id
                        + " with conflicting sizes: " + previousSize + " vs " + file.sizeInBytes);
            }
        }
        return fileSizes;
    }

    private static Map<String, Double> indexRuntimes(List<ExecutionTask> tasks) {
        Map<String, Double> runtimes = new HashMap<>();
        for (ExecutionTask task : tasks) {
            if (task.id == null || task.id.length() == 0) {
                continue;
            }
            if (task.runtimeInSeconds == null) {
                throw new WorkflowValidationException("Missing WfCommons execution runtime for task " + task.id);
            }

            Double previousRuntime = runtimes.put(task.id, task.runtimeInSeconds);
            if (previousRuntime != null && Double.compare(previousRuntime.doubleValue(), task.runtimeInSeconds) != 0) {
                throw new WorkflowValidationException("Duplicate WfCommons execution task id "
                        + task.id + " with conflicting runtimes: " + previousRuntime
                        + " vs " + task.runtimeInSeconds);
            }
        }
        return runtimes;
    }

    private static Map<String, SpecTask> indexSpecTasks(List<SpecTask> tasks) {
        Map<String, SpecTask> specTasks = new LinkedHashMap<>();
        for (SpecTask task : tasks) {
            if (task.id == null || task.id.length() == 0) {
                throw new IllegalArgumentException("WfCommons task id cannot be empty");
            }
            if (specTasks.containsKey(task.id)) {
                throw new IllegalArgumentException("Duplicate WfCommons task id: " + task.id);
            }
            specTasks.put(task.id, task);
        }
        return specTasks;
    }

    private static double getRequiredRuntimeSeconds(Map<String, Double> runtimes, String taskId) {
        Double runtime = runtimes.get(taskId);
        if (runtime == null) {
            throw new IllegalArgumentException("Missing WfCommons execution runtime for task " + taskId);
        }
        if (Double.isNaN(runtime.doubleValue())
                || Double.isInfinite(runtime.doubleValue())
                || runtime.doubleValue() < 0.0) {
            throw new IllegalArgumentException("Negative WfCommons runtime for task " + taskId);
        }
        return runtime.doubleValue();
    }

    private static long toCloudletLength(double runtimeSeconds, String taskId, WorkflowInputReport report) {
        double length = Parameters.getRuntimeReferenceMips() * runtimeSeconds * Parameters.getRuntimeScale();

        if (Double.isNaN(length) || Double.isInfinite(length) || length > Long.MAX_VALUE) {
            throw new WorkflowValidationException("Invalid converted runtime for WfCommons task " + taskId + ": "
                    + runtimeSeconds + " s overflows cloudlet length");
        }
        if (length < MIN_CLOUDLET_LENGTH) {
            report.recordNormalization(
                    WorkflowInputReport.NormalizationKind.TASK_RUNTIME_FLOORED_TO_MINIMUM,
                    taskId,
                    length,
                    MIN_CLOUDLET_LENGTH);
            length = MIN_CLOUDLET_LENGTH;
        }
        return Math.max(MIN_CLOUDLET_LENGTH, (long) length);
    }

    private static void addFiles(
            List<FileItem> taskFiles,
            Map<String, FileItem> fileItemsByNameAndType,
            List<String> fileNames,
            Map<String, Double> fileSizes,
            FileType fileType,
            String fileNamespace,
            Map<String, FileItem> firstDeclaredInputs) {
        if (fileNames == null) {
            return;
        }
        for (String fileName : fileNames) {
            if (fileName == null || fileName.length() == 0) {
                continue;
            }
            String scopedFileName = scopedFileName(fileNamespace, fileName);
            FileItem item = getOrCreateFileItem(fileItemsByNameAndType, fileName, scopedFileName, fileSizes, fileType);
            taskFiles.add(item);

            if (fileType == FileType.INPUT && !firstDeclaredInputs.containsKey(scopedFileName)) {
                firstDeclaredInputs.put(scopedFileName, item);
            }
        }
    }

    private static void commitFirstDeclaredInputs(Map<String, FileItem> firstDeclaredInputs) {
        for (Map.Entry<String, FileItem> entry : firstDeclaredInputs.entrySet()) {
            if (!ReplicaCatalog.containsFile(entry.getKey())) {
                ReplicaCatalog.setFile(entry.getKey(), entry.getValue());
            }
        }
    }

    private static FileItem getOrCreateFileItem(
            Map<String, FileItem> fileItemsByNameAndType,
            String originalFileName,
            String scopedFileName,
            Map<String, Double> fileSizes,
            FileType fileType) {
        String key = scopedFileName + "#" + fileType.name();
        FileItem item = fileItemsByNameAndType.get(key);
        if (item != null) {
            return item;
        }

        Double size = fileSizes.get(originalFileName);
        if (size == null) {
            throw new WorkflowValidationException("Task references an undeclared WfCommons file " + originalFileName);
        }
        double sizeBytes = size.doubleValue();
        item = new FileItem(scopedFileName, sizeBytes);
        item.setType(fileType);
        fileItemsByNameAndType.put(key, item);
        return item;
    }

    private static String scopedFileName(String fileNamespace, String originalFileName) {
        if (fileNamespace == null) {
            return originalFileName;
        }
        return fileNamespace + "/" + originalFileName;
    }

    private static void connectDependencies(
            List<SpecTask> specTaskList, Map<String, SpecTask> specTasks, Map<String, Task> tasksByWfCommonsId) {
        Set<Long> linkedEdges = new HashSet<>();
        for (SpecTask specTask : specTaskList) {
            Task task = tasksByWfCommonsId.get(specTask.id);
            addParentEdges(task, specTask.parents, specTasks, tasksByWfCommonsId, specTask.id, linkedEdges);
            addChildEdges(task, specTask.children, specTasks, tasksByWfCommonsId, specTask.id, linkedEdges);
        }
    }

    private static void validateDeclaredDependencySymmetry(
            List<SpecTask> specTaskList, Map<String, SpecTask> specTasks) {
        Map<String, Set<String>> parentMembership = new HashMap<>();
        Map<String, Set<String>> childMembership = new HashMap<>();
        for (SpecTask task : specTaskList) {
            validateDeclaredParents(task, specTasks, childMembership);
            validateDeclaredChildren(task, specTasks, parentMembership);
        }
    }

    private static void validateDeclaredParents(
            SpecTask child, Map<String, SpecTask> specTasks, Map<String, Set<String>> childMembership) {
        if (child.parents == null) {
            return;
        }
        for (String parentId : child.parents) {
            SpecTask parent = specTasks.get(parentId);
            if (parent == null) {
                throw new IllegalArgumentException("Task " + child.id + " references missing parent " + parentId);
            }
            if (!declares(parent.id, parent.children, child.id, childMembership)) {
                throw new WorkflowValidationException("Inconsistent WfCommons dependency: task "
                        + child.id + " lists " + parentId + " as a parent, but task "
                        + parentId + " does not list " + child.id + " as a child");
            }
        }
    }

    private static void validateDeclaredChildren(
            SpecTask parent, Map<String, SpecTask> specTasks, Map<String, Set<String>> parentMembership) {
        if (parent.children == null) {
            return;
        }
        for (String childId : parent.children) {
            SpecTask child = specTasks.get(childId);
            if (child == null) {
                throw new IllegalArgumentException("Task " + parent.id + " references missing child " + childId);
            }
            if (!declares(child.id, child.parents, parent.id, parentMembership)) {
                throw new WorkflowValidationException("Inconsistent WfCommons dependency: task "
                        + parent.id + " lists " + childId + " as a child, but task "
                        + childId + " does not list " + parent.id + " as a parent");
            }
        }
    }

    private static boolean declares(
            String taskId, List<String> references, String expected, Map<String, Set<String>> membership) {
        if (references == null) {
            return false;
        }
        Set<String> indexed = membership.get(taskId);
        if (indexed == null) {
            indexed = new HashSet<>(references);
            membership.put(taskId, indexed);
        }
        return indexed.contains(expected);
    }

    private static void addParentEdges(
            Task childTask,
            List<String> parentIds,
            Map<String, SpecTask> specTasks,
            Map<String, Task> tasksByWfCommonsId,
            String childId,
            Set<Long> linkedEdges) {
        if (parentIds == null) {
            return;
        }
        for (String parentId : parentIds) {
            if (!specTasks.containsKey(parentId)) {
                throw new IllegalArgumentException("Task " + childId + " references missing parent " + parentId);
            }
            addEdge(tasksByWfCommonsId.get(parentId), childTask, linkedEdges);
        }
    }

    private static void addChildEdges(
            Task parentTask,
            List<String> childIds,
            Map<String, SpecTask> specTasks,
            Map<String, Task> tasksByWfCommonsId,
            String parentId,
            Set<Long> linkedEdges) {
        if (childIds == null) {
            return;
        }
        for (String childId : childIds) {
            if (!specTasks.containsKey(childId)) {
                throw new IllegalArgumentException("Task " + parentId + " references missing child " + childId);
            }
            addEdge(parentTask, tasksByWfCommonsId.get(childId), linkedEdges);
        }
    }

    private static void addEdge(Task parentTask, Task childTask, Set<Long> linkedEdges) {
        long edge = ((long) parentTask.getCloudletId() << 32) | (childTask.getCloudletId() & 0xffffffffL);
        if (linkedEdges.add(edge)) {
            parentTask.addChild(childTask);
            childTask.addParent(parentTask);
        }
    }

    public static final class ParseResult {

        private final List<Task> tasks;
        private final int nextTaskId;
        private final WorkflowInputReport inputReport;

        private ParseResult(List<Task> tasks, int nextTaskId, WorkflowInputReport inputReport) {
            this.tasks = tasks;
            this.nextTaskId = nextTaskId;
            this.inputReport = inputReport;
        }

        public List<Task> getTasks() {
            return tasks;
        }

        public int getNextTaskId() {
            return nextTaskId;
        }

        public WorkflowInputReport getInputReport() {
            return inputReport;
        }
    }

    private static final class WfCommonsInstance {

        private Workflow workflow;
        private String schemaVersion;
    }

    private static final class Workflow {

        private Specification specification;
        private Execution execution;
    }

    private static final class Specification {

        private List<SpecTask> tasks;
        private List<SpecFile> files;
    }

    private static final class SpecTask {

        private String id;
        private String name;
        private List<String> parents;
        private List<String> children;
        private List<String> inputFiles;
        private List<String> outputFiles;
    }

    private static final class SpecFile {

        private String id;
        private Double sizeInBytes;
    }

    private static final class Execution {

        private List<ExecutionTask> tasks;
    }

    private static final class ExecutionTask {

        private String id;
        private Double runtimeInSeconds;
    }
}
