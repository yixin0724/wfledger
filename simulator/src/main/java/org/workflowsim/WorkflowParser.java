/**
 * Copyright 2012-2013 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.workflowsim;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cloudbus.cloudsim.Log;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * Single entry point that converts workflow input into a WorkflowSim {@link Task} DAG.
 *
 * <p>DAX XML is parsed by default; an input whose extension is {@code .json} is delegated to
 * {@link WfCommonsJsonParser} to parse a WfCommons WfFormat instance. Both paths convert the
 * runtime in seconds carried by the input into million instructions (MI) using the reference MIPS
 * and the runtime scale factor, and normalize a too-small length with the normalization recorded
 * explicitly.</p>
 *
 * <p>Version contract (R8 audit, architecture channel P1-4): the DAX {@code version} attribute and
 * the WfCommons {@code schemaVersion} are only recorded into {@link WorkflowInputReport} as
 * evidence and are not validated. Parsing semantics follow the Pegasus DAX 3.x structure and the
 * WfCommons 1.x implementation; the DAX {@code size} attribute keeps the upstream WorkflowSim
 * historical convention of interpreting it directly as bytes (the Pegasus 3.x schema nominally
 * uses KB, a factor of 1024 apart -- the platform bandwidth calibration and golden values are both
 * based on the "as bytes" semantics); unit or structure differences in DAX 2.x or future major
 * versions are unverified, so reconcile the unit semantics manually before using input from a
 * non-target version.</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Aug 23, 2013
 * @date Nov 9, 2014
 */
public final class WorkflowParser {

    private static final long MIN_CLOUDLET_LENGTH = 100L;

    /** Single DAX or JSON input path. */
    private final String daxPath;
    /** Multiple DAX or JSON input paths. */
    private final List<String> daxPaths;
    /** All tasks produced by the most recent parse. */
    private List<Task> taskList;

    /** Conversion report for each input consumed by the most recent parse. */
    private final List<WorkflowInputReport> inputReports;

    /**
     * R5 dynamic arrival: mapping from each task number to its source input index in the most
     * recent parse.
     *
     * <p>Task numbers are assigned consecutively across inputs, and input indices correspond
     * one-to-one to the positions in {@link #inputReports}.</p>
     */
    private final Map<Integer, Integer> taskWorkflowIndices;
    /** CloudSim user ID written when a task is created. */
    private final int userId;

    /** Next assignable task number; supports submitting several workflows in sequence. */
    private int jobIdStartsFrom;

    /** @return task list produced by the most recent parse */
    @SuppressWarnings("unchecked")
    public List<Task> getTaskList() {
        return taskList;
    }

    /**
     * Replace the task list of the current parse result.
     *
     * @param taskList new task list
     */
    protected void setTaskList(List<Task> taskList) {
        this.taskList = taskList;
    }
    /** Temporary index from DAX job name to task object. */
    protected Map<String, Task> mName2Task;
    /** Directed task-ID pairs seen in the current DAX input; lists retain first-declaration order. */
    private final Set<Long> daxDependencyEdges = new HashSet<>();

    /**
     * Create a workflow parser.
     *
     * @param userId CloudSim user ID to which every parsed task belongs
     */
    public WorkflowParser(int userId) {
        this.userId = userId;
        this.mName2Task = new HashMap<>();
        this.daxPath = Parameters.getDaxPath();
        this.daxPaths = Parameters.getDAXPaths();
        this.jobIdStartsFrom = 1;

        setTaskList(new ArrayList<>());
        this.inputReports = new ArrayList<>();
        this.taskWorkflowIndices = new HashMap<>();
    }

    /**
     * Parse the configured workflow input or inputs.
     *
     * <p>DAX XML is still the default format; a file ending in {@code .json} is parsed as a
     * WfCommons workflow instance. Every call first clears the tasks, the name index, and the input
     * reports of the previous run, so the result does not accumulate across parses.</p>
     *
     * @throws WorkflowValidationException when no input is configured or any input is invalid
     */
    public void parse() {
        setTaskList(new ArrayList<Task>());
        mName2Task.clear();
        daxDependencyEdges.clear();
        inputReports.clear();
        taskWorkflowIndices.clear();
        jobIdStartsFrom = 1;
        if (this.daxPath != null) {
            parseInputWithWorkflowIndex(this.daxPath, null, 0);
        } else if (this.daxPaths != null) {
            if (this.daxPaths.isEmpty()) {
                throw new WorkflowValidationException("No workflow input path was configured");
            }
            boolean isolateInputFiles = this.daxPaths.size() > 1;
            for (int inputIndex = 0; inputIndex < this.daxPaths.size(); inputIndex++) {
                String fileNamespace = isolateInputFiles ? "workflow-" + inputIndex : null;
                parseInputWithWorkflowIndex(this.daxPaths.get(inputIndex), fileNamespace, inputIndex);
            }
        } else {
            throw new WorkflowValidationException("No workflow input path was configured");
        }
    }

    /**
     * Parse a single input and register the tasks it produces under the given workflow index
     * (used by R5 dynamic arrival).
     *
     * @param path input file path
     * @param fileNamespace file scope of a multi-input submission; {@code null} for a single input
     * @param workflowIndex index of this input within the current submission
     */
    private void parseInputWithWorkflowIndex(String path, String fileNamespace, int workflowIndex) {
        int firstTaskId = this.jobIdStartsFrom;
        parseWorkflowFile(path, fileNamespace);
        for (int taskId = firstTaskId; taskId < this.jobIdStartsFrom; taskId++) {
            taskWorkflowIndices.put(taskId, workflowIndex);
        }
    }

    /**
     * Return the mapping from task number to source input index of the most recent parse (used by
     * R5 dynamic arrival).
     *
     * @return unmodifiable mapping; with a single input every task index is 0
     */
    public Map<Integer, Integer> getTaskWorkflowIndices() {
        return Collections.unmodifiableMap(taskWorkflowIndices);
    }

    /**
     * Return the immutable reports of the inputs consumed by the most recent parse.
     *
     * @return one report per DAX or WfCommons input
     */
    public List<WorkflowInputReport> getInputReports() {
        return Collections.unmodifiableList(inputReports);
    }

    /**
     * Parse a single workflow input by file extension.
     *
     * @param path workflow file path; DAX uses {@code .dax}/{@code .xml}, WfCommons uses
     * {@code .json}
     */
    private void parseWorkflowFile(String path, String fileNamespace) {
        if (path == null || path.trim().isEmpty()) {
            throw new WorkflowValidationException("Workflow input path cannot be empty");
        }
        String normalizedPath = path.trim();
        if (normalizedPath.toLowerCase().endsWith(".json")) {
            parseWfCommonsJsonFile(normalizedPath, fileNamespace);
            return;
        }
        if (normalizedPath.toLowerCase().endsWith(".dax")
                || normalizedPath.toLowerCase().endsWith(".xml")) {
            parseXmlFile(normalizedPath, fileNamespace);
            return;
        }
        throw new WorkflowValidationException("Unsupported workflow input format: " + path);
    }

    /**
     * Parse a WfCommons WfFormat JSON workflow instance.
     *
     * @param path JSON file path
     */
    private void parseWfCommonsJsonFile(String path, String fileNamespace) {
        try {
            WfCommonsJsonParser.ParseResult result =
                    new WfCommonsJsonParser().parse(path, this.userId, this.jobIdStartsFrom, fileNamespace);
            this.getTaskList().addAll(result.getTasks());
            this.jobIdStartsFrom = result.getNextTaskId();
            this.inputReports.add(result.getInputReport());
        } catch (Exception e) {
            if (e instanceof WorkflowValidationException) {
                throw (WorkflowValidationException) e;
            }
            throw new WorkflowValidationException("Unable to parse WfCommons JSON workflow: " + path, e);
        }
    }

    /**
     * Parse a DAX XML file with JDOM.
     *
     * <p>All task definitions are built first, then parent-child relations are parsed, and the DAG
     * is validated at the end. A missing task is never created implicitly during dependency
     * parsing, so invalid input cannot be accepted silently.</p>
     *
     * @param path DAX XML path
     */
    private void parseXmlFile(String path, String fileNamespace) {
        try {
            SAXBuilder builder = new SAXBuilder();
            // Inputs are self-contained evidence. External DTD/entity contents are neither
            // part of the input fingerprint nor allowed to access local/network resources.
            builder.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            builder.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            builder.setFeature("http://xml.org/sax/features/external-general-entities", false);
            builder.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            builder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            builder.setExpandEntities(false);
            File source = new File(path);
            if (!source.isFile()) {
                throw new WorkflowValidationException("DAX input does not exist: " + source.getAbsolutePath());
            }
            Document dom = builder.build(source);
            Element root = dom.getRootElement();
            List<Element> list = root.getChildren();

            WorkflowInputReport report = new WorkflowInputReport(
                    path, WorkflowInputReport.Format.DAX_XML, root.getAttributeValue("version"));
            List<Task> parsedTasks = new ArrayList<>();
            Map<String, FileItem> firstDeclaredInputs = new LinkedHashMap<>();
            mName2Task.clear();
            daxDependencyEdges.clear();

            // Every task definition must be built first, so that later dependency references can
            // be strictly resolved and validated.
            for (Element node : list) {
                if ("job".equalsIgnoreCase(node.getName())) {
                    parsedTasks.add(parseDaxJob(node, report, fileNamespace, firstDeclaredInputs));
                }
            }

            for (Element node : list) {
                if ("child".equalsIgnoreCase(node.getName())) {
                    parseDaxDependencies(node);
                }
            }
            WorkflowDagValidator.validateAndAssignDepths(parsedTasks);
            // F-009 fix (2026-10-19): commit the first-round INPUT declarations into the global
            // replica catalog only after the structure and DAG validation have fully passed; a
            // failed parse no longer leaves half-committed entries (such pollution would replace
            // the new declarations of a retried parse in the same JVM through the "reuse the
            // first-declared size" rule). Commit keeps the historical cross-parse semantics: an
            // existing entry is never overwritten.
            for (Map.Entry<String, FileItem> entry : firstDeclaredInputs.entrySet()) {
                if (!ReplicaCatalog.containsFile(entry.getKey())) {
                    ReplicaCatalog.setFile(entry.getKey(), entry.getValue());
                }
            }
            report.setTaskCount(parsedTasks.size());
            this.getTaskList().addAll(parsedTasks);
            this.inputReports.add(report);
            this.mName2Task.clear();
        } catch (JDOMException jde) {
            throw new WorkflowValidationException("Invalid DAX XML: " + path, jde);
        } catch (IOException ioe) {
            throw new WorkflowValidationException("Unable to read DAX input: " + path, ioe);
        }
    }

    private Task parseDaxJob(
            Element node, WorkflowInputReport report, String fileNamespace, Map<String, FileItem> firstDeclaredInputs) {
        String nodeName = requiredAttribute(node, "id", "DAX job");
        if (mName2Task.containsKey(nodeName)) {
            throw new WorkflowValidationException("Duplicate DAX job ID " + nodeName);
        }
        String runtimeAttribute = requiredAttribute(node, "runtime", "DAX job " + nodeName);
        double runtimeSeconds = parseNonNegativeFinite(runtimeAttribute, "runtime for DAX job " + nodeName);
        double convertedLength = runtimeSeconds * Parameters.getRuntimeReferenceMips() * Parameters.getRuntimeScale();
        long length = toCloudletLength(convertedLength, nodeName, report);

        List<FileItem> files = new ArrayList<>();
        for (Element file : node.getChildren()) {
            if ("uses".equalsIgnoreCase(file.getName())) {
                files.add(parseDaxFile(file, nodeName, fileNamespace, firstDeclaredInputs));
            }
        }

        Task task = new Task(jobIdStartsFrom++, length);
        task.setType(node.getAttributeValue("name") == null ? "" : node.getAttributeValue("name"));
        task.setUserId(userId);
        for (FileItem file : files) {
            task.addRequiredFile(file.getName());
        }
        task.setFileList(files);
        mName2Task.put(nodeName, task);
        return task;
    }

    private FileItem parseDaxFile(
            Element file, String taskId, String fileNamespace, Map<String, FileItem> firstDeclaredInputs) {
        String originalFileName = file.getAttributeValue("name");
        if (originalFileName == null || originalFileName.trim().isEmpty()) {
            originalFileName = file.getAttributeValue("file");
        }
        if (originalFileName == null || originalFileName.trim().isEmpty()) {
            throw new WorkflowValidationException("DAX job " + taskId + " contains a file without a name");
        }
        originalFileName = originalFileName.trim();
        String fileName = scopedFileName(fileNamespace, originalFileName);
        String link = requiredAttribute(file, "link", "DAX file " + originalFileName);
        FileType type;
        if ("input".equalsIgnoreCase(link)) {
            type = FileType.INPUT;
        } else if ("output".equalsIgnoreCase(link)) {
            type = FileType.OUTPUT;
        } else {
            throw new WorkflowValidationException(
                    "DAX file " + originalFileName + " has unsupported link type " + link);
        }
        double size = parseNonNegativeFinite(
                requiredAttribute(file, "size", "DAX file " + originalFileName),
                "size for DAX file " + originalFileName);
        FileItem item;
        // F-009: an INPUT declaration only interacts with the "local first-round table plus the
        // existing global catalog" and no longer writes into the global catalog directly; the first
        // of three sources wins: existing catalog entry (historical cross-parse semantics), then an
        // earlier declaration in this round, then a new entry.
        FileItem registered = ReplicaCatalog.containsFile(fileName)
                ? ReplicaCatalog.getFile(fileName)
                : firstDeclaredInputs.get(fileName);
        if (type == FileType.INPUT && registered != null) {
            item = registered;
            if (Double.compare(item.getSize(), size) != 0) {
                // Relaxed validation: log a warning but keep using the first-declared size. In a
                // real scientific workflow, the size estimated for the same file can differ across
                // tasks; this is normal Pegasus DAX output and must not block simulation execution.
                Log.printLine("WARNING: DAX file '" + originalFileName
                        + "' has inconsistent input size declarations: "
                        + "first=" + item.getSize() + ", current=" + size
                        + " (using first declaration)");
            }
        } else {
            item = new FileItem(fileName, size);
            if (type == FileType.INPUT) {
                // Enter the local first-round table during parsing; commit after the DAG
                // check passes (see the end of parseXmlFile).
                firstDeclaredInputs.putIfAbsent(fileName, item);
            } else if (registered != null && Double.compare(registered.getSize(), size) != 0) {
                // R8 audit fix (architecture channel P1-4): a size mismatch between an OUTPUT and
                // a registered INPUT of the same name was previously completely silent -- when the
                // two declarations of an intermediate file (upstream output = downstream input)
                // diverge, the transferred byte count depends on the parse order. Log a warning to
                // match the INPUT branch.
                Log.printLine("WARNING: DAX file '" + originalFileName
                        + "' output size " + size + " differs from registered input size "
                        + registered.getSize()
                        + " (transfers use the registered input declaration)");
            }
        }
        item.setType(type);
        return item;
    }

    /**
     * Build the internal logical identity of a file within a multi-input submission.
     *
     * <p>Files of the same name in different workflows are not shared by default; only a file
     * referenced repeatedly within the same input denotes the same logical data object. A
     * single-input scenario keeps the original file name to preserve the historical API and the
     * compatibility of existing experiments.</p>
     *
     * @param fileNamespace stable scope of the current input; {@code null} for a single input
     * @param originalFileName original file name declared in the input format
     * @return internal file identity used by tasks and the replica catalog
     */
    private static String scopedFileName(String fileNamespace, String originalFileName) {
        if (fileNamespace == null) {
            return originalFileName;
        }
        return fileNamespace + "/" + originalFileName;
    }

    private void parseDaxDependencies(Element childNode) {
        String childName = requiredAttribute(childNode, "ref", "DAX child");
        Task childTask = mName2Task.get(childName);
        if (childTask == null) {
            throw new WorkflowValidationException("DAX dependency references missing child " + childName);
        }
        for (Element parentNode : childNode.getChildren()) {
            if (!"parent".equalsIgnoreCase(parentNode.getName())) {
                continue;
            }
            String parentName = requiredAttribute(parentNode, "ref", "DAX parent");
            Task parentTask = mName2Task.get(parentName);
            if (parentTask == null) {
                throw new WorkflowValidationException("DAX dependency references missing parent " + parentName);
            }
            long edge = ((long) parentTask.getCloudletId() << 32) | (childTask.getCloudletId() & 0xffffffffL);
            if (daxDependencyEdges.add(edge)) {
                parentTask.addChild(childTask);
                childTask.addParent(parentTask);
            }
        }
    }

    private static String requiredAttribute(Element element, String attribute, String subject) {
        String value = element.getAttributeValue(attribute);
        if (value == null || value.trim().isEmpty()) {
            throw new WorkflowValidationException(subject + " is missing required attribute " + attribute);
        }
        return value.trim();
    }

    private static double parseNonNegativeFinite(String value, String subject) {
        try {
            double number = Double.parseDouble(value);
            if (Double.isNaN(number) || Double.isInfinite(number) || number < 0.0) {
                throw new WorkflowValidationException("Invalid " + subject + ": " + value);
            }
            return number;
        } catch (NumberFormatException exception) {
            throw new WorkflowValidationException("Invalid " + subject + ": " + value, exception);
        }
    }

    private static long toCloudletLength(double convertedLength, String taskId, WorkflowInputReport report) {
        if (Double.isNaN(convertedLength) || Double.isInfinite(convertedLength) || convertedLength > Long.MAX_VALUE) {
            throw new WorkflowValidationException("Invalid converted runtime for task " + taskId);
        }
        if (convertedLength < MIN_CLOUDLET_LENGTH) {
            report.recordNormalization(
                    WorkflowInputReport.NormalizationKind.TASK_RUNTIME_FLOORED_TO_MINIMUM,
                    taskId,
                    convertedLength,
                    MIN_CLOUDLET_LENGTH);
            return MIN_CLOUDLET_LENGTH;
        }
        return (long) convertedLength;
    }
}
