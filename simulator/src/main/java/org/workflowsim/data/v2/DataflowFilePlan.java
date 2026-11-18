package org.workflowsim.data.v2;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters.FileType;

public final class DataflowFilePlan {

    public static final String CONTRACT_VERSION = "SCOPED_RESOLVED_WRITE_ONCE_FILES_V2";

    public static final class FileId implements Comparable<FileId> {
        private final int workflowInputIndex;
        private final String name;

        private FileId(int workflowInputIndex, String name) {
            this.workflowInputIndex = workflowInputIndex;
            this.name = name;
        }

        public static FileId of(int workflowInputIndex, String name) {
            if (workflowInputIndex < 0 || name == null || name.isEmpty()) {
                throw bad("File identity needs a nonnegative workflow input and nonempty name");
            }
            return new FileId(workflowInputIndex, name);
        }

        public int getWorkflowInputIndex() {
            return workflowInputIndex;
        }

        public String getName() {
            return name;
        }

        @Override
        public int compareTo(FileId other) {
            int order = Integer.compare(workflowInputIndex, other.workflowInputIndex);
            return order != 0 ? order : name.compareTo(other.name);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FileId)) {
                return false;
            }
            FileId id = (FileId) other;
            return workflowInputIndex == id.workflowInputIndex && name.equals(id.name);
        }

        @Override
        public int hashCode() {
            return 31 * workflowInputIndex + name.hashCode();
        }

        @Override
        public String toString() {
            return "input[" + workflowInputIndex + "]/" + name;
        }
    }

    public static final class FileDefinition {
        private final FileId id;
        private final double bytes;
        private final Integer producerTaskId;
        private final List<Integer> consumers;

        private FileDefinition(FileBuilder builder) {
            id = builder.id;
            bytes = builder.bytes;
            producerTaskId = builder.producer;
            consumers = immutableList(builder.consumers);
        }

        public FileId getId() {
            return id;
        }

        public double getBytes() {
            return bytes;
        }

        public Integer getProducerTaskId() {
            return producerTaskId;
        }

        public List<Integer> getConsumerTaskIds() {
            return consumers;
        }

        public boolean isExternal() {
            return producerTaskId == null;
        }

        public boolean isWorkflowOutput() {
            return producerTaskId != null && consumers.isEmpty();
        }
    }

    public static final class InputDemand {
        private final FileDefinition file;
        private final long referenceCount;
        private final List<Integer> consumers;

        private InputDemand(DemandBuilder builder) {
            file = builder.file;
            referenceCount = builder.references;
            consumers = immutableList(builder.consumers);
        }

        public FileDefinition getFile() {
            return file;
        }

        public long getReferenceCount() {
            return referenceCount;
        }

        public BigDecimal getReferenceBytes() {
            return new BigDecimal(file.bytes).multiply(BigDecimal.valueOf(referenceCount));
        }

        public List<Integer> getConsumerTaskIds() {
            return consumers;
        }
    }

    private final List<Integer> taskIds;
    private final Map<Integer, Integer> workflowIndices;
    private final Map<Integer, Map<FileId, Long>> inputs;
    private final Map<Integer, List<FileId>> outputs;
    private final Map<Integer, List<Integer>> parents;
    private final Map<FileId, FileDefinition> byId;
    private final List<FileDefinition> files;

    private DataflowFilePlan(Map<Integer, Node> nodes, Map<FileId, FileBuilder> declarations) {
        taskIds = immutableList(nodes.keySet());
        Map<Integer, Integer> scopes = new TreeMap<>();
        Map<Integer, Map<FileId, Long>> inputRows = new TreeMap<>();
        Map<Integer, List<FileId>> outputRows = new TreeMap<>();
        Map<Integer, List<Integer>> parentRows = new TreeMap<>();
        for (Node node : nodes.values()) {
            scopes.put(node.id, node.workflow);
            inputRows.put(node.id, Collections.unmodifiableMap(new TreeMap<>(node.inputs)));
            outputRows.put(node.id, immutableList(node.outputs));
            parentRows.put(node.id, immutableList(node.parents));
        }
        workflowIndices = Collections.unmodifiableMap(scopes);
        inputs = Collections.unmodifiableMap(inputRows);
        outputs = Collections.unmodifiableMap(outputRows);
        parents = Collections.unmodifiableMap(parentRows);
        Map<FileId, FileDefinition> resolved = new TreeMap<>();
        for (FileBuilder file : declarations.values()) {
            resolved.put(file.id, new FileDefinition(file));
        }
        byId = Collections.unmodifiableMap(resolved);
        files = immutableList(resolved.values());
    }

    public static DataflowFilePlan capture(List<Task> tasks, Map<Integer, Integer> taskWorkflowIndices) {
        if (tasks == null || taskWorkflowIndices == null) {
            throw bad("Tasks and workflow indices are required");
        }
        Map<Integer, Node> nodes = new TreeMap<>();
        Map<FileId, FileBuilder> files = new TreeMap<>();
        for (Task task : tasks) {
            if (task == null || task.getCloudletId() < 0) {
                throw bad("Logical Task IDs must be nonnegative and Tasks nonnull");
            }
            int id = task.getCloudletId();
            Integer workflow = taskWorkflowIndices.get(id);
            if (workflow == null || workflow < 0) {
                throw bad("Missing or invalid workflow input index for Task " + id);
            }
            if (nodes.containsKey(id)) {
                throw bad("Duplicate logical Task ID " + id);
            }
            Node node =
                    new Node(id, workflow, neighbours(task.getParentList(), id), neighbours(task.getChildList(), id));
            nodes.put(id, node);
            if (task.getFileList() == null) {
                throw bad("Null resolved file list for Task " + id);
            }
            resolveTaskFiles(node, files, id, workflow, task);
            for (FileId key : node.outputs) {
                if (node.inputs.containsKey(key)) {
                    throw bad("In-place file mutation is not write-once: " + key + " at Task " + id);
                }
            }
        }
        if (!taskWorkflowIndices.keySet().equals(nodes.keySet())) {
            throw bad("Workflow index mapping must cover exactly the captured Task IDs");
        }
        validateGraph(nodes);
        validateProducers(nodes, files);
        return new DataflowFilePlan(nodes, files);
    }

    private static void resolveTaskFiles(Node node, Map<FileId, FileBuilder> files, int id, int workflow, Task task) {
        for (FileItem item : task.getFileList()) {
            if (item == null || (item.getType() != FileType.INPUT && item.getType() != FileType.OUTPUT)) {
                throw bad("V2 resolved files require INPUT or OUTPUT type");
            }
            double bytes = item.getSize();
            if (!Double.isFinite(bytes) || bytes < 0) {
                throw bad("Resolved file bytes must be finite and nonnegative");
            }
            bytes = bytes == 0 ? 0 : bytes;
            FileId key = FileId.of(workflow, item.getName());
            FileBuilder file = files.get(key);
            if (file == null) {
                file = new FileBuilder(key, bytes);
                files.put(key, file);
            } else if (file.bytes != bytes) {
                throw bad("Conflicting resolved sizes for " + key);
            }
            if (item.getType() == FileType.INPUT) {
                Long count = node.inputs.get(key);
                node.inputs.put(key, count == null ? 1 : Math.addExact(count, 1));
                file.consumers.add(id);
            } else {
                if (file.producer != null && file.producer != id) {
                    throw bad("Multiple logical producers for " + key);
                }
                file.producer = id;
                node.outputs.add(key);
            }
        }
    }

    public String getContractVersion() {
        return CONTRACT_VERSION;
    }

    public List<Integer> getTaskIds() {
        return taskIds;
    }

    public Map<Integer, Integer> getTaskWorkflowIndices() {
        return workflowIndices;
    }

    public List<FileDefinition> getFiles() {
        return files;
    }

    public List<Integer> getParentTaskIds(int taskId) {
        List<Integer> row = parents.get(taskId);
        if (row == null) {
            throw bad("Unknown logical Task ID " + taskId);
        }
        return row;
    }

    public FileDefinition getFile(FileId id) {
        FileDefinition file = id == null ? null : byId.get(id);
        if (file == null) {
            throw bad("Unknown file identity: " + id);
        }
        return file;
    }

    public Map<FileId, Long> getInputReferences(int taskId) {
        Map<FileId, Long> row = inputs.get(taskId);
        if (row == null) {
            throw bad("Unknown logical Task ID " + taskId);
        }
        return row;
    }

    public List<FileId> getOutputFiles(int taskId) {
        List<FileId> row = outputs.get(taskId);
        if (row == null) {
            throw bad("Unknown logical Task ID " + taskId);
        }
        return row;
    }

    public List<InputDemand> demandsFor(Collection<Integer> logicalTaskIds) {
        if (logicalTaskIds == null) {
            throw bad("Logical Task selection is required");
        }
        Set<Integer> selected = new TreeSet<>();
        for (Integer id : logicalTaskIds) {
            if (id == null || !inputs.containsKey(id) || !selected.add(id)) {
                throw bad("Task selection requires distinct known logical IDs");
            }
        }
        Map<FileId, DemandBuilder> demands = new TreeMap<>();
        for (int id : selected) {
            for (Map.Entry<FileId, Long> reference : inputs.get(id).entrySet()) {
                FileDefinition file = byId.get(reference.getKey());
                if (file.producerTaskId != null && selected.contains(file.producerTaskId)) {
                    continue;
                }
                DemandBuilder demand = demands.get(file.id);
                if (demand == null) {
                    demand = new DemandBuilder(file);
                    demands.put(file.id, demand);
                }
                demand.references = Math.addExact(demand.references, reference.getValue());
                demand.consumers.add(id);
            }
        }
        List<InputDemand> result = new ArrayList<>();
        for (DemandBuilder demand : demands.values()) {
            result.add(new InputDemand(demand));
        }
        return Collections.unmodifiableList(result);
    }

    private static Set<Integer> neighbours(List<Task> neighbours, int self) {
        if (neighbours == null) {
            throw bad("Null Task adjacency list");
        }
        Set<Integer> ids = new TreeSet<>();
        for (Task neighbour : neighbours) {
            if (neighbour == null
                    || neighbour.getCloudletId() < 0
                    || neighbour.getCloudletId() == self
                    || !ids.add(neighbour.getCloudletId())) {
                throw bad("Invalid/duplicate logical Task adjacency for " + self);
            }
        }
        return ids;
    }

    private static void validateGraph(Map<Integer, Node> nodes) {
        Map<Integer, Integer> remaining = new HashMap<>();
        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (Node node : nodes.values()) {
            for (int parent : node.parents) {
                Node p = nodes.get(parent);
                if (p == null || p.workflow != node.workflow || !p.children.contains(node.id)) {
                    throw bad("Missing, cross-workflow or asymmetric parent edge for Task " + node.id);
                }
            }
            for (int child : node.children) {
                Node c = nodes.get(child);
                if (c == null || c.workflow != node.workflow || !c.parents.contains(node.id)) {
                    throw bad("Missing, cross-workflow or asymmetric child edge for Task " + node.id);
                }
            }
            remaining.put(node.id, node.parents.size());
            if (node.parents.isEmpty()) {
                ready.add(node.id);
            }
        }
        int count = 0;
        while (!ready.isEmpty()) {
            Node node = nodes.get(ready.remove());
            count++;
            for (int child : node.children) {
                int next = remaining.get(child) - 1;
                remaining.put(child, next);
                if (next == 0) {
                    ready.add(child);
                }
            }
        }
        if (count != nodes.size()) {
            throw bad("Logical Task control graph contains a cycle");
        }
    }

    private static void validateProducers(Map<Integer, Node> nodes, Map<FileId, FileBuilder> files) {
        for (Node consumer : nodes.values()) {
            Set<Integer> required = new HashSet<>();
            for (FileId id : consumer.inputs.keySet()) {
                Integer producer = files.get(id).producer;
                if (producer != null) {
                    required.add(producer);
                }
            }
            if (required.isEmpty()) {
                continue;
            }
            Set<Integer> visited = new HashSet<>();
            Deque<Integer> ancestors = new ArrayDeque<>();
            for (int parent : consumer.parents) {
                if (visited.add(parent)) {
                    ancestors.addLast(parent);
                }
            }
            while (!required.isEmpty() && !ancestors.isEmpty()) {
                int next = ancestors.removeFirst();
                required.remove(next);
                if (!required.isEmpty()) {
                    for (int parent : nodes.get(next).parents) {
                        if (visited.add(parent)) {
                            ancestors.addLast(parent);
                        }
                    }
                }
            }
            if (!required.isEmpty()) {
                throw bad("File producer is not a control ancestor of Task " + consumer.id + ": " + required);
            }
        }
    }

    private static final class Node {
        final int id, workflow;
        final Set<Integer> parents, children;
        final Map<FileId, Long> inputs = new TreeMap<>();
        final Set<FileId> outputs = new TreeSet<>();

        Node(int id, int workflow, Set<Integer> parents, Set<Integer> children) {
            this.id = id;
            this.workflow = workflow;
            this.parents = parents;
            this.children = children;
        }
    }

    private static final class FileBuilder {
        final FileId id;
        final double bytes;
        Integer producer;
        final Set<Integer> consumers = new TreeSet<>();

        FileBuilder(FileId id, double bytes) {
            this.id = id;
            this.bytes = bytes;
        }
    }

    private static final class DemandBuilder {
        final FileDefinition file;
        long references;
        final Set<Integer> consumers = new TreeSet<>();

        DemandBuilder(FileDefinition file) {
            this.file = file;
        }
    }

    private static <T> List<T> immutableList(Collection<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }
}
