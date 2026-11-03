package org.workflowsim.experiments.tutorials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.workflowsim.Task;
import org.workflowsim.WfCommonsJsonParser;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/** Tutorial cross-validation test for the DAX/WfCommons JSON parsing path. */
class ParserCrossValidationTest {

    private static final String WF_JSON_RELATIVE_PATH = "wfformat/montage/n100/montage-100-000.json";
    private static Path workflowPath;

    /** Initializes the static state required by the parser and validates the Maven-injected test dataset. */
    @BeforeAll
    static void setUp() {
        Parameters.init(
                20,
                (String) null,
                null,
                null,
                null,
                null,
                Parameters.SchedulingAlgorithm.INVALID,
                Parameters.PlanningAlgorithm.INVALID,
                null,
                0);
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.SHARED);

        String configuredRoot = System.getProperty("workflowsim.datasetRoot");
        if (configuredRoot == null || configuredRoot.trim().isEmpty()) {
            throw new IllegalStateException("Missing required test property workflowsim.datasetRoot");
        }
        Path datasetRoot = Paths.get(configuredRoot);
        if (!datasetRoot.isAbsolute() || !Files.isDirectory(datasetRoot)) {
            throw new IllegalStateException("Invalid absolute dataset root: " + configuredRoot);
        }
        workflowPath = datasetRoot.resolve(WF_JSON_RELATIVE_PATH).normalize();
        if (!workflowPath.startsWith(datasetRoot.normalize())) {
            throw new IllegalStateException("Invalid tutorial input path: " + workflowPath);
        }
        // R9 resident-corpus guard: montage-100-000.json (145 KB) is committed (see the .gitignore
        // negation); this test must run in the standard build and may no longer be skipped.
        assertTrue(Files.isRegularFile(workflowPath), "Resident WfCommons corpus must be present at " + workflowPath);
    }

    @Test
    void wfCommonsParserProducesValidTaskList() throws IOException {
        WfCommonsJsonParser.ParseResult result = parse();

        assertNotNull(result);
        List<Task> tasks = result.getTasks();
        assertFalse(tasks.isEmpty(), "Montage-100 JSON must parse into at least one task");
    }

    @Test
    void wfCommonsTasksHaveConsistentDependencies() throws IOException {
        List<Task> tasks = parse().getTasks();

        for (Task task : tasks) {
            for (Task child : task.getChildList()) {
                assertTrue(
                        child.getParentList().contains(task),
                        String.format(
                                "Task %d is a child of Task %d, but the parentList of Task %d does not contain %d",
                                child.getCloudletId(),
                                task.getCloudletId(),
                                child.getCloudletId(),
                                task.getCloudletId()));
            }
        }
    }

    @Test
    void wfCommonsTasksHavePositiveDepth() throws IOException {
        for (Task task : parse().getTasks()) {
            assertTrue(task.getDepth() >= 1, "Task " + task.getCloudletId() + " depth must be >= 1");
        }
    }

    @Test
    void wfCommonsTasksHavePositiveCloudletLength() throws IOException {
        for (Task task : parse().getTasks()) {
            assertTrue(task.getCloudletLength() > 0, "Task " + task.getCloudletId() + " length must be > 0");
        }
    }

    @Test
    void wfCommonsNextTaskIdIsConsistent() throws IOException {
        WfCommonsJsonParser.ParseResult result = parse();
        assertEquals(result.getTasks().size(), result.getNextTaskId(), "nextTaskId must equal the task count from 0");
    }

    private static WfCommonsJsonParser.ParseResult parse() throws IOException {
        return new WfCommonsJsonParser().parse(workflowPath.toString(), 0, 0);
    }
}
