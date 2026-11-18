package org.workflowsim.examples.wfcommons;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.workflowsim.Task;
import org.workflowsim.WfCommonsJsonParser;
import org.workflowsim.utils.ReplicaCatalog;

public final class WfCommonsJsonParserValidationExample {

    private WfCommonsJsonParserValidationExample() {}

    public static void main(String[] args) throws Exception {
        if (args != null && args.length > 1) {
            throw new IllegalArgumentException("Usage: WfCommonsJsonParserValidationExample [path]");
        }
        String rootPath = args != null && args.length > 0 ? args[0] : "datasets/wfformat";
        List<File> jsonFiles = findJsonFiles(new File(rootPath));
        if (jsonFiles.isEmpty()) {
            throw new IllegalArgumentException("No WfCommons JSON files found under " + rootPath);
        }

        int totalTasks = 0;
        int totalEdges = 0;
        long startMillis = System.currentTimeMillis();
        WfCommonsJsonParser parser = new WfCommonsJsonParser();

        for (File jsonFile : jsonFiles) {

            ReplicaCatalog.init(ReplicaCatalog.FileSystem.LOCAL);
            WfCommonsJsonParser.ParseResult result = parser.parse(jsonFile.getPath(), 0, 1);
            List<Task> tasks = result.getTasks();
            int edgeCount = countEdges(tasks);
            validateTaskGraph(jsonFile, tasks);

            totalTasks += tasks.size();
            totalEdges += edgeCount;
            System.out.println(String.format(
                    "WFJSON file=%s tasks=%d edges=%d roots=%d leaves=%d",
                    jsonFile.getPath(), tasks.size(), edgeCount, countRoots(tasks), countLeaves(tasks)));
        }

        long elapsedMillis = System.currentTimeMillis() - startMillis;
        System.out.println(String.format(
                "WFJSON_VALIDATION PASSED files=%d tasks=%d edges=%d elapsed_ms=%d",
                jsonFiles.size(), totalTasks, totalEdges, elapsedMillis));
    }

    private static List<File> findJsonFiles(File root) throws IOException {
        if (!root.exists()) {
            throw new IOException("Path does not exist: " + root.getAbsolutePath());
        }

        List<File> jsonFiles = new ArrayList<>();
        collectJsonFiles(root, jsonFiles);
        Collections.sort(jsonFiles, new Comparator<File>() {
            @Override
            public int compare(File first, File second) {
                return first.getPath().compareTo(second.getPath());
            }
        });
        return jsonFiles;
    }

    private static void collectJsonFiles(File file, List<File> jsonFiles) {
        if (file.isFile()) {
            String name = file.getName().toLowerCase();

            if (name.endsWith(".json") && !"index.json".equals(name) && !name.endsWith(".manifest.json")) {
                jsonFiles.add(file);
            }
            return;
        }

        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectJsonFiles(child, jsonFiles);
        }
    }

    private static void validateTaskGraph(File jsonFile, List<Task> tasks) {
        if (tasks.isEmpty()) {
            throw new IllegalStateException("Parsed zero tasks from " + jsonFile.getPath());
        }
        for (Task task : tasks) {
            if (task.getCloudletLength() <= 0L) {
                throw new IllegalStateException(
                        "Task " + task.getCloudletId() + " has non-positive length in " + jsonFile.getPath());
            }
            if (task.getDepth() <= 0) {
                throw new IllegalStateException(
                        "Task " + task.getCloudletId() + " has non-positive depth in " + jsonFile.getPath());
            }
            for (Task child : task.getChildList()) {
                if (!child.getParentList().contains(task)) {
                    throw new IllegalStateException("Dependency is not symmetric in " + jsonFile.getPath());
                }
            }
            for (Task parent : task.getParentList()) {
                if (!parent.getChildList().contains(task)) {
                    throw new IllegalStateException("Dependency is not symmetric in " + jsonFile.getPath());
                }
            }
        }
    }

    private static int countEdges(List<Task> tasks) {
        int edgeCount = 0;
        for (Task task : tasks) {
            edgeCount += task.getChildList().size();
        }
        return edgeCount;
    }

    private static int countRoots(List<Task> tasks) {
        int rootCount = 0;
        for (Task task : tasks) {
            if (task.getParentList().isEmpty()) {
                rootCount++;
            }
        }
        return rootCount;
    }

    private static int countLeaves(List<Task> tasks) {
        int leafCount = 0;
        for (Task task : tasks) {
            if (task.getChildList().isEmpty()) {
                leafCount++;
            }
        }
        return leafCount;
    }
}
